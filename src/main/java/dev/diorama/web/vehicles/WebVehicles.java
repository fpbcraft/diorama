package dev.diorama.web.vehicles;

import com.google.gson.Gson;
import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.api.WebVehicleScene;
import dev.diorama.bake.*;
import dev.diorama.compat.ContraptionBodies;
import dev.diorama.compat.SubLevelBodies;
import dev.diorama.craft.Body;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import org.joml.Matrix3d;
import org.joml.Quaterniond;

/** Server-thread capture, bounded immutable handoff, low-priority craft tracing and publication. */
public final class WebVehicles implements AutoCloseable {
	private static final int LIMIT = 64;
	private record Captured(String provider, UUID id, String dimension, Body.Blocks blocks, double[] pose) {
		String key() { return provider + "/" + id; }
	}
	private record Input(long time, List<Captured> bodies) {}
	private record CapturedBlocks(Body.Blocks blocks, long version, long time) {}
	private record Baked(Body.Blocks blocks, double[] linear, double y, VehicleSprites.Fileset files) {}
	private record Shadow(VehicleSprites.Fileset files, double[] center, double[] pose, Body.Blocks blocks, long madeAt) {}
	private final Path root;
	private final VehicleShadows shadows;
	private final Map<String, Shadow> shaded = new HashMap<>();
	private long nextShadow;
	private long nextClean;
	private int first;

	private final Map<String, List<Captured>> dimensions = new LinkedHashMap<>(); // server thread
	private final LinkedHashMap<String, CapturedBlocks> blocks = new LinkedHashMap<>(); // server thread
	private final Map<String, Baked> baked = new HashMap<>(); // publisher thread
	private final Thread worker;
	private volatile Input input = new Input(0, List.of());
	private volatile boolean closed;
	private volatile Looks looks;
	private long nextCopy; // at most one bounded grid copy across all dimensions per two ticks
	private long nextBake;

	public WebVehicles(Path webRoot, VanillaTable table, Path modsFolder) {
		this.root = webRoot.resolve("assets/diorama-vehicles");
		this.shadows = new VehicleShadows(webRoot);
		worker = new Thread(() -> run(table, modsFolder), "Diorama vehicle publisher");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}
	/** Invoked after level ticks. Never loads a chunk and never performs image or file work. */
	public void tick(ServerLevel level) {
		Looks table = looks;
		if (closed || table == null || level.getGameTime() % 10 != 0) return;
		String dimension = level.dimension().identifier().toString();
		List<Captured> found = new ArrayList<>();
		collect("create_contraptions", ContraptionBodies.bodies(level.getAllEntities()), dimension, found);
		collect("sable_ships", SubLevelBodies.bodies(level), dimension, found);
		dimensions.put(dimension, List.copyOf(found));
		List<Captured> all = dimensions.values().stream().flatMap(List::stream).limit(LIMIT).toList();
		Set<String> keep = new HashSet<>();
		for (Captured body : all) keep.add(body.key());
		blocks.keySet().retainAll(keep);
		input = new Input(System.currentTimeMillis(), all);
	}
	private void collect(String provider, List<Body> bodies, String dimension, List<Captured> found) {
		long now = System.currentTimeMillis();
		for (Body body : bodies) {
			if (found.size() >= LIMIT) break;
			String key = provider + "/" + body.id();
			CapturedBlocks old = blocks.get(key);
			if (old == null && blocks.size() >= LIMIT) continue;
			if ((old == null || old.version != body.version() || now - old.time > 30000) && now >= nextCopy) {
				nextCopy = now + 100;
				Body.Blocks copy = body.read(looks);
				if (copy != null && copy.blocks().volume() <= 262144) {
					if (old != null && Arrays.equals(copy.min(), old.blocks.min()) && copy.blocks().sameBlocks(old.blocks.blocks())) copy = old.blocks;
					old = new CapturedBlocks(copy, body.version(), now); blocks.put(key, old);
				}
			}
			if (old == null) continue;
			double[] pose = new double[12];
			if (!body.pose(old.blocks.min(), 1, pose) || Arrays.stream(pose).anyMatch(v -> !Double.isFinite(v))) continue;
			found.add(new Captured(provider, body.id(), dimension, old.blocks, pose));
		}
	}
	private void run(VanillaTable table, Path modsFolder) {
		JarModels jars = null;
		try {
			Files.createDirectories(root);
			jars = JarModels.of(modsFolder);
			looks = new TableLooks(table, jars);
			while (!closed) {
				Input current = input;
				try { publish(current); }
				catch (java.io.IOException | RuntimeException error) { Diorama.LOGGER.warn("Couldn't publish vehicles; retrying", error); Thread.sleep(5000); }
				Thread.sleep(250);
			}
		} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		catch (Exception | LinkageError error) { Diorama.LOGGER.warn("Vehicle publisher stopped", error); }
		finally { looks = null; if (jars != null) jars.close(); WebVehicleScene.clear(); }
	}
	private void publish(Input current) throws java.io.IOException {
		List<Map<String, Object>> rows = new ArrayList<>();
		List<WebVehicleScene.Vehicle> publicRows = new ArrayList<>();
		Set<String> keep = new HashSet<>();
		List<Captured> ordered = new ArrayList<>(current.bodies);
		if (!ordered.isEmpty()) Collections.rotate(ordered, -Math.floorMod(first++, ordered.size()));
		for (Captured body : ordered) {
			keep.add(body.key());
			Baked ready = image(body);
			if (ready == null) continue;
			double[] p = body.pose, b = {body.blocks.blocks().sizeX / 2.0, body.blocks.blocks().sizeY / 2.0, body.blocks.blocks().sizeZ / 2.0};
			double[] center = new double[3];
			for (int a = 0; a < 3; a++) center[a] = p[a] + p[3+a]*b[0] + p[6+a]*b[1] + p[9+a]*b[2];
			Quaterniond q = new Matrix3d().set(Arrays.copyOfRange(p, 3, 12)).getUnnormalizedRotation(new Quaterniond()).normalize();
			String sprite = "assets/diorama-vehicles/" + ready.files.sprite(), atlas = "assets/diorama-vehicles/" + ready.files.atlas();
			String label = body.provider.equals("sable_ships") ? "Sable ship" : "Create contraption";
			rows.add(Map.of("id", body.key(), "label", label, "dimension", body.dimension,
				"mesh", sprite, "atlas", atlas, "pos", center, "rot", new double[]{q.x,q.y,q.z,q.w}));
			Shadow shadow = shadow(body, center);
			if (shadow != null) rows.add(Map.of("id", body.key() + "/shadow", "label", label,
				"dimension", body.dimension, "shadow", true, "mesh", "assets/diorama-vehicles/" + shadow.files.sprite(),
				"atlas", "assets/diorama-vehicles/" + shadow.files.atlas(), "pos", shadowPosition(shadow,center), "rot", new double[]{0,0,0,1}));
			publicRows.add(new WebVehicleScene.Vehicle(body.provider, body.id.toString(), label, body.dimension,
				ready.files.version(), center[0],center[1],center[2],q.x,q.y,q.z,q.w,
				root.resolve(ready.files.sprite()), root.resolve(ready.files.atlas())));
		}
		baked.keySet().retainAll(keep); shaded.keySet().retainAll(keep);
		cleanup();
		Map<String, Object> feed = Map.of("generatedAt", current.time, "intervalMs", 500,
			"format", "diorama-craft-sprites-v1", "objects", rows);
		if (closed) return;
		VehicleSprites.atomic(root.resolve("scene.json"), new Gson().toJson(feed).getBytes(StandardCharsets.UTF_8));
		WebVehicleScene.publish(new WebVehicleScene.Snapshot(current.time, publicRows));
	}
	private Shadow shadow(Captured body, double[] center) throws java.io.IOException {
		Shadow previous = shaded.get(body.key());
		if (previous != null && previous.blocks == body.blocks && Arrays.equals(previous.pose, body.pose)
			&& System.currentTimeMillis() - previous.madeAt < 5000) return previous;
		if (System.currentTimeMillis() < nextShadow) return previous;
		long start = System.currentTimeMillis();
		var made = shadows.bake(body.dimension, body.blocks.blocks(), body.pose, center, looks);
		if (closed) return previous;
		if (made != null) {
			previous = new Shadow(VehicleSprites.write(root, made), center.clone(), body.pose.clone(), body.blocks, System.currentTimeMillis());
			shaded.put(body.key(), previous);
		} else { shaded.remove(body.key()); previous = null; }
		nextShadow = System.currentTimeMillis() + Math.max(500, (System.currentTimeMillis() - start) * 4);
		return previous;
	}
	private static double[] shadowPosition(Shadow shadow, double[] center) {
		double run = (center[1] - shadow.center[1]) / Math.tan(Math.toRadians(MapStyle.get().sunElevation()));
		return new double[] {center[0] - TileBaker.SUN_AZIMUTH_X * run, shadow.center[1], center[2] - TileBaker.SUN_AZIMUTH_Z * run};
	}

