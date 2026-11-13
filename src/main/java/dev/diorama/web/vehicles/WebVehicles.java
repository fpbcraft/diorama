package dev.diorama.web.vehicles;

import com.google.gson.Gson;
import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.api.WebVehicleScene;
import dev.diorama.bake.*;
import dev.diorama.compat.ContraptionBodies;
import dev.diorama.compat.SubLevelBodies;
import dev.diorama.compat.TrainBodies;
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
	private record Baked(Body.Blocks blocks, double[] linear, double y, VehicleSprites.Fileset files, String caster) {}
	private final Path root;
	private final java.util.function.BooleanSupplier active;
	private long nextClean;
	private int first;

	private final Map<String, List<Captured>> dimensions = new LinkedHashMap<>(); // server thread
	private final Map<String,Captured> lastCaptured = new HashMap<>();
	private final LinkedHashMap<String, CapturedBlocks> blocks = new LinkedHashMap<>(); // server thread
	private final Map<String, Baked> baked = new java.util.concurrent.ConcurrentHashMap<>(); // baker writes; publisher reads
	private final Thread worker;
	private volatile Thread publisher;
	private volatile Input input = new Input(0, List.of());
	private volatile boolean closed;
	private volatile Looks looks;
	private final Map<String,Integer> captureCursor = new HashMap<>();
	private long nextCopy; // at most one bounded grid copy across all dimensions per two ticks
	private long nextBake;

	public WebVehicles(Path webRoot, VanillaTable table, Path modsFolder, java.util.function.BooleanSupplier active) {
		this.active = active;
		this.root = webRoot.resolve("assets/diorama-vehicles");
		worker = new Thread(() -> run(table, modsFolder), "Diorama vehicle publisher");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}
	/** Invoked after level ticks. Never loads a chunk and never performs image or file work. */
	public void tick(ServerLevel level) {
		Looks table = looks;
		if (closed || table == null || level.getGameTime() % 2 != 0) return;
		String dimension = level.dimension().identifier().toString();
		List<Captured> found = new ArrayList<>();
		List<Body> trains = TrainBodies.bodies(level);
		collect("create_contraptions", ContraptionBodies.bodies(level.getAllEntities()).stream().filter(b -> !TrainBodies.isCarriage(level.getEntity(b.id()))).toList(), dimension, found);
		collect("create_trains", trains, dimension, found);
		collect("sable_ships", SubLevelBodies.bodies(level), dimension, found);
		dimensions.put(dimension, List.copyOf(found));
		List<Captured> all = dimensions.values().stream().flatMap(List::stream).limit(LIMIT).toList();
		Set<String> keep = new HashSet<>();
		for (Captured body : all) keep.add(body.key());
		blocks.keySet().retainAll(keep);
		lastCaptured.keySet().retainAll(keep);
		input = new Input(System.currentTimeMillis(), all);
	}
	private void collect(String provider, List<Body> bodies, String dimension, List<Captured> found) {
		long now = System.currentTimeMillis();
		List<Body> ordered = new ArrayList<>(bodies);
		String cursor = dimension + "/" + provider;
		int start = captureCursor.merge(cursor,1,Integer::sum);
		if(!ordered.isEmpty())Collections.rotate(ordered,-Math.floorMod(start,ordered.size()));
		for (Body body : ordered) {
			if (found.size() >= LIMIT) break;
			String key = provider + "/" + body.id();
			CapturedBlocks old = blocks.get(key);
			if (old == null && blocks.size() >= LIMIT) continue;
			if ((old == null || old.version != body.version() || now - old.time > 30000) && now >= nextCopy && body.geometryAvailable()) {
				nextCopy = now + 100;
				Body.Blocks copy = body.read(looks);
				if (copy != null && copy.blocks().volume() <= 262144) {
					if (old != null && Arrays.equals(copy.min(), old.blocks.min()) && copy.blocks().sameBlocks(old.blocks.blocks())) copy = old.blocks;
					old = new CapturedBlocks(copy, body.version(), now); blocks.put(key, old);
				}
			}
			if (old == null) continue;
			double[] pose = new double[12];
			if (!body.pose(old.blocks.min(), 1, pose) || Arrays.stream(pose).anyMatch(v -> !Double.isFinite(v))) {
				Captured last=lastCaptured.get(key);
				if(last!=null && last.dimension.equals(dimension))found.add(last);
				continue;
			}
			Captured captured = new Captured(provider, body.id(), dimension, old.blocks, pose);
			lastCaptured.put(key,captured);found.add(captured);
		}
	}
	private void run(VanillaTable table, Path modsFolder) {
		JarModels jars = null;
		try {
			Files.createDirectories(root);
			jars = JarModels.of(modsFolder);
			looks = new TableLooks(table, jars);
			publisher = new Thread(this::publishLoop,"Diorama vehicle poses");
			publisher.setDaemon(true); publisher.setPriority(Thread.MIN_PRIORITY); publisher.start();
			while (!closed) {
				if (!active.getAsBoolean()) { Thread.sleep(1000); continue; }
				Input current = input;
				try { prepare(current); }
				catch (java.io.IOException | RuntimeException error) { Diorama.LOGGER.warn("Couldn't publish vehicles; retrying", error); Thread.sleep(5000); }
				Thread.sleep(100);
			}
		} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		catch (Exception | LinkageError error) { Diorama.LOGGER.warn("Vehicle publisher stopped", error); }
		finally { looks = null; Thread poses=publisher;if(poses!=null)poses.interrupt(); if (jars != null) jars.close(); WebVehicleScene.clear(); }
	}
	private void publishLoop() {
		while (!closed) {
			try {
				if (active.getAsBoolean() && input.time > 0) publish(input);
				Thread.sleep(100);
			} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
			catch (java.io.IOException | RuntimeException error) { Diorama.LOGGER.warn("Couldn't publish vehicle poses",error);
				try { Thread.sleep(1000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt();return; }
			}
		}
	}
	private void prepare(Input current) throws java.io.IOException {
		List<Captured> ordered = new ArrayList<>(current.bodies);
		if (!ordered.isEmpty()) Collections.rotate(ordered,-Math.floorMod(first++,ordered.size()));
		for(Captured body:ordered) image(body);
		baked.keySet().retainAll(current.bodies.stream().map(Captured::key).collect(java.util.stream.Collectors.toSet()));
		cleanup();
	}
	private void publish(Input current) throws java.io.IOException {
		List<Map<String, Object>> rows = new ArrayList<>();
		List<WebVehicleScene.Vehicle> publicRows = new ArrayList<>();
		for (Captured body : current.bodies) {
			Baked ready = baked.get(body.key());
			if (ready == null) continue;
			double[] p = body.pose, b = {ready.blocks.blocks().sizeX / 2.0, ready.blocks.blocks().sizeY / 2.0, ready.blocks.blocks().sizeZ / 2.0};
			for(int a=0;a<3;a++)b[a] += ready.blocks.min()[a] - body.blocks.min()[a];
			double[] center = new double[3];
			for (int a = 0; a < 3; a++) center[a] = p[a] + p[3+a]*b[0] + p[6+a]*b[1] + p[9+a]*b[2];
			Quaterniond q = new Matrix3d().set(Arrays.copyOfRange(p, 3, 12)).getUnnormalizedRotation(new Quaterniond()).normalize();
			String sprite = "assets/diorama-vehicles/" + ready.files.sprite(), atlas = "assets/diorama-vehicles/" + ready.files.atlas();
			String label = body.provider.equals("sable_ships") ? "Sable ship" : body.provider.equals("create_trains") ? "Create train" : "Create contraption";
			rows.add(Map.of("id", body.key(), "label", label, "dimension", body.dimension,
				"mesh", sprite, "atlas", atlas, "pos", center, "rot", new double[]{q.x,q.y,q.z,q.w},
				"caster", ready.caster == null ? "" : "assets/diorama-vehicles/" + ready.caster));
			publicRows.add(new WebVehicleScene.Vehicle(body.provider, body.id.toString(), label, body.dimension,
				ready.files.version(), center[0],center[1],center[2],q.x,q.y,q.z,q.w,
				root.resolve(ready.files.sprite()), root.resolve(ready.files.atlas())));
		}
		Map<String, Object> feed = Map.of("generatedAt", current.time, "intervalMs", 100,
			"format", "diorama-craft-sprites-v1", "objects", rows);
		if (closed) return;
		VehicleSprites.atomic(root.resolve("scene.json"), new Gson().toJson(feed).getBytes(StandardCharsets.UTF_8));
		WebVehicleScene.publish(new WebVehicleScene.Snapshot(current.time, publicRows));
	}
	private void cleanup() throws java.io.IOException {
		long now = System.currentTimeMillis(); if (now < nextClean) return; nextClean = now + 15000;
		Set<String> active = new HashSet<>();
		for (Baked image : baked.values()) { active.add(image.files.sprite()); active.add(image.files.atlas()); if(image.caster != null) active.add(image.caster); }
		try (var files = Files.newDirectoryStream(root)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (name.matches("[a-f0-9]{64}\\.(dspr|png|dshd)") && !active.contains(name)
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
		var shape = CraftShadow.silhouette(body.blocks.blocks(), linear, looks, MapStyle.get());
		double[] offset = new double[3];
		double[] half = {body.blocks.blocks().sizeX/2.0, body.blocks.blocks().sizeY/2.0, body.blocks.blocks().sizeZ/2.0};
		for(int a=0;a<3;a++) offset[a] = -(linear[a]*half[0]+linear[3+a]*half[1]+linear[6+a]*half[2]);
		double[] relativePose = pose.clone();
		System.arraycopy(offset,0,relativePose,0,3);
		String caster = shape == null ? null : VehicleCaster.write(root,shape,offset,body.blocks.blocks().box(relativePose));
		Baked made = new Baked(body.blocks, image.linear(), pose[1], VehicleSprites.write(root, image),caster);
		baked.put(body.key(), made);
		// Use at most a fifth of this background thread for tracing, as the client does.
		nextBake = System.currentTimeMillis() + Math.max(100, (System.currentTimeMillis() - started) * 4);
		return made;
	}
	@Override public void close() {
		closed = true; worker.interrupt(); Thread poses=publisher; if(poses!=null)poses.interrupt(); WebVehicleScene.clear();
	}
}
