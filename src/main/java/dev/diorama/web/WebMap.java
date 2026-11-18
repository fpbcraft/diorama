package dev.diorama.web;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.JarModels;
import dev.diorama.bake.TableLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VanillaTable;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.SavedChunkSource;
import dev.diorama.share.ServerLocks;
import dev.diorama.share.ShareServer;
import dev.diorama.web.WebChunkState.Drawn;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The server's web map: Diorama's tracer drawing the world from its save, in vanilla colours, on
 * one background thread, into JPEG tiles a browser can pan, as BlueMap and Dynmap draw theirs.
 * Off unless the owner turns it on ({@link WebSettings}); dedicated servers only.
 *
 * <p>The first pass draws every tile with land the shared map shows (by default, land players
 * spent time near: vanilla's inhabited time). After that, at most every 5 minutes, a pass draws
 * the tiles of chunks the region files say were saved since the last pass with other blocks or
 * biomes; so a build shows once the server has saved it, with nothing to press.
 * {@code /diorama-web redraw} draws everything again.
 *
 * <p>It shows no more than the map in the game would: a dimension the server's settings turn the
 * map off in ({@link ServerLocks}) isn't drawn, and the pictures it had are deleted; see
 * {@link #leftOut}.
 *
 * <p>Reads only the region files ({@link ServerSave}), so it never touches loaded chunks or the
 * server's storage thread, and names no client class.
 */
public final class WebMap {
	/** How often the region files are looked at for saved chunks between passes. */
	private static final long CHECK_MS = 30_000;
	/**
	 * The least time from one pass's start to the next: vanilla's autosave interval. A running
	 * server saves busy chunks (farms, redstone) every minute or so; this redraws them at most this
	 * often rather than every time.
	 */
	private static final long PASS_GAP_MS = 5 * 60_000;
	/** A tick past this and the server is close to slowing down: the map waits. */
	private static final long SERVER_BEHIND_NANOS = 40_000_000;
	/** A busy but still sub-50-mspt server may render one tile slowly after this wait. */
	private static final long BUSY_GRACE_MS = 30_000;
	private static final long BUSY_TILE_GAP_MS = 15_000;
	/** Incomplete passes retry soon, without hammering a server whose save is busy. */
	private static final long RETRY_GAP_MS = 30_000;
	/** Throttle periodic stage/progress logging so large worlds don't flood the log. */
	private static final long PROGRESS_LOG_MS = 30_000;
	/** A pass failed or was interrupted and must retry. */
	private static final long PASS_INCOMPLETE = -1;
	/** An incremental pass found nothing changed; it completed successfully. */
	private static final long PASS_UNCHANGED = -2;
	/** Blend radius for biome tints: vanilla's default. */
	private static final int BLEND_RADIUS = 2;
	/** The most zoom levels out from the map's own tiles. */
	private static final int MAX_ZOOM = 10;
	/** A tile or its lossless, coverage, depth or ground companion: its place across and down. */
	private static final java.util.regex.Pattern TILE_NAME = java.util.regex.Pattern.compile("-?[0-9]+_-?[0-9]+[.](jpg|png|fog[.]png|depth|ground)");

	private static @Nullable WebMap running;
	private static Path configDirectory;
	private static Path modsDirectory;
	private static final AtomicBoolean reloading = new AtomicBoolean();
	private static volatile boolean stoppingServer;

	private final MinecraftServer server;
	private final WebSettings settings;
	private final LiveTerrain live;
	private dev.diorama.web.vehicles.WebVehicles vehicles;
	private final WebWaypoints waypoints;
	private WebQueueStatus queueStatus;
	private final WebTracks tracks=new WebTracks();
	private final VanillaTable table;
	private final Path modsFolder;
	final Path folder;
	private final Map<String, Dimension> dimensions = new ConcurrentHashMap<>();
	/** The folders, in lower case, of the dimensions the settings keep off the map: the map's web server serves nothing in them. */
	private final Set<String> closed = ConcurrentHashMap.newKeySet();
	/** The folders a warning has been logged for, with pictures that couldn't be deleted. */
	private final Set<String> warnedKept = new HashSet<>();
	private final Object wake = new Object();
	private final Thread thread;
	private volatile @Nullable WebServer http;
	private volatile boolean stopping;
	/** A changed appearance or land policy requires a refresh on the first pass. */
	private final java.util.concurrent.atomic.AtomicBoolean redrawAll = new java.util.concurrent.atomic.AtomicBoolean();
	/** An interrupted automatic pass must retry even if no newer chunks were saved. */
	private boolean redrawOwed;
	private volatile String status = "starting";
	private volatile String automaticProgress = "";
	private volatile boolean paused;
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private final WebRenderTasks tasks;
	private boolean taskBakeFailed;
	private long nextTaskAttempt;
	private String taskPlanInputs;
	/** Passes over every dimension finished since the server started. */
	private volatile int passes;

	private WebMap(MinecraftServer server, WebSettings settings, VanillaTable table, Path modsFolder) {
		this.server = server;
		this.settings = settings;
		this.live = new LiveTerrain(settings.updateSeconds() * 1000L);
		this.table = table;
		this.modsFolder = modsFolder;
		this.folder = settings.folder() != null ? settings.folder() : server.getWorldPath(LevelResource.ROOT).resolve("diorama-web");
		this.tasks = new WebRenderTasks(this.folder.resolve("render-tasks.dat"));
		for (ServerLevel level : server.getAllLevels()) {
			if (this.leftOut(level)) this.closed.add(folderName(level.dimension().identifier().toString()).toLowerCase(Locale.ROOT));
		}
		for (String id : ShareServer.locks(server).mapOffDimensions()) this.closed.add(folderName(id).toLowerCase(Locale.ROOT));
		this.waypoints=new WebWaypoints(server.getWorldPath(LevelResource.ROOT),this.folder,System::currentTimeMillis,
			id -> !this.closed.contains(folderName(id).toLowerCase(Locale.ROOT)));
		this.thread = new Thread(this::run, "Diorama web map");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/** Starts the web map if the settings turn it on. Server thread, once the server has started. */
	public static synchronized void started(MinecraftServer server, Path configFolder, Path modsFolder) {
		configDirectory = configFolder;
		modsDirectory = modsFolder;
		stoppingServer = false;
		if (!server.isDedicatedServer()) {
			return;
		}
		WebSettings settings = WebSettings.load(configFolder);
		dev.diorama.api.WebVehicleScene.configureHistory(settings.enabled() && settings.vehicles() && settings.vehicleHistory(), settings.vehicleHistoryIntervalMs());
		if (!settings.enabled()) {
			Diorama.LOGGER.info("Diorama's web map is off (web-map in config/{})", WebSettings.FILE);
			return;
		}
		VanillaTable table = WebAppearance.load(configFolder);
		if (table == null) {
			Diorama.LOGGER.warn("Diorama's web map can't start: this jar has no vanilla colour table");
			return;
		}
		WebMap map = new WebMap(server, settings, table, modsFolder);
		map.waypoints.icons(configFolder.resolve("diorama-web-appearance/waypoint-icons.json"));
		if (map.start()) {
			running = map;
			map.waypoints.start();
			map.queueStatus=new WebQueueStatus(map.folder, map::queuedStatus, () -> map.paused, () -> map.automaticProgress);
			if (settings.vehicles()) map.vehicles = new dev.diorama.web.vehicles.WebVehicles(map.folder, table, modsFolder,
				() -> !map.stopping && !map.paused && !map.serverBehind() && !server.isCurrentlySaving());
		}
	}

	/** Retire all writers before reopening their files; never wait for rendering on the server thread. */
	public static boolean reload(MinecraftServer server, java.util.function.Consumer<String> feedback) throws IOException {
		if (!server.isDedicatedServer() || configDirectory == null) {
			feedback.accept("Diorama server config reload is only available on a dedicated server.");
			return false;
		}
		// A malformed or unreadable file must not replace a working configuration with defaults.
		try (var reader = Files.newBufferedReader(configDirectory.resolve(WebSettings.FILE), java.nio.charset.StandardCharsets.UTF_8)) {
			new Properties().load(reader);
		}
		WebSettings settings = WebSettings.load(configDirectory);
		if (stoppingServer || !reloading.compareAndSet(false, true)) {
			feedback.accept("Diorama config reload is already in progress.");
			return false;
		}
		Path config = configDirectory, mods = modsDirectory;
		WebMap previous = running;
		boolean wasPaused = previous != null && previous.paused;
		dev.diorama.api.WebVehicleScene.configureHistory(settings.enabled() && settings.vehicles() && settings.vehicleHistory(), settings.vehicleHistoryIntervalMs());
		Thread reload = new Thread(() -> {
			try {
				stopRunning();
				if (previous != null) {
					previous.thread.join();
					if (previous.vehicles != null) previous.vehicles.awaitStopped();
					previous.waypoints.awaitStopped();
					if (previous.queueStatus != null) previous.queueStatus.awaitStopped();
				}
				server.execute(() -> {
					try {
						if (stoppingServer) return;
						ShareServer.reload(server, config);
						started(server, config, mods);
						WebMap current = running;
						if (wasPaused && current != null) current.pause();
						String result = settings.enabled() && current == null
							? "Diorama config read, but the web map could not restart; see the server log."
							: "Reloaded diorama-server.properties; vehicle history " + (settings.vehicleHistory() ? "enabled" : "disabled")
								+ ", interval " + settings.vehicleHistoryIntervalMs() + " ms. Existing terrain and history kept.";
						Diorama.LOGGER.info(result);
						feedback.accept(result);
					} catch (RuntimeException error) {
						Diorama.LOGGER.error("Diorama config reload failed", error);
						feedback.accept("Diorama config reload failed; see the server log.");
					} finally { reloading.set(false); }
				});
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				reloading.set(false);
				Diorama.LOGGER.warn("Diorama config reload was interrupted", error);
			} catch (RuntimeException error) {
				reloading.set(false);
				Diorama.LOGGER.error("Diorama config reload failed while stopping background writers", error);
				server.execute(() -> feedback.accept("Diorama config reload failed; see the server log."));
			}
		}, "Diorama config reload");
		reload.setDaemon(true);
		reload.start();
		return true;
	}

	/** Stops the web map, if it runs. Server thread. */
	public static synchronized void stopped() {
		stoppingServer = true;
		stopRunning();
	}

	private static synchronized void stopRunning() {
		WebMap map = running;
		running = null;
		if (map != null) {
			if (map.vehicles != null) map.vehicles.close();
			map.waypoints.close();if(map.queueStatus!=null)map.queueStatus.close();
			map.stop();
		}
	}

	/** Called only by the authenticated Minecraft connection, never an HTTP upload. */
	public static boolean receiveWaypoints(net.minecraft.server.level.ServerPlayer player,byte[] data) {
		if(data.length==0||data[0]!=WebWaypoints.MESSAGE)return false;
		WebMap map=running;
		if(map!=null&&player.level().getServer()==map.server)try {map.waypoints.receive(player.getUUID(),data);}
		catch(java.io.IOException|RuntimeException error){Diorama.LOGGER.debug("Invalid public waypoint snapshot from {}",player.getUUID());}
		return true;
	}
	/** Cheap dirty notification: no world reads, disk IO or rendering. */
	public static void blockChanged(ServerLevel level, net.minecraft.core.BlockPos pos) {
		WebMap map = running;
		if (map != null && !map.stopping && level.getServer().isSameThread() && !map.leftOut(level)) map.live.mark(level, pos);
	}
	/** At most one loaded-chunk copy per level tick; busy servers defer capture. */
	public static void tick(ServerLevel level) {
		WebMap map = running;
		if (map == null || map.stopping || map.paused || map.serverBehind() || map.server.isCurrentlySaving() || map.leftOut(level)) return;
		try {
			if (level == map.server.overworld() && map.tracks.tick()) synchronized(map.wake){map.wake.notifyAll();}
			if (map.vehicles != null) map.vehicles.tick(level);
			if (map.live.capture(level, map.settings.everyChunk())) synchronized (map.wake) { map.wake.notifyAll(); }
		} catch (RuntimeException | LinkageError error) {
			Diorama.LOGGER.warn("Couldn't capture a live web-map chunk; saved-world rendering remains available", error);
		}
	}
	private boolean handleLiveUpdates(TableLooks looks) {
		if (this.paused || !this.live.hasUpdates()) return false;
		Set<Dimension> changed = new HashSet<>();
		for (LiveTerrain.Changed update : this.live.drain()) {
			for (ServerLevel level : this.server.getAllLevels()) {
				if (!level.dimension().identifier().toString().equals(update.dimension()) || this.leftOut(level)) continue;
				Dimension dimension = this.dimensions.computeIfAbsent(update.dimension(), id -> new Dimension(this, level, id));
				Set<Long> tiles = new HashSet<>();
				ChunkSnapshot snapshot = update.snapshot();
				// Include the old structure's full height: removing a tower must clear its former pixels/shadows.
				Drawn previous = dimension.drawn.get(update.key());
				dimension.markTiles(snapshot.chunkX, snapshot.chunkZ,
					previous == null ? level.getMaxY() : Math.max(previous.top(), snapshot.maxY),
					previous == null ? level.getMinY() : Math.min(previous.floor(), snapshot.floorY), tiles);
				dimension.source.clear();
				dimension.pending.addFirst(tiles);
				dimension.pending.save();
				dimension.queuedCount = dimension.pending.size();
				changed.add(dimension);
				break;
			}
		}
		for (Dimension dimension : changed) dimension.renderQueued(looks, 32);
		return !changed.isEmpty();
	}

	/** The running web map, or null. */
	public static @Nullable WebMap get() {
		return running;
	}

	/** Starts drawing and serving; false (logged) if the folder can't be written. */
	private boolean start() {
		try {
			Files.createDirectories(this.folder);
			// Old public feeds may contain coordinates from a dimension now excluded by server locks.
			// Their publishers replace them with current snapshots after startup.
			for (String feed : new String[] {"markers.json", "queue-status.json", "assets/diorama-vehicles/scene.json"}) {
				Files.deleteIfExists(this.folder.resolve(feed));
			}
			WebPins.install(this.folder);
			if (!WebFrontendAssets.install(this.folder)) {
				for (String name : WebFogAssets.FILES) Files.write(this.folder.resolve(name), WebFogAssets.read(name));
				for (String asset : new String[] {"index.html", "overlay-api.js", "web-markers.js", "map-controls.js", "web-queue.js"}) {
					try (InputStream page = WebMap.class.getResourceAsStream("/diorama/web/" + asset)) {
						if (page != null) {
							Files.copy(page, this.folder.resolve(asset), StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Diorama's web map can't write to {}, so it doesn't start", this.folder, e);
			return false;
		}
		this.thread.start();
		Diorama.LOGGER.info("Diorama's web map is drawing into {}", this.folder);
		return true;
	}

	private void stop() {
		this.stopping = true;
		synchronized (this.wake) {
			this.wake.notifyAll();
		}
		try {
			this.thread.join(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		WebServer http = this.http;
		if (http != null) {
			http.stop();
		}
	}


	public int requestFull(boolean force) {
		return this.enqueue(new WebRenderTasks.Request(null, 0, 0, 0, force));
	}

	public void redrawAll() { this.requestFull(true); }

	public int requestArea(String dimension, int x, int z, int radius, boolean force) {
		return this.enqueue(new WebRenderTasks.Request(dimension, x, z, radius, force));
	}

	private int enqueue(WebRenderTasks.Request request) {
		int id = this.tasks.enqueue(request);
		synchronized (this.wake) { this.wake.notifyAll(); }
		return id;
	}

	public List<String> taskStatus() { return this.tasks.describe(this.paused); }

	public boolean cancelTask(int id) {
		boolean cancelled = this.tasks.cancel(id);
		synchronized (this.wake) { this.wake.notifyAll(); }
		return cancelled;
	}

	public void pause() {
		this.paused = true;
		this.status = "paused by operator";
	}

	public void resume() {
		this.paused = false;
		synchronized (this.wake) { this.wake.notifyAll(); }
	}

	public void cancel() {
		this.live.cancel();
		this.cancelRequested.set(true);
		this.paused = true;
		synchronized (this.wake) { this.wake.notifyAll(); }
	}

	private int queuedTiles() {
		return this.dimensions.values().stream().mapToInt(d -> d.queuedCount).sum();
	}

	private void awaitActive() { this.awaitActive(false); }

	private void awaitActive(boolean task) {
		while (this.paused && !this.stopping && !this.cancelRequested.get() && (!task || !this.tasks.cancelled())) {
			this.status = "paused by operator";
			try {
				if (!task && this.tasks.cancelled()) {
					var entry = this.tasks.begin();
					if (entry != null) this.finishTask(entry, "cancelled");
				}
				this.tasks.saveQueueChanges();
			} catch (IOException error) { Diorama.LOGGER.warn("Could not checkpoint web render tasks", error); }
			synchronized (this.wake) {
				try { this.wake.wait(1000); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
			}
		}
	}

	/** Detach manual work from the automatic queue so cancellation only affects the selected job. */
	private void handleTasks(TableLooks looks) {
		try {
			this.tasks.saveQueueChanges();
			if (System.nanoTime() < this.nextTaskAttempt && !this.tasks.cancelled()) return;
			WebRenderTasks.Entry entry = this.tasks.begin();
			if (entry == null) return;
			this.tasks.revalidatePlan(this.taskPlanInputs);
			if (this.tasks.remaining == null && !this.tasks.cancelled()) {
				Map<String, LinkedHashSet<Long>> plan = new LinkedHashMap<>();
				for (ServerLevel level : this.server.getAllLevels()) {
					if (this.taskStopping()) break;
					String id = level.dimension().identifier().toString();
					if (entry.request().dimension() != null && !entry.request().dimension().equals(id) || this.leftOut(level)) continue;
					Dimension dimension = this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
					var tiles = dimension.planRender(entry.request());
					if (!tiles.isEmpty()) plan.put(id, tiles);
				}
				if (this.stopping) return;
				this.tasks.remaining = plan;
				this.tasks.total = plan.values().stream().mapToLong(Set::size).sum();
				this.tasks.save();
			}
			if (this.tasks.cancelled()) {
				this.finishTask(entry, "cancelled");
				return;
			}
			var iterator = this.tasks.remaining.entrySet().iterator();
			while (iterator.hasNext() && !this.taskStopping()) {
				var region = iterator.next();
				Dimension dimension = this.dimensions.get(region.getKey());
				if (dimension == null) {
					this.finishTask(entry, "failed: dimension is no longer published");
					return;
				}
				var tiles = region.getValue();
				if (!tiles.isEmpty()) {
					int before = tiles.size();
					long started = System.nanoTime();
					boolean published = dimension.renderQueued(looks, 16, tiles);
					int done = before - tiles.size();
					this.tasks.completed += done;
					if (done > 0) {
						this.tasks.measuredTiles += done;
						this.tasks.batchTimeNanos += System.nanoTime() - started;
						this.tasks.progress(WebRenderTasks.progress("tiles", this.tasks.completed,
							this.tasks.total, this.tasks.measuredTiles, this.tasks.batchTimeNanos));
					}
					if (!published && !this.taskStopping()) {
						this.tasks.progress("waiting to retry a tile write");
						this.nextTaskAttempt = System.nanoTime() + RETRY_GAP_MS * 1_000_000;
					}
					this.tasks.save();
				}

				if (!tiles.isEmpty()) break;
				iterator.remove();
			}
			if (this.taskBakeFailed) {
				this.taskBakeFailed = false;
				this.finishTask(entry, "failed: a tile could not be baked");
			} else if (this.tasks.cancelled() || this.cancelRequested.get()) this.finishTask(entry, "cancelled");
			else if (this.tasks.remaining.isEmpty()) this.finishTask(entry, "complete");
		} catch (IOException error) {
			this.nextTaskAttempt = System.nanoTime() + RETRY_GAP_MS * 1_000_000;
			this.tasks.progress("waiting to retry a task checkpoint");
			Diorama.LOGGER.warn("Could not checkpoint web render tasks", error);
		}
	}

	private boolean taskStopping() {
		return this.stopping || this.cancelRequested.get() || this.tasks.cancelled() || this.taskBakeFailed;
	}

	private void finishTask(WebRenderTasks.Entry entry, String result) throws IOException {
		this.tasks.finish(result);
		this.nextTaskAttempt = 0;
		this.tasks.save();
		Diorama.LOGGER.info("Web render task #{} {} ({})", entry.id(), result, entry.request().description());
	}

	/** How many passes over every dimension have finished since the server started. */
	public int passes() {
		return this.passes;
	}

	/** What the map is doing, for the status command. */
	public String status() {
		return this.status + "; queued=" + this.queuedTiles()
			+ ", tick=" + String.format(Locale.ROOT, "%.1f", this.averageTickMs())
			+ " ms, saving=" + this.server.isCurrentlySaving()
			+ (this.paused ? ", paused=true" : "")
			+ (this.automaticProgress.isEmpty() ? "" : "; " + this.automaticProgress);
	}

	/**
	 * Whether the server's settings keep a dimension off the web map: one with the map off
	 * ({@code map-off-dimensions}), or one under a roof, as the Nether, while cave maps aren't allowed
	 * or are fog of war. There the map in the game shows only what each player has seen, and a page
	 * for everyone can't know what one player has seen.
	 */
	private boolean leftOut(ServerLevel level) {
		ServerLocks locks = ShareServer.locks(this.server);
		return locks.mapOffDimensions().contains(level.dimension().identifier().toString())
			|| level.dimensionType().hasCeiling() && (!locks.caveMapsAllowed() || locks.caveFogOfWar());
	}

	/**
	 * Takes a dimension off the web map: its pictures, and when it was last drawn, so it's drawn
	 * whole if the settings allow it again. Only the map's own pictures are deleted, in the map's own
	 * folders (none reached through a link). Whatever can't be deleted, the map's web server no
	 * longer serves, and it's tried again at the next pass.
	 */
	private void remove(String id, Properties progress) {
		this.dimensions.remove(id);
		if (progress.remove(id) != null) {
			this.saveProgress(progress);
		}
		String name = folderName(id);
		Path dimension = this.folder.resolve(name);
		// An ID from the settings file that isn't one folder's name inside the map's folder names nothing of the map's.
		if (!this.folder.equals(dimension.getParent()) || !name.equals(String.valueOf(dimension.getFileName()))) {
			return;
		}
		this.closed.add(name.toLowerCase(Locale.ROOT));
		if (!Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		int deleted = 0;
		int kept = 0;
		for (int zoom = 0; zoom <= MAX_ZOOM; zoom++) {
			Path level = dimension.resolve(Integer.toString(zoom));
			if (!Files.isDirectory(level, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(level)) {
				for (Path file : files) {
					if (TILE_NAME.matcher(file.getFileName().toString()).matches()) {
						try {
							Files.delete(file);
							deleted++;
						} catch (IOException e) {
							kept++;
						}
					}
				}
				deleteIfEmpty(level);
			} catch (IOException e) {
				kept++;
			}
		}
		try {
			deleteIfEmpty(dimension);
		} catch (IOException e) {
			kept++;
		}
		if (deleted > 0) {
			Diorama.LOGGER.info("Web map: deleted the {} pictures of {}, which this server's settings keep off it", deleted, id);
		}
		if (kept > 0 && this.warnedKept.add(name)) {
			Diorama.LOGGER.warn("Web map: couldn't delete {} of the pictures or folders of {} in {}, which this server's settings keep off the map. Diorama's own web server "
				+ "doesn't serve them; a web server of your own still would, so delete that folder yourself", kept, id, dimension);
		}
	}

	/** Deletes a folder with nothing in it; one with someone else's files stays. */
	private static void deleteIfEmpty(Path folder) throws IOException {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
			if (files.iterator().hasNext()) {
				return;
			}
		}
		Files.delete(folder);
	}

	/** The folder of a dimension's pictures, in the web map's folder. */
	private static String folderName(String id) {
		return id.replace(':', '_').replace('/', '_');
	}

	private void run() {
		try {
			if (!this.settings.vehicles()) {
				try {
					Path feed = this.folder.resolve("assets/diorama-vehicles/scene.json");
					Files.createDirectories(feed.getParent());
					dev.diorama.web.vehicles.VehicleSprites.atomic(feed,
						("{\"generatedAt\":" + System.currentTimeMillis() + ",\"intervalMs\":100,\"format\":\"diorama-craft-sprites-v1\",\"objects\":[]}")
							.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				} catch (IOException error) { Diorama.LOGGER.warn("Couldn't clear the disabled vehicle feed", error); }
			}
			Properties progress = this.loadProgress();
			// Before anything is served: what the settings keep off the map is deleted, and the map
			// file lists what's left as it is on disk.
			List<String> out = new ArrayList<>();
			for (ServerLevel level : this.server.getAllLevels()) {
				String id = level.dimension().identifier().toString();
				if (this.leftOut(level)) {
					out.add(id);
					this.remove(id, progress);
				} else {
					this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
				}
			}
			// A dimension turned off that isn't in this world now (its mod or data pack taken out) may still have pictures.
			for (String id : ShareServer.locks(this.server).mapOffDimensions()) {
				if (!out.contains(id)) {
					this.remove(id, progress);
				}
			}
			if (!out.isEmpty()) {
				Diorama.LOGGER.info("Web map: not drawing {}, which this server's settings keep off the map (map-off-dimensions, or cave maps locked for a dimension under a roof)", String.join(", ", out));
			}
			this.writeMapJson();
			if (this.settings.port() > 0 && !this.stopping) {
				this.http = WebServer.start(this.folder, this.settings.address(), this.settings.port(), this.closed::contains);
				if (this.stopping) {
					// The server stopped while the pictures were being deleted, and has looked for this already.
					WebServer late = this.http;
					if (late != null) {
						late.stop();
					}
					return;
				}
				if (this.http != null) {
					Diorama.LOGGER.info("Diorama's web map is served at http://{}:{}/", this.settings.address().isEmpty() ? "<this server>" : this.settings.address(), this.settings.port());
				}
			}
			this.colourMaps();
			JarModels jars = JarModels.of(this.modsFolder);
			TableLooks looks = new TableLooks(this.table, jars);
			Diorama.LOGGER.info("Web map colours: vanilla table of {}; block models from {} mod jars", this.table.describe(), jars.jarCount());
			String appearance = WebAppearance.fingerprint(this.table);
			this.taskPlanInputs = appearance + "/" + this.settings.everyChunk() + "/" + this.settings.depth();
			String previousAppearance = progress.getProperty("appearance");
			if (previousAppearance != null ? !previousAppearance.equals(appearance) : this.table != VanillaTable.shipped()) {
				this.redrawAll.set(true);
				Diorama.LOGGER.info("Web appearance changed: refreshing terrain to match the client profile");
			} else if (previousAppearance == null) progress.setProperty("appearance", appearance);
			// Drawn under another share-land: land the map shows now may be missing, or land it
			// no longer shows still drawn. Everything again.
			String land = this.settings.everyChunk() ? "generated" : "visited";
			if (!land.equals(progress.getProperty("share-land", land))) {
				Diorama.LOGGER.info("Web-map land changed to {}: drawing the whole web map again", land);
				this.redrawAll.set(true);
			} else {
				progress.setProperty("share-land", land);
			}
			String refreshTarget = this.redrawAll.get() ? appearance + "/" + land : null;
			// A stale progress file may claim a dimension was rendered even if the
			// base tile folder is empty (e.g. an older interrupted first pass).
			// Recover once per dimension on startup without deleting any stored data.
			Set<String> checkedEmptyProgress = new HashSet<>();
			long nextSavedScan = 0;

			while (!this.stopping) {
				this.awaitActive();
				if (this.cancelRequested.getAndSet(false)) {
					for (Dimension dimension : this.dimensions.values()) dimension.cancel(progress);
					this.tasks.cancelAll();
					this.handleTasks(looks);
					this.redrawAll.set(false);
					refreshTarget = null;
					progress.keySet().removeIf(key -> key.toString().startsWith("refresh."));
					this.redrawOwed = false;
					this.saveProgress(progress);
					this.automaticProgress = "";
					this.status = "cancelled; existing JPEGs kept, use /diorama-web start to resume";
					continue;
				}
				this.handleTasks(looks);
				for(var key:this.tracks.update(looks)){
					Dimension dimension=this.dimensions.get(key.dimension());if(dimension==null)continue;
					long tile=WebTileQueue.key(key.tu(),key.tv());if(dimension.tiles[0].contains(tile))dimension.pending.add(tile);
				}
				this.handleLiveUpdates(looks);
				boolean coverageWork=false;
				for(Dimension dimension:this.dimensions.values()){
					dimension.repairCoverage();coverageWork|=dimension.coverageRepair.pending();
				}
				if (System.currentTimeMillis() < nextSavedScan && !this.redrawAll.get() && !this.redrawOwed) {
					boolean pending = coverageWork || this.tasks.hasWork();
					for (Dimension dimension : this.dimensions.values()) {
						if (dimension.pending.size() == 0) continue;
						pending = true;
						dimension.renderQueued(looks, 32);
					}
					if (pending) {
						// Continue bounded batches without another region scan; avoid spinning on disk errors.
						sleep(1000);
					} else this.waitForChanges(progress, nextSavedScan);
					continue;
				}
				this.redrawAll.set(false);
				String refresh = refreshTarget;
				this.redrawOwed = false;
				long passStarted = System.currentTimeMillis();
				nextSavedScan = passStarted + PASS_GAP_MS;
				boolean complete = true;
				String interruptedAt = null;
				for (ServerLevel level : this.server.getAllLevels()) {
					if (this.stopping) {
						break;
					}
					String id = level.dimension().identifier().toString();
					if (this.leftOut(level)) {
						// A dimension a mod added since, or pictures that couldn't be deleted before.
						this.remove(id, progress);
						continue;
					}
					Dimension dimension = this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
					if (refresh != null && refresh.equals(progress.getProperty("refresh.done." + id))) continue;
					dimension.repairCoverage();
					long last = Long.parseLong(progress.getProperty(id, "-1"));
					if (last >= 0 && dimension.tiles[0].isEmpty() && checkedEmptyProgress.add(id)) {
						Diorama.LOGGER.warn("Web map {}: progress file says rendering completed, but no base JPEG tiles exist; rescanning saved chunks from scratch", id);
						last = -1;
					}
					long started = dimension.pass(looks, refresh != null ? -1 : last, progress, refresh);
					if (started >= 0) {
						progress.setProperty(id, Long.toString(started));
						if (refresh != null) progress.setProperty("refresh.done." + id, refresh);
						this.saveProgress(progress);
					} else if (started == PASS_UNCHANGED) {
						this.saveProgress(progress);
					} else if (started == PASS_INCOMPLETE) {
						// Never mark interrupted incremental passes as complete. Their in-memory
						// chunk hashes may already have advanced although tiles weren't written.
						complete = false;
						if (interruptedAt == null) {
							interruptedAt = this.status;
						}
					}
				}
				if (refresh != null && complete && !this.stopping) {
					refreshTarget = null;
					progress.keySet().removeIf(key -> key.toString().startsWith("refresh."));
					progress.setProperty("share-land", land);
					progress.setProperty("appearance", appearance);
					this.saveProgress(progress);
				} else if (!complete && !this.stopping) {
					this.redrawOwed = true;
				}
				this.writeMapJson();
				if (!complete && !this.stopping) {
					if (this.cancelRequested.get()) {
						this.status = "cancelling pending web-map tasks";
						continue;
					}
					String cause = interruptedAt == null ? "an incomplete dimension pass" : interruptedAt;
					if (this.redrawAll.get()) {
						this.status = "restarting incomplete pass: another redraw was requested";
					} else {
						this.status = "incomplete: " + cause + "; retrying in " + RETRY_GAP_MS / 1000 + " s";
						Diorama.LOGGER.warn("Web map pass incomplete ({}). Retrying the scan in {} s", cause, RETRY_GAP_MS / 1000);
						this.waitForRetry();
					}
					continue;
				}
				this.automaticProgress = "";
				this.passes++;
				int rendered = this.dimensions.values().stream().mapToInt(d -> d.tiles[0].size()).sum();
				this.status = "up to date: " + rendered + " base tiles across " + this.dimensions.size()
					+ " dimensions; checks saved chunks at most every " + PASS_GAP_MS / 60_000 + " minutes";
				this.waitForChanges(progress, passStarted + PASS_GAP_MS);
			}
			jars.close();
		} catch (RuntimeException | Error e) {
			this.status = "stopped by an error: " + e;
			Diorama.LOGGER.error("Diorama's web map stopped", e);
		} finally {
			try { this.tasks.saveQueueChanges(); }
			catch (IOException error) { Diorama.LOGGER.warn("Could not checkpoint web render tasks", error); }
		}
	}

	/** Retry incomplete passes promptly, but avoid tight retry loops on damaged or busy saves. */
	private void waitForRetry() {
		synchronized (this.wake) {
			if (!this.stopping && !this.redrawAll.get()) {
				try {
					this.wake.wait(RETRY_GAP_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	/**
	 * Waits until {@code notBefore} at least, then until a region file has a chunk saved since its
	 * dimension's last pass; or until a redraw is asked for.
	 */
	private void waitForChanges(Properties progress, long notBefore) {
		while (!this.stopping && !this.redrawAll.get()) {
			if(this.tracks.hasUpdates())return;
			long wait = Math.max(CHECK_MS, notBefore - System.currentTimeMillis());
			synchronized (this.wake) {
				try {
					this.wake.wait(wait);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (this.stopping || this.redrawAll.get() || this.tasks.hasWork() || this.live.hasUpdates() || this.tracks.hasUpdates()) return;
			if (System.currentTimeMillis() < notBefore) {
				continue;
			}
			if (this.redrawOwed) {
				return;
			}
			for (Dimension dimension : this.dimensions.values()) {
				if (dimension.savedSince(Long.parseLong(progress.getProperty(dimension.id, "-1")))) {
					return;
				}
			}
		}
	}

	/**
	 * A dedicated server never loads the grass and foliage colour maps (they're textures); without
	 * them every biome's grass is black. Fills them from the table, which keeps them.
	 */
	private void colourMaps() {
		if (GrassColor.get(0.5, 1.0) == 0) {
			int[] grass = this.table.colourMap(VanillaTable.GRASS);
			if (grass != null) {
				GrassColor.init(grass);
			}
		}
		if (FoliageColor.get(0.5, 1.0) == 0) {
			int[] foliage = this.table.colourMap(VanillaTable.FOLIAGE);
			if (foliage != null) {
				FoliageColor.init(foliage);
			}
		}
		//? if >=1.21.5 {
		if (net.minecraft.world.level.DryFoliageColor.get(0.5, 1.0) == 0) {
			int[] dry = this.table.colourMap(VanillaTable.DRY_FOLIAGE);
			if (dry != null) {
				net.minecraft.world.level.DryFoliageColor.init(dry);
			}
		}
		//?}
	}

	boolean serverBehind() {
		//? if >=1.21 {
		return this.server.getAverageTickTimeNanos() > SERVER_BEHIND_NANOS;
		//?} else {
		/*return this.server.getAverageTickTime() * 1_000_000 > SERVER_BEHIND_NANOS;
		*///?}
	}

	double averageTickMs() {
		//? if >=1.21 {
		return this.server.getAverageTickTimeNanos() / 1e6;
		//?} else {
		/*return this.server.getAverageTickTime();
		*///?}
	}

	boolean stopping() {
		return this.stopping || this.redrawAll.get() || this.cancelRequested.get();
	}

	/** Whether the server is shutting down (not a redraw asked for). */
	boolean shuttingDown() {
		return this.stopping;
	}

	void setStatus(String status) {
		this.status = status;
	}

	private Properties loadProgress() {
		Properties progress = new Properties();
		Path file = this.folder.resolve("progress.properties");
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				progress.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				Diorama.LOGGER.warn("Couldn't read the web map's progress in {}; drawing everything again", file, e);
				progress.clear();
			}
		}
		return progress;
	}

	private boolean saveProgress(Properties progress) {
		Path file = this.folder.resolve("progress.properties");
		Path part = this.folder.resolve("progress.properties.part");
		try (Writer writer = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
			progress.store(writer, "When each dimension's last web map pass started, in the region files' seconds");
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
			return false;
		}
		try {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
			return false;
		}
	}

	private Map<String,WebQueueStatus.Frame> queuedStatus() {
		Map<String,WebQueueStatus.Frame> result=new HashMap<>();
		for(Dimension dimension:this.dimensions.values()){
			Set<Long> waiting=this.live.waiting(dimension.id),cells=new java.util.LinkedHashSet<>();
			for(long chunk:waiting){
				Drawn drawn=dimension.drawn.get(chunk);
				dimension.markTiles((int)(chunk>>32),(int)chunk,drawn==null?dimension.maximumY:drawn.top(),drawn==null?dimension.minimumY:drawn.floor(),cells);
				if(cells.size()>=WebQueueStatus.LIMIT)break;
			}
			for(long tile:dimension.pending.first(WebQueueStatus.LIMIT)){cells.add(tile);if(cells.size()>=WebQueueStatus.LIMIT)break;}
			result.put(dimension.id,new WebQueueStatus.Frame(dimension.pending.size(),waiting.size(),cells.size()>=WebQueueStatus.LIMIT,List.copyOf(cells)));
		}return result;
	}

	/** What the page reads first: the projection, and each dimension's tiles at each zoom. */
	boolean writeMapJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"tileUnits\":").append(Projection.TILE_UNITS)
			.append(",\"tilePixels\":").append(Projection.TILE_PIXELS)
			.append(",\"sin\":").append(String.format(Locale.ROOT, "%.6f", Projection.sin()))
			.append(",\"cos\":").append(String.format(Locale.ROOT, "%.6f", Projection.cos()))
			.append(",\"facing\":").append(Projection.facing().turns)
			.append(",\"land\":\"").append(this.settings.everyChunk() ? "generated" : "visited").append('"')
			.append(",\"depth\":").append(this.settings.depth())
			.append(",\"vehicleFeed\":\"assets/diorama-vehicles/scene.json\"")
			.append(",\"markerFeed\":\"markers.json\",\"queueFeed\":\"queue-status.json\"")
			.append(",\"fogCoverage\":true,\"roundedFog\":").append(dev.diorama.MapStyle.get().roundedFog())
			.append(",\"background\":\"#").append(String.format(Locale.ROOT, "%06x", WebTiles.BACKGROUND)).append('"')
			.append(",\"updated\":").append(System.currentTimeMillis())
			.append(",\"dimensions\":[");
		List<Dimension> list = new ArrayList<>(this.dimensions.values());
		list.sort(Comparator.comparing(d -> d.order() + d.id));
		boolean first = true;
		for (Dimension dimension : list) {
			if (!first) {
				json.append(',');
			}
			first = false;
			dimension.json(json);
		}
		json.append("]}");
		Path file = this.folder.resolve("map.json");
		Path part = this.folder.resolve("map.json.part");
		try {
			Files.writeString(part, json, StandardCharsets.UTF_8);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			Path revision = this.folder.resolve("updates.json.part");
			Files.writeString(revision, "{\"updated\":" + Files.getLastModifiedTime(file).toMillis() + "}");
			Files.move(revision, this.folder.resolve("updates.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't write the web map's map.json", e);
			return false;
		}
	}

	/** One dimension's web map: its save, which chunks count, and the tiles on disk. */
	static final class Dimension {
		private final WebMap map;
		private final ServerLevel level;
		final String id;
		private final Path folder;
		private final ServerSave save;
		private final SavedChunkSource source;
		private final WebTileQueue pending;
		private final WebChunkState chunkState;
		private final WebCoverageRepair coverageRepair;
		private volatile int queuedCount;
		/** Per region, per chunk: whether it's been looked at, and whether the map shows it. */
		private final Map<Long, long[]> shown = new HashMap<>();
		/**
		 * Each drawn chunk's blocks and height as last drawn since the server started, by
		 * {@link ChunkSnapshot#key}: a chunk saved with the same blocks needs no redraw, and one that
		 * got lower still redraws the tiles it used to reach.
		 */
		private final Set<Long> lossless = new HashSet<>();
		private final Map<Long, Drawn> drawn = new ConcurrentHashMap<>();
		private final int minimumY,maximumY;
		private final boolean ceiling;
		/** Tiles on disk, per zoom level, as {@link #key}. */
		@SuppressWarnings("unchecked")
		private final Set<Long>[] tiles = new Set[MAX_ZOOM + 1];
		private final List<Map<Long, Long>> versions = new ArrayList<>(MAX_ZOOM + 1);
		private final Map<Long, Long> baseWritten = new HashMap<>();
		private int zoomLevels;

		Dimension(WebMap map, ServerLevel level, String id) {
			this.map = map;
			this.level = level;
			this.id = id;
			this.folder = map.folder.resolve(folderName(id));
			this.pending = new WebTileQueue(this.folder);
			this.chunkState = new WebChunkState(this.folder.resolve("chunk-state"));
			this.coverageRepair = new WebCoverageRepair(this.folder);
			this.minimumY=level.getMinY();this.maximumY=level.getMaxY();this.ceiling=level.dimensionType().hasCeiling();
			this.queuedCount = this.pending.size();
			this.save = ServerSave.of(level);
			int roof = level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() : ChunkSource.NO_ROOF;
			int minY = level.getMinY();
			int maxY = level.getMaxY() + 1;
			ChunkSource bounds = new ChunkSource() {
				@Override
				public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
					return map.live.chunk(id, chunkX, chunkZ);
				}

				@Override
				public int minY() {
					return minY;
				}

				@Override
				public int maxY() {
					return maxY;
				}

				@Override
				public int topY() {
					return maxY;
				}

				@Override
				public int roof() {
					return roof;
				}

				@Override
				public boolean isSeen(int x, int y, int z) {
					return true;
				}
			};
			this.source = new SavedChunkSource(bounds, this.save, BLEND_RADIUS);
			boolean everyChunk = map.settings.everyChunk();
			this.source.allow((chunkX, chunkZ, top) -> everyChunk || this.shows(chunkX, chunkZ));
			for (int z = 0; z <= MAX_ZOOM; z++) {
				this.tiles[z] = new HashSet<>();
				this.versions.add(new HashMap<>());
				Path zoom = this.folder.resolve(Integer.toString(z));
				if (!Files.isDirectory(zoom)) {
					continue;
				}
				try (DirectoryStream<Path> files = Files.newDirectoryStream(zoom, "*.jpg")) {
					for (Path file : files) {
						String name = file.getFileName().toString();
						int sep = name.indexOf('_');
						try {
							if (sep > 0) {
								int u=Integer.parseInt(name.substring(0,sep)),v=Integer.parseInt(name.substring(sep+1,name.length()-4));
								this.tiles[z].add(key(u,v));
								this.updateVersion(z, key(u, v));
								if(z==0&&Files.isRegularFile(WebTiles.lossless(file)))this.lossless.add(key(u,v));
								if(!level.dimensionType().hasCeiling())this.coverageRepair.add(z,u,v);
							}
						} catch (NumberFormatException e) {
							// Not one of the map's tiles: someone else's file.
						}
					}
				} catch (IOException e) {
					Diorama.LOGGER.warn("Couldn't list the web map's tiles in {}", zoom, e);
				}
				if (!this.tiles[z].isEmpty()) {
					this.zoomLevels = z + 1;
				}
			}
		}

		private void repairCoverage() {
			if(!this.coverageRepair.pending()||this.map.serverBehind()||this.map.server.isCurrentlySaving())return;
			try {
				boolean changed=this.coverageRepair.step(32, this.pending::add, this::updateVersion);
				this.queuedCount=this.pending.size();this.pending.save();
				if(changed)this.map.writeMapJson();
			}catch(IOException error){Diorama.LOGGER.warn("Couldn't repair web-map coverage for {}",this.id,error);}
		}


		/** Overworld first, then the Nether, the End, and modded ones. */
		String order() {
			return this.id.equals("minecraft:overworld") ? "0" : this.id.equals("minecraft:the_nether") ? "1" : this.id.equals("minecraft:the_end") ? "2" : "3";
		}

		static long key(int u, int v) {
			return ((long) u << 32) | (v & 0xFFFFFFFFL);
		}

		/** Whether players spent time near the chunk, as its save says; looked up once, then kept. */
		private boolean shows(int chunkX, int chunkZ) {
			long[] bits = this.shown.computeIfAbsent(ServerSave.regionKey(chunkX >> 5, chunkZ >> 5), k -> new long[32]);
			int i = ((chunkZ & 31) << 5) | (chunkX & 31);
			long looked = 1L << (i & 31) << 32;
			long yes = 1L << (i & 31);
			if ((bits[i >> 5] & looked) == 0) {
				ServerSave.Info info = this.save.info(chunkX, chunkZ);
				boolean visited = info != null && info.summary().finished() && info.inhabited() > 0;
				bits[i >> 5] |= looked | (visited ? yes : 0);
			}
			return (bits[i >> 5] & yes) != 0;
		}

		private void forget(int chunkX, int chunkZ) {
			long[] bits = this.shown.get(ServerSave.regionKey(chunkX >> 5, chunkZ >> 5));
			if (bits != null) {
				int i = ((chunkZ & 31) << 5) | (chunkX & 31);
				bits[i >> 5] &= ~((1L << (i & 31) << 32) | (1L << (i & 31)));
			}
		}

		/** Whether any chunk was saved at or after {@code since} (region file seconds). */
		boolean savedSince(long since) {
			Map<Long, int[]> saved = this.save.scan();
			try {
				for (long region : this.chunkState.regions()) {
					if (!saved.containsKey(region) && this.save.regionRemoved(region)) return true;
				}
			} catch (IOException error) {
				Diorama.LOGGER.warn("Could not check for removed regions in {}", this.id, error);
			}
			for (int[] times : saved.values()) {
				for (int time : times) {
					if (time != 0 && time >= since) {
						return true;
					}
				}
			}
			return false;
		}

		private Map<Long, int[]> scanRegions() throws IOException {
			Map<Long, int[]> regions = this.save.scan();
			for (long region : this.chunkState.regions()) {
				// An unreadable header does not establish that a region was removed.
				if (!regions.containsKey(region) && this.save.regionRemoved(region)) {
					regions.put(region, new int[WebChunkState.CHUNKS]);
				}
			}
			return regions;
		}

		/**
		 * Progressive region discovery. Every batch durably queues affected base tiles
		 * before checkpointing its scan cursor, then renders a few tiles immediately.
		 * This makes the first JPEG available while much of the world remains unscanned.
		 * On restart, queued tiles and the cursor resume without reprocessing the whole world.
		 */
		long pass(TableLooks looks, long last, Properties progress, @Nullable String refresh) {
			long started = System.currentTimeMillis() / 1000;
			long wallStart = System.currentTimeMillis();
			int failedReads = this.save.failedReads();
			String prefix = "scan." + this.id + ".";
			this.map.setStatus("scanning region headers for " + this.id);
			Map<Long, int[]> regions;
			try { regions = this.scanRegions(); }
			catch (IOException error) {
				Diorama.LOGGER.warn("Could not list chunk fingerprints for {}", this.id, error);
				return PASS_INCOMPLETE;
			}
			List<Map.Entry<Long, int[]>> ordered = new ArrayList<>(regions.entrySet());
			// Start near spawn instead of the signed-coordinate corner of the world.
			ordered.sort(Comparator.comparingLong((Map.Entry<Long, int[]> e) -> {
				long x = (int) (e.getKey() >> 32);
				long z = (int) (long) e.getKey();
				return x * x + z * z;
			}).thenComparingLong(Map.Entry::getKey));
			this.source.clear();
			// Resume only if the region-file identity and ordering are unchanged.
			String refreshKey = "refresh.seed." + this.id;
			boolean seedRefresh = refresh != null && !refresh.equals(progress.getProperty(refreshKey));
			long regionFingerprint = 1;
			for (Map.Entry<Long, int[]> region : ordered) regionFingerprint = 31 * regionFingerprint + region.getKey();
			int cursor = 0;
			try {
				int savedCount = Integer.parseInt(progress.getProperty(prefix + "count", "-1"));
				long savedLast = Long.parseLong(progress.getProperty(prefix + "since", "0"));
				long savedFingerprint = Long.parseLong(progress.getProperty(prefix + "regions", "0"));
				if (savedCount == ordered.size() && savedLast == last
						&& savedFingerprint == regionFingerprint && !seedRefresh) {
					cursor = Math.max(0, Math.min(ordered.size(), Integer.parseInt(progress.getProperty(prefix + "cursor", "0"))));
					started = Long.parseLong(progress.getProperty(prefix + "started", Long.toString(started)));
				}
			} catch (NumberFormatException e) {
				Diorama.LOGGER.warn("Web map {}: invalid scan checkpoint; restarting this scan", this.id);
			}
			this.map.setStatus("scanning " + this.id + ": " + cursor + "/" + ordered.size() + " regions, "
				+ this.pending.size() + " tiles queued");
			Diorama.LOGGER.info("Web map {}: progressive {} scan, {} region files (resuming at {}), {} pending tiles, tick {} ms",
				this.id, last < 0 ? "full" : "incremental", ordered.size(), cursor, this.pending.size(),
				String.format(Locale.ROOT, "%.1f", this.map.averageTickMs()));

			this.map.automaticProgress = this.id + " scan " + WebRenderTasks.progress("regions", cursor, ordered.size(), 0, 0);
			int changed = 0;
			int counted = 0;
			int batches = 0;
			long scanStart = System.nanoTime();
			long lastLog = System.currentTimeMillis();
			Set<Long> dirty = new HashSet<>();
			Set<Long> changedTiles = new HashSet<>();
			Map<Long, Drawn[]> changedRegions = new LinkedHashMap<>();
			if (seedRefresh) {
				// Save the work before marking it seeded, so a reboot resumes this refresh.
				progress.remove(prefix + "cursor");
				this.pending.addAll(this.tiles[0]);
				this.queuedCount = this.pending.size();
				if (!this.pending.save()) return PASS_INCOMPLETE;
				progress.setProperty(refreshKey, refresh);
				if (!this.map.saveProgress(progress)) {
					progress.remove(refreshKey);
					return PASS_INCOMPLETE;
				}
			}
			for (int index = cursor; index < ordered.size(); index++) {
				this.map.awaitActive();
				if (this.map.tasks.hasWork()) this.map.handleTasks(looks);
				this.map.handleLiveUpdates(looks);
				if (this.map.stopping()) {
					this.map.setStatus("interrupted " + this.id + " scan after " + index + "/" + ordered.size()
						+ " regions: " + (this.map.shuttingDown() ? "server stopped" : "redraw requested"));
					return PASS_INCOMPLETE;
				}
				Map.Entry<Long, int[]> region = ordered.get(index);
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				int[] times = region.getValue();
				Drawn[] previous;
				try { previous = this.chunkState.read(region.getKey()); }
				catch (IOException error) {
					Diorama.LOGGER.warn("Could not read chunk fingerprints for {}", this.id, error);
					return PASS_INCOMPLETE;
				}
				boolean stateChanged = false;
				boolean any = false;
				for (int i = 0; i < 1024; i++) {
					Drawn before = previous[i];
					if (times[i] == 0 && before == null || times[i] != 0 && last >= 0 && times[i] < last) continue;
					any = true;
					int chunkX = regionX * 32 + (i & 31);
					int chunkZ = regionZ * 32 + (i >> 5);
					this.map.live.saved(this.id, chunkX, chunkZ, times[i]);
					this.forget(chunkX, chunkZ);
					changed++;
					long key = ChunkSnapshot.key(chunkX, chunkZ);
					int readsBefore = this.save.failedReads();
					ServerSave.Info info = times[i] == 0 ? null : this.save.info(chunkX, chunkZ);
					if (this.save.failedReads() != readsBefore) {
						this.map.setStatus("incomplete " + this.id + ": unreadable saved chunks");
						return PASS_INCOMPLETE;
					}
					if (info == null || !info.summary().finished()
							|| !this.map.settings.everyChunk() && info.inhabited() <= 0) {
						if (before != null) {
							this.markTiles(chunkX, chunkZ, before.top(), before.floor(), changedTiles);
							previous[i] = null;
							stateChanged = true;
						}
						this.drawn.remove(key);
						continue;
					}
					ChunkTags.Summary summary = info.summary();
					Drawn current = new Drawn(info.blocks(), summary.maxY(), summary.floorY());
					this.drawn.put(key, current);
					if (!current.equals(before)) {
						stateChanged = true;
						previous[i] = current;
						counted++;
					}
					if (last < 0 || before == null) {
						this.markTiles(chunkX, chunkZ, current.top(), current.floor(), dirty);
					}
					if (before != null && !current.equals(before)) {
						this.markTiles(chunkX, chunkZ, Math.max(before.top(), current.top()),
							Math.min(before.floor(), current.floor()), changedTiles);
					} else if (before == null) {
						this.markNewerTiles(chunkX, chunkZ, current, times[i], changedTiles);
					}
				}
				if (stateChanged) changedRegions.put(region.getKey(), previous);
				if (any) this.source.forgetTops(region.getKey());

				// No more than eight region files or 15 seconds before the first render batch.
				if ((index + 1) % 8 == 0 || System.currentTimeMillis() - wallStart > 15_000 && index == cursor
						|| index + 1 == ordered.size()) {
					this.pending.addAll(changedTiles);
					for (long tile : dirty) {
						if (!this.tiles[0].contains(tile)) this.pending.add(tile);
					}
					dirty.clear();
					changedTiles.clear();
					this.queuedCount = this.pending.size();
					if (!this.pending.save()) {
						this.map.setStatus("incomplete " + this.id + ": couldn't persist pending tile queue");
						return PASS_INCOMPLETE;
					}
					// Only commit discovered inputs after their render work is durable.
					try {
						for (var entry : changedRegions.entrySet()) this.chunkState.write(entry.getKey(), entry.getValue());
					} catch (IOException error) {
						Diorama.LOGGER.warn("Could not checkpoint chunk fingerprints for {}", this.id, error);
						return PASS_INCOMPLETE;
					}
					changedRegions.clear();

					progress.setProperty(prefix + "cursor", Integer.toString(index + 1));
					progress.setProperty(prefix + "count", Integer.toString(ordered.size()));
					progress.setProperty(prefix + "regions", Long.toString(regionFingerprint));
					progress.setProperty(prefix + "since", Long.toString(last));
					progress.setProperty(prefix + "started", Long.toString(started));
					if (!this.map.saveProgress(progress)) return PASS_INCOMPLETE;
					// Give rendering a share of CPU now; do not wait for discovery to finish.
					if (!this.renderQueued(looks, 8)) return PASS_INCOMPLETE;
					batches++;
				}
				long now = System.currentTimeMillis();
				if ((index + 1) % 8 == 0 || index + 1 == ordered.size()) {
					this.map.automaticProgress = this.id + " scan " + WebRenderTasks.progress("regions",
						index + 1, ordered.size(), index + 1 - cursor, System.nanoTime() - scanStart);
				}
				this.map.setStatus("scanning " + this.id + ": region " + (index + 1) + "/" + ordered.size()
					+ ", " + this.pending.size() + " pending tiles; tick "
					+ String.format(Locale.ROOT, "%.1f", this.map.averageTickMs()) + " ms");
				if (now - lastLog > PROGRESS_LOG_MS) {
					Diorama.LOGGER.info("Web map {}: scanned {}/{} regions, {} chunks examined, {} map changes, {} pending tiles, tick {} ms, saving={}",
						this.id, index + 1, ordered.size(), changed, counted, this.pending.size(),
						String.format(Locale.ROOT, "%.1f", this.map.averageTickMs()), this.map.server.isCurrentlySaving());
					lastLog = now;
				}
			}
			Diorama.LOGGER.info("Web map {}: region scan complete; {} chunks examined, {} changed, {} queued tiles, {} batches, {} s",
				this.id, changed, counted, this.pending.size(), batches,
				(System.currentTimeMillis() - wallStart) / 1000);
			int tileTotal = this.pending.size();
			long tileStart = System.nanoTime();
			while (!this.pending.isEmpty() && !this.map.stopping()) {
				if (this.map.tasks.hasWork()) this.map.handleTasks(looks);
				int done = tileTotal - this.pending.size();
				this.map.automaticProgress = this.id + " render " + WebRenderTasks.progress("tiles",
					done, tileTotal, done, System.nanoTime() - tileStart);
				if (!this.renderQueued(looks, 32)) return PASS_INCOMPLETE;
			}
			if (this.map.stopping()) return PASS_INCOMPLETE;
			this.source.clear();
			if (this.save.failedReads() != failedReads) {
				this.drawn.clear();
				this.map.setStatus("incomplete " + this.id + ": unreadable saved chunks");
				return PASS_INCOMPLETE;
			}
			progress.remove(prefix + "cursor");
			progress.remove(prefix + "count");
			progress.remove(prefix + "regions");
			progress.remove(prefix + "since");
			progress.remove(prefix + "started");
			Diorama.LOGGER.info("Web map {}: complete; {} base JPEG tiles, {} zoom levels, pending={}, total {} s",
				this.id, this.tiles[0].size(), this.zoomLevels, this.pending.size(),
				(System.currentTimeMillis() - wallStart) / 1000);
			return last < 0 || changed > 0 ? started : PASS_UNCHANGED;
		}

		/** Renders at most limit pending tiles, with the same tick/save safeguards as before. */
		private boolean renderQueued(TableLooks looks, int limit) {
			return this.renderQueued(looks, limit, null);
		}

		private boolean renderQueued(TableLooks looks, int limit, @Nullable Set<Long> taskTiles) {
			this.map.live.beginRender();
			this.source.clear();
			try {
			Set<Long> rendered = new HashSet<>();
			int processed = 0;
			int skippedTiles = 0;
			boolean writeFailed = false;
			long lastWaitLog = 0;
			var batch = taskTiles == null ? this.pending.first(limit) : taskTiles.stream().limit(limit).toList();
			Set<Long> skipped = new HashSet<>();
			for (long tile : batch) {
				this.map.awaitActive(taskTiles != null);
				if (processed >= limit || (taskTiles == null ? this.map.stopping() : this.map.taskStopping())) break;
				long waitStarted = 0;
				while (!(taskTiles == null ? this.map.stopping() : this.map.taskStopping())) {
					boolean saving = this.map.server.isCurrentlySaving();
					double mspt = this.map.averageTickMs();
					if (!saving && mspt <= 40.0) break;
					long now = System.currentTimeMillis();
					if (waitStarted == 0) waitStarted = now;
					if (!saving && mspt <= 50.0 && now - waitStarted >= BUSY_GRACE_MS) break;
					this.map.setStatus("waiting " + this.id + " before tile " + (processed + 1) + "/"
						+ limit + "; tick " + String.format(Locale.ROOT, "%.1f", mspt)
						+ " ms, saving=" + saving + ", pending=" + this.pending.size());
					if (now - lastWaitLog >= PROGRESS_LOG_MS) {
						Diorama.LOGGER.warn("Web map {}: paused tile rendering, tick {} ms, saving={}, pending={}, waited {} s",
							this.id, String.format(Locale.ROOT, "%.1f", mspt), saving,
							this.pending.size(), (now - waitStarted) / 1000);
						lastWaitLog = now;
					}
					sleep(1000);
				}
				this.map.awaitActive(taskTiles != null);
				if (taskTiles == null ? this.map.stopping() : this.map.taskStopping()) break;
				int u = (int) (tile >> 32);
				int v = (int) tile;
				this.map.setStatus("drawing " + this.id + " tile " + u + "," + v
					+ ", pending=" + this.pending.size() + ", tick "
					+ String.format(Locale.ROOT, "%.1f", this.map.averageTickMs()) + " ms");
				long tileStart = System.nanoTime();
				int[] pixels;
				try {
					pixels = TileBaker.reference(new TileKey(TileKey.SURFACE, u, v), this.source, looks, false, null, null);
				} catch (RuntimeException | LinkageError | StackOverflowError e) {
						// Background discovery can continue past a broken block model.
					// An explicit task reports the bake failure.
					skippedTiles++;
					skipped.add(tile);
					processed++;
					Diorama.LOGGER.warn("Couldn't bake web-map tile {} {},{}; skipping until a later update or force-render", this.id, u, v, e);
					continue;
				}
				try {
					Path depthFile = this.folder.resolve("0").resolve(u + "_" + v + ".depth");
					if (this.map.settings.depth() || this.map.tracks.contains(this.id,u,v)) {
						try {
							var surface = TileBaker.surface(new TileKey(TileKey.SURFACE, u, v), 0, this.source, looks, false,
								0, 0, Projection.TILE_PIXELS, Projection.TILE_PIXELS);
							this.map.tracks.composite(this.id,u,v,pixels,surface);
							if(this.map.settings.depth()){
								WebDepth.write(depthFile, surface);
								dev.diorama.web.vehicles.VehicleGround.write(depthFile.resolveSibling(u + "_" + v + ".ground"), surface);
							}else{Files.deleteIfExists(depthFile);Files.deleteIfExists(depthFile.resolveSibling(u + "_" + v + ".ground"));}
						} catch (RuntimeException | LinkageError | StackOverflowError error) {
							Files.deleteIfExists(depthFile);
							Files.deleteIfExists(depthFile.resolveSibling(u + "_" + v + ".ground"));
							Diorama.LOGGER.warn("Couldn't trace terrain depth for {} {},{}; publishing colour only", this.id, u, v, error);
						}
					} else {
						Files.deleteIfExists(depthFile);
						Files.deleteIfExists(depthFile.resolveSibling(u + "_" + v + ".ground"));
					}
					boolean rail=this.map.tracks.contains(this.id,u,v);
					WebTiles.writeLossless(WebTiles.file(this.folder,0,u,v),pixels,rail);
					if(rail)this.lossless.add(tile);else this.lossless.remove(tile);
					long size = WebTiles.write(WebTiles.file(this.folder, 0, u, v), pixels);
					this.recordTile(0, tile, size);
					rendered.add(tile);
					processed++;
				} catch (IOException e) {
					// Disk errors are retryable; leave the tile in the durable queue.
					writeFailed = true;
					Diorama.LOGGER.warn("Couldn't write web-map tile {} {},{}", this.id, u, v, e);
				}
				long took = (System.nanoTime() - tileStart) / 1_000_000;
				if (took >= 10_000) {
					Diorama.LOGGER.warn("Web map {}: tile {},{} took {} s, {} pending",
						this.id, u, v, took / 1000, this.pending.size());
				}
				if (writeFailed) break;
			}
			if (!rendered.isEmpty()) {
				if (this.zoomOut(rendered) < 0 || !this.map.writeMapJson()) return false;
			}
			if (taskTiles != null) {
				taskTiles.removeAll(rendered);
				if (!skipped.isEmpty()) {
					this.map.tasks.progress("one or more tiles failed to bake");
					this.map.taskBakeFailed = true;
				}
			} else {
				for (long tile : skipped) this.pending.remove(tile);
			}
			for (long tile : rendered) this.pending.remove(tile);
			this.queuedCount = this.pending.size();
			if (!this.pending.save()) return false;
			if (!rendered.isEmpty()) {
				Diorama.LOGGER.info("Web map {}: published {} tiles; {} still queued, zoom levels={}",
					this.id, rendered.size(), taskTiles == null ? this.pending.size() : taskTiles.size(), this.zoomLevels);
			}

			if (skippedTiles > 0) {
				Diorama.LOGGER.warn("Web map {}: could not bake {} tiles in this batch",
					this.id, skippedTiles);
			}
			if (writeFailed) {
				this.map.setStatus("incomplete " + this.id + ": tile write error, will retry");
				return false;
			}
			return taskTiles != null || !this.map.stopping();

			} finally { this.map.live.endRender(); }
		}


		/** Queue only eligible saved chunks in the requested radius. Does not generate land. */
		private LinkedHashSet<Long> planRender(WebRenderTasks.Request request) throws IOException {
			var tilesToRender = new LinkedHashSet<Long>();
			boolean full = request.dimension() == null;
			Set<Long> changedTiles = new HashSet<>();
			int radius = request.radius();
			long radiusSquared = (long) radius * radius;
			int minX = (int) Math.floorDiv((long) request.x() - radius, 16);
			int maxX = (int) Math.floorDiv((long) request.x() + radius, 16);
			int minZ = (int) Math.floorDiv((long) request.z() - radius, 16);
			int maxZ = (int) Math.floorDiv((long) request.z() + radius, 16);

			var regions = this.scanRegions();
			long scanStart = System.nanoTime();
			int scanned = 0;
			for (Map.Entry<Long, int[]> region : regions.entrySet()) {
				this.map.awaitActive(true);
				if (++scanned % 32 == 0 || scanned == regions.size()) {
					this.map.tasks.progress("planning " + this.id + " " + WebRenderTasks.progress("regions",
						scanned, regions.size(), scanned, System.nanoTime() - scanStart));
				}
				if (this.map.taskStopping()) break;
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				if (!full && (regionX * 32 > maxX || regionX * 32 + 31 < minX
					|| regionZ * 32 > maxZ || regionZ * 32 + 31 < minZ)) continue;
				int[] times = region.getValue();
				Drawn[] previous = request.force() ? null : this.chunkState.read(region.getKey());
				for (int i = 0; i < times.length; i++) {
					Drawn before = previous == null ? null : previous[i];
					if (times[i] == 0 && before == null) continue;
					int chunkX = regionX * 32 + (i & 31);
					int chunkZ = regionZ * 32 + (i >> 5);
					if (!full && (chunkX < minX || chunkX > maxX || chunkZ < minZ || chunkZ > maxZ)) continue;
					long dx = (long) chunkX * 16 + 8 - request.x();
					long dz = (long) chunkZ * 16 + 8 - request.z();
					if (!full && dx * dx + dz * dz > radiusSquared + 256) continue;
					ServerSave.Info info = times[i] == 0 ? null : this.save.info(chunkX, chunkZ);
					if (info == null || !info.summary().finished()
						|| !this.map.settings.everyChunk() && info.inhabited() <= 0) continue;
					Drawn current = new Drawn(info.blocks(), info.summary().maxY(), info.summary().floorY());
					this.markTiles(chunkX, chunkZ, current.top(), current.floor(), tilesToRender);
					if (previous == null) continue;
					if (before == null) this.markNewerTiles(chunkX, chunkZ, current, times[i], changedTiles);
					else if (!current.equals(before)) {
						this.markTiles(chunkX, chunkZ, Math.max(before.top(), current.top()),
							Math.min(before.floor(), current.floor()), changedTiles);
					}

				}
			}
			// Render fills missing JPEGs; force-render replaces all the affected JPEGs.
			if (!request.force()) {
				Set<Long> alreadyQueued = this.pending.snapshot();
				tilesToRender.removeIf(tile -> this.tiles[0].contains(tile) && !alreadyQueued.contains(tile));
				tilesToRender.addAll(changedTiles);
			}
			if (full && request.force()) tilesToRender.addAll(this.tiles[0]);
			return tilesToRender;
		}

		/** Cancel outstanding queue and scan checkpoints but retain rendered JPEG tiles. */
		private void cancel(Properties progress) {
			this.pending.clear();
			this.queuedCount = 0;
			this.pending.save();
			String prefix = "scan." + this.id + ".";
			progress.remove(prefix + "cursor");
			progress.remove(prefix + "count");
			progress.remove(prefix + "regions");
			progress.remove(prefix + "since");
			progress.remove(prefix + "started");
		}

		/** Legacy tiles have no recorded input. Inspect their revisions while building a baseline. */
		private void markNewerTiles(int chunkX, int chunkZ, Drawn current, int saved, Set<Long> changed) {
			Set<Long> affected = new HashSet<>();
			this.markTiles(chunkX, chunkZ, current.top(), current.floor(), affected);
			for (long tile : affected) {
				long written = this.baseWritten.getOrDefault(tile, 0L);
				if (written == 0 || saved >= written / 1000) changed.add(tile);
			}
		}

		/** The tiles a chunk's blocks can show in, or cast shadows into. */
		private void markTiles(int chunkX, int chunkZ, int maxY, int floorY, Set<Long> dirty) {
			double sin = Projection.sin();
			double cos = Projection.cos();
			int x0 = chunkX * 16 - Math.abs(TileBaker.SHADOW_REACH_X) - 1;
			int x1 = chunkX * 16 + 16 + Math.abs(TileBaker.SHADOW_REACH_X) + 1;
			int z0 = chunkZ * 16 - Math.abs(TileBaker.SHADOW_REACH_Z) - 1;
			int z1 = chunkZ * 16 + 16 + Math.abs(TileBaker.SHADOW_REACH_Z) + 1;
			int top = maxY + 1;
			// Under a roof the map looks down from below it, as far as the dimension's floor; the
			// height maps there stop at the roof.
			int bottom = this.ceiling ? this.minimumY : Math.max(floorY, this.minimumY);
			double v0 = z0 * sin - (top + TileBaker.SHADOW_REACH_Y) * cos;
			double v1 = z1 * sin - bottom * cos;
			int u0 = Math.floorDiv(x0, Projection.TILE_UNITS);
			int u1 = Math.floorDiv(x1, Projection.TILE_UNITS);
			int tv0 = (int) Math.floor(v0 / Projection.TILE_UNITS);
			int tv1 = (int) Math.floor(v1 / Projection.TILE_UNITS);
			for (int u = u0; u <= u1; u++) {
				for (int v = tv0; v <= tv1; v++) {
					dirty.add(key(u, v));
				}
			}
		}

		/** Remakes the zoomed-out tiles over the ones drawn, adding levels as the map grows; the bytes written, or -1 if one failed. */
		private long zoomOut(Set<Long> drawn) {
			int levels = 1;
			int minU = Integer.MAX_VALUE;
			int maxU = Integer.MIN_VALUE;
			int minV = Integer.MAX_VALUE;
			int maxV = Integer.MIN_VALUE;
			for (long key : this.tiles[0]) {
				int u = (int) (key >> 32);
				int v = (int) key;
				minU = Math.min(minU, u);
				maxU = Math.max(maxU, u);
				minV = Math.min(minV, v);
				maxV = Math.max(maxV, v);
			}
			// Out until the whole map fits in a few tiles across.
			int span = this.tiles[0].isEmpty() ? 1 : Math.max(maxU - minU + 1, maxV - minV + 1);
			while (levels <= MAX_ZOOM && (span >> (levels - 1)) > 3) {
				levels++;
			}
			long bytes = 0;
			boolean failed = false;
			Set<Long> below = drawn;
			for (int zoom = 1; zoom < levels; zoom++) {
				Set<Long> here = new HashSet<>();
				// A level the map hasn't had yet is made whole.
				for (long key : zoom >= this.zoomLevels ? this.tiles[zoom - 1] : below) {
					here.add(key(Math.floorDiv((int) (key >> 32), 2), Math.floorDiv((int) key, 2)));
				}
				for (long key : here) {
					int u = (int) (key >> 32);
					int v = (int) key;
					Path[] children = new Path[4];
					for (int i = 0; i < 4; i++) {
						children[i] = WebTiles.file(this.folder, zoom - 1, u * 2 + (i & 1), v * 2 + (i >> 1));
					}
					try {
						long size = WebTiles.shrink(WebTiles.file(this.folder, zoom, u, v), children);
						bytes += size;
						this.recordTile(zoom, key, size);
					} catch (IOException e) {
						failed = true;
						Diorama.LOGGER.warn("Couldn't write web map tile {} zoom {} {},{}", this.id, zoom, u, v, e);
					}
				}
				below = here;
			}
			// The map got smaller (land it no longer shows): the levels over the new top go. One that
			// can't be deleted stays listed, and is tried again next pass.
			int kept = levels;
			for (int zoom = levels; zoom < this.zoomLevels && zoom <= MAX_ZOOM; zoom++) {
				for (java.util.Iterator<Long> it = this.tiles[zoom].iterator(); it.hasNext();) {
					long key = it.next();
					try {
						Files.deleteIfExists(WebTiles.file(this.folder, zoom, (int) (key >> 32), (int) key));
						it.remove();
						this.versions.get(zoom).remove(key);
					} catch (IOException e) {
						failed = true;
					}
				}
				if (!this.tiles[zoom].isEmpty()) {
					kept = zoom + 1;
				}
			}
			this.zoomLevels = kept;
			return failed ? -1 : bytes;
		}

		private void recordTile(int zoom, long tile, long size) {
			if (size > 0) {
				this.tiles[zoom].add(tile);
				this.updateVersion(zoom, tile);
			} else {
				this.tiles[zoom].remove(tile);
				this.versions.get(zoom).remove(tile);
				if (zoom == 0) this.baseWritten.remove(tile);
			}
		}

		private void updateVersion(int zoom, long tile) {
			try {
				Path file = WebTiles.file(this.folder, zoom, (int) (tile >> 32), (int) tile);
				long jpegWritten = Files.getLastModifiedTime(file).toMillis();
				this.versions.get(zoom).put(tile, WebCoverage.version(file, jpegWritten));
				if (zoom == 0) this.baseWritten.put(tile, jpegWritten);
			} catch (IOException error) {
				this.versions.get(zoom).remove(tile);
				if (zoom == 0) this.baseWritten.remove(tile);
			}
		}

		void json(StringBuilder json) {
			json.append("{\"losslessTiles\":[");boolean firstLossless=true;
			for(long key:this.lossless){if(!firstLossless)json.append(',');firstLossless=false;json.append('[').append((int)(key>>32)).append(',').append((int)key).append(']');}
			json.append("],");
			json.append("\"id\":\"").append(this.id).append("\",\"folder\":\"").append(this.folder.getFileName())
				.append("\",\"fog\":\"").append(this.level.dimensionType().hasCeiling() ? "none" : "fog")
				.append("\",\"referenceY\":").append(this.level.getChunkSource().getGenerator().getSpawnHeight(this.level))
				.append(",\"zoomLevels\":").append(Math.max(1, this.zoomLevels)).append(",\"versions\":true,\"tiles\":[");
			for (int z = 0; z < Math.max(1, this.zoomLevels); z++) {
				if (z > 0) {
					json.append(',');
				}
				json.append('[');
				boolean first = true;
				for (long key : this.tiles[z]) {
					if (!first) {
						json.append(',');
					}
					first = false;
					int u = (int) (key >> 32);
					int v = (int) key;
					json.append(u).append(',').append(v).append(',').append(this.versions.get(z).getOrDefault(key, 0L));
				}
				json.append(']');
			}
			json.append("]}");
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