	private void cleanup() throws java.io.IOException {
		long now = System.currentTimeMillis(); if (now < nextClean) return; nextClean = now + 15000;
		Set<String> active = new HashSet<>();
		for (Baked image : baked.values()) { active.add(image.files.sprite()); active.add(image.files.atlas()); }
		for (Shadow shadow : shaded.values()) { active.add(shadow.files.sprite()); active.add(shadow.files.atlas()); }
		try (var files = Files.newDirectoryStream(root)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (name.matches("[a-f0-9]{64}\\.(dspr|png)") && !active.contains(name)
					&& now - Files.getLastModifiedTime(file).toMillis() > 60000) Files.deleteIfExists(file);
			}
		}
	}

	private Baked image(Captured body) throws java.io.IOException {
		double[] pose = body.pose.clone();
		for (int i = 0; i < 12; i += 3) {
			double x = pose[i], z = pose[i+2]; pose[i] = Projection.facing().x(x,z); pose[i+2] = Projection.facing().z(x,z);
		}
		double[] linear = Arrays.copyOfRange(pose, 3, 12);
		Baked old = baked.get(body.key());
		if (old != null && old.blocks == body.blocks && Math.abs(old.y - pose[1]) < 4
			&& !CraftBaker.stretched(old.linear, linear, body.blocks.blocks())) return old;
		if (System.currentTimeMillis() < nextBake) return old;
		long started = System.currentTimeMillis();
		CraftBaker.Image image = CraftBaker.bake(body.blocks.blocks(), linear, pose[1], looks, MapStyle.get(), false);
		if (closed) return old;
		if (image == null) { nextBake = started + 1000; return old; }
		Baked made = new Baked(body.blocks, image.linear(), pose[1], VehicleSprites.write(root, image));
		baked.put(body.key(), made);
		// Use at most a fifth of this background thread for tracing, as the client does.
		nextBake = System.currentTimeMillis() + Math.max(100, (System.currentTimeMillis() - started) * 4);
		return made;
	}
	@Override public void close() {
		closed = true; worker.interrupt(); WebVehicleScene.clear();
	}
}
