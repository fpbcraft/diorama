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
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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

	private static @Nullable WebMap running;

	private final MinecraftServer server;
	private final WebSettings settings;
	private final VanillaTable table;
	private final Path modsFolder;
	final Path folder;
	private final Map<String, Dimension> dimensions = new ConcurrentHashMap<>();
	private final Object wake = new Object();
	private final Thread thread;
	private @Nullable WebServer http;
	private volatile boolean stopping;
	/** An operator asked to draw everything again; taken by the next pass. */
	private final java.util.concurrent.atomic.AtomicBoolean redrawAll = new java.util.concurrent.atomic.AtomicBoolean();
	/** A full redraw that didn't complete: done again at the next pass, after the usual wait. */
	private boolean redrawOwed;
	private volatile String status = "starting";
	private volatile boolean paused;
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private final AtomicBoolean forceAll = new AtomicBoolean();
	private final ConcurrentLinkedQueue<RenderRequest> requested = new ConcurrentLinkedQueue<>();
	/** Passes over every dimension finished since the server started. */
	private volatile int passes;

	private WebMap(MinecraftServer server, WebSettings settings, VanillaTable table, Path modsFolder) {
		this.server = server;
		this.settings = settings;
		this.table = table;
		this.modsFolder = modsFolder;
		this.folder = settings.folder() != null ? settings.folder() : server.getWorldPath(LevelResource.ROOT).resolve("diorama-web");
		this.thread = new Thread(this::run, "Diorama web map");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/** Starts the web map if the settings turn it on. Server thread, once the server has started. */
	public static synchronized void started(MinecraftServer server, Path configFolder, Path modsFolder) {
		if (!server.isDedicatedServer()) {
			return;
		}
		WebSettings settings = WebSettings.load(configFolder);
		if (!settings.enabled()) {
			Diorama.LOGGER.info("Diorama's web map is off (web-map in config/{})", WebSettings.FILE);
			return;
		}
		VanillaTable table = VanillaTable.shipped();
		if (table == null) {
			Diorama.LOGGER.warn("Diorama's web map can't start: this jar has no vanilla colour table");
			return;
		}
		WebMap map = new WebMap(server, settings, table, modsFolder);
		if (map.start()) {
			running = map;
		}
	}

	/** Stops the web map, if it runs. Server thread. */
	public static synchronized void stopped() {
		WebMap map = running;
		running = null;
		if (map != null) {
			map.stop();
		}
	}

	/** The running web map, or null. */
	public static @Nullable WebMap get() {
		return running;
	}

	/** Starts drawing and serving; false (logged) if the folder can't be written. */
	private boolean start() {
		try {
			Files.createDirectories(this.folder);
			try (InputStream page = WebMap.class.getResourceAsStream("/diorama/web/index.html")) {
				if (page != null) {
					Files.copy(page, this.folder.resolve("index.html"), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Diorama's web map can't write to {}, so it doesn't start", this.folder, e);
			return false;
		}
		if (this.settings.port() > 0) {
			this.http = WebServer.start(this.folder, this.settings.address(), this.settings.port());
		}
		this.thread.start();
		Diorama.LOGGER.info("Diorama's web map is drawing into {}{}", this.folder,
			this.http != null ? ", served at http://" + (this.settings.address().isEmpty() ? "<this server>" : this.settings.address()) + ":" + this.settings.port() + "/" : "");
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
		if (this.http != null) {
			this.http.stop();
		}
	}


	/** Radius requests stay on the background worker; no disk IO runs on the server thread. */
	record RenderRequest(String dimension, int x, int z, int radius, boolean force) {}

	public void requestFull(boolean force) {
		if (force) this.forceAll.set(true);
		this.redrawAll.set(true);
		synchronized (this.wake) { this.wake.notifyAll(); }
		Diorama.LOGGER.info("Web map: {} full-world render requested", force ? "forced" : "normal");
	}

	public void redrawAll() { this.requestFull(true); }

	public void requestArea(String dimension, int x, int z, int radius, boolean force) {
		this.requested.add(new RenderRequest(dimension, x, z, radius, force));
		synchronized (this.wake) { this.wake.notifyAll(); }
		Diorama.LOGGER.info("Web map: {} render queued in {} at {},{} radius {}",
			force ? "forced" : "normal", dimension, x, z, radius);
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
		this.cancelRequested.set(true);
		this.paused = true;
		synchronized (this.wake) { this.wake.notifyAll(); }
	}

	private int queuedTiles() {
		return this.dimensions.values().stream().mapToInt(d -> d.queuedCount).sum() + this.requested.size();
	}

	private void awaitActive() {
		while (this.paused && !this.stopping && !this.cancelRequested.get()) {
			this.status = "paused by operator";
			synchronized (this.wake) {
				try { this.wake.wait(1000); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
			}
		}
	}

	/** Process operator region requests between short batches of automatic scanning. */
	private void handleRequests(TableLooks looks) {
		RenderRequest request;
		while ((request = this.requested.poll()) != null && !this.stopping && !this.cancelRequested.get()) {
			for (ServerLevel level : this.server.getAllLevels()) {
				if (!level.dimension().identifier().toString().equals(request.dimension())) continue;
				Dimension dimension = this.dimensions.computeIfAbsent(request.dimension(),
					id -> new Dimension(this, level, id));
				dimension.renderArea(looks, request);
				break;
			}
		}
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
			+ (this.paused ? ", paused=true" : "");
	}

	private void run() {
		try {
			this.colourMaps();
			JarModels jars = JarModels.of(this.modsFolder);
			TableLooks looks = new TableLooks(this.table, jars);
			Diorama.LOGGER.info("Web map colours: vanilla table of {}; block models from {} mod jars", this.table.describe(), jars.jarCount());
			Properties progress = this.loadProgress();
			// Drawn under another share-land: land the map shows now may be missing, or land it
			// no longer shows still drawn. Everything again.
			String land = this.settings.everyChunk() ? "generated" : "visited";
			if (!land.equals(progress.getProperty("share-land", land))) {
				Diorama.LOGGER.info("share-land changed to {}: drawing the whole web map again", land);
				this.redrawAll.set(true);
				this.forceAll.set(true);
			} else {
				progress.setProperty("share-land", land);
			}
			// A stale progress file may claim a dimension was rendered even if the
			// base tile folder is empty (e.g. an older interrupted first pass).
			// Recover once per dimension on startup without deleting any stored data.
			Set<String> checkedEmptyProgress = new HashSet<>();

			while (!this.stopping) {
				this.awaitActive();
				if (this.cancelRequested.getAndSet(false)) {
					for (Dimension dimension : this.dimensions.values()) dimension.cancel(progress);
					this.requested.clear();
					this.redrawAll.set(false);
					this.redrawOwed = false;
					this.saveProgress(progress);
					this.status = "cancelled; existing JPEGs kept, use /diorama-web start to resume";
					continue;
				}
				this.handleRequests(looks);
				boolean all = this.redrawAll.getAndSet(false) || this.redrawOwed;
				boolean force = this.forceAll.getAndSet(false);
				this.redrawOwed = false;
				long passStarted = System.currentTimeMillis();
				boolean complete = true;
				String interruptedAt = null;
				for (ServerLevel level : this.server.getAllLevels()) {
					if (this.stopping) {
						break;
					}
					String id = level.dimension().identifier().toString();
					Dimension dimension = this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
					long last = all ? -1 : Long.parseLong(progress.getProperty(id, "-1"));
					if (last >= 0 && dimension.tiles[0].isEmpty() && checkedEmptyProgress.add(id)) {
						Diorama.LOGGER.warn("Web map {}: progress file says rendering completed, but no base JPEG tiles exist; rescanning saved chunks from scratch", id);
						last = -1;
					}
					long started = dimension.pass(looks, last, progress, force);
					if (started >= 0) {
						progress.setProperty(id, Long.toString(started));
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
				if (all && complete && !this.stopping) {
					// Only now is the map drawn under this share-land: a redraw cut short starts over.
					progress.setProperty("share-land", land);
					this.saveProgress(progress);
				} else if (!complete && !this.stopping) {
					// Retry the full map, not the partial in-memory snapshot, even when
					// the failed pass was incremental instead of an explicit redraw.
					this.redrawOwed = true;
				}
				this.writeMapJson();
				if (!complete && !this.stopping) {
					String cause = interruptedAt == null ? "an incomplete dimension pass" : interruptedAt;
					if (this.redrawAll.get() || !this.requested.isEmpty()) {
						this.status = "restarting incomplete pass: another redraw was requested";
					} else {
						this.status = "incomplete: " + cause + "; retrying in " + RETRY_GAP_MS / 1000 + " s";
						Diorama.LOGGER.warn("Web map pass incomplete ({}). Retrying the full map in {} s", cause, RETRY_GAP_MS / 1000);
						this.waitForRetry();
					}
					continue;
				}
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
			long wait = Math.max(CHECK_MS, notBefore - System.currentTimeMillis());
			synchronized (this.wake) {
				try {
					this.wake.wait(wait);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (this.stopping || this.redrawAll.get() || !this.requested.isEmpty()) return;
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

	private void saveProgress(Properties progress) {
		Path file = this.folder.resolve("progress.properties");
		Path part = this.folder.resolve("progress.properties.part");
		try (Writer writer = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
			progress.store(writer, "When each dimension's last web map pass started, in the region files' seconds");
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
			return;
		}
		try {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
		}
	}

	/** What the page reads first: the projection, and each dimension's tiles at each zoom. */
	void writeMapJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"tileUnits\":").append(Projection.TILE_UNITS)
			.append(",\"tilePixels\":").append(Projection.TILE_PIXELS)
			.append(",\"sin\":").append(String.format(Locale.ROOT, "%.6f", Projection.sin()))
			.append(",\"cos\":").append(String.format(Locale.ROOT, "%.6f", Projection.cos()))
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
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't write the web map's map.json", e);
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
		private volatile int queuedCount;
		/** Per region, per chunk: whether it's been looked at, and whether the map shows it. */
		private final Map<Long, long[]> shown = new HashMap<>();
		/**
		 * Each drawn chunk's blocks and height as last drawn since the server started, by
		 * {@link ChunkSnapshot#key}: a chunk saved with the same blocks needs no redraw, and one that
		 * got lower still redraws the tiles it used to reach.
		 */
		private final Map<Long, Drawn> drawn = new HashMap<>();
		/** Tiles on disk, per zoom level, as {@link #key}. */
		@SuppressWarnings("unchecked")
		private final Set<Long>[] tiles = new Set[MAX_ZOOM + 1];
		private int zoomLevels;

		Dimension(WebMap map, ServerLevel level, String id) {
			this.map = map;
			this.level = level;
			this.id = id;
			this.folder = map.folder.resolve(id.replace(':', '_').replace('/', '_'));
			this.pending = new WebTileQueue(this.folder);
			this.queuedCount = this.pending.size();
			this.save = ServerSave.of(level);
			int roof = level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() : ChunkSource.NO_ROOF;
			int minY = level.getMinY();
			int maxY = level.getMaxY() + 1;
			ChunkSource bounds = new ChunkSource() {
				@Override
				public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
					return null;
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
								this.tiles[z].add(key(Integer.parseInt(name.substring(0, sep)), Integer.parseInt(name.substring(sep + 1, name.length() - 4))));
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

		/** A chunk as last drawn: its {@link ServerSave.Info#blocks}, highest block and floor. */
		private record Drawn(int blocks, int top, int floor) {
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
			for (int[] times : this.save.scan().values()) {
				for (int time : times) {
					if (time != 0 && time >= since) {
						return true;
					}
				}
			}
			return false;
		}

		/**
		 * Draws the tiles of every chunk saved at or after {@code last} (all of them when it's
		 * negative). Returns when the pass started in region file seconds, or -1 if it was cut short.
		 */

		/**
		 * Progressive region discovery. Every batch durably queues affected base tiles
		 * before checkpointing its scan cursor, then renders a few tiles immediately.
		 * This makes the first JPEG available while much of the world remains unscanned.
		 * On restart, queued tiles and the cursor resume without reprocessing the whole world.
		 */
		long pass(TableLooks looks, long last, Properties progress, boolean force) {
			long started = System.currentTimeMillis() / 1000;
			long wallStart = System.currentTimeMillis();
			int failedReads = this.save.failedReads();
			String prefix = "scan." + this.id + ".";
			this.map.setStatus("scanning region headers for " + this.id);
			Map<Long, int[]> regions = this.save.scan();
			List<Map.Entry<Long, int[]>> ordered = new ArrayList<>(regions.entrySet());
			// Start near spawn instead of the signed-coordinate corner of the world.
			ordered.sort(Comparator.comparingLong((Map.Entry<Long, int[]> e) -> {
				long x = (int) (e.getKey() >> 32);
				long z = (int) (long) e.getKey();
				return x * x + z * z;
			}).thenComparingLong(Map.Entry::getKey));
			this.source.clear();
			int cursor = 0;
			try {
				int savedCount = Integer.parseInt(progress.getProperty(prefix + "count", "-1"));
				long savedLast = Long.parseLong(progress.getProperty(prefix + "since", "0"));
				if (savedCount == ordered.size() && savedLast == last && !force) {
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

			int changed = 0;
			int counted = 0;
			int batches = 0;
			long lastLog = System.currentTimeMillis();
			Set<Long> dirty = new HashSet<>();
			Set<Long> changedTiles = new HashSet<>();
			if (force) {
				// Force redraw must also clear stale JPEGs for land removed from the map.
				this.pending.addAll(this.tiles[0]);
				this.queuedCount = this.pending.size();
				if (!this.pending.save()) return PASS_INCOMPLETE;
			}
			for (int index = cursor; index < ordered.size(); index++) {
				this.map.awaitActive();
				if (!this.map.requested.isEmpty()) this.map.handleRequests(looks);
				if (this.map.stopping()) {
					this.map.setStatus("interrupted " + this.id + " scan after " + index + "/" + ordered.size()
						+ " regions: " + (this.map.shuttingDown() ? "server stopped" : "redraw requested"));
					return PASS_INCOMPLETE;
				}
				Map.Entry<Long, int[]> region = ordered.get(index);
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				int[] times = region.getValue();
				boolean any = false;
				for (int i = 0; i < 1024; i++) {
					if (times[i] == 0 || last >= 0 && times[i] < last) continue;
					any = true;
					int chunkX = regionX * 32 + (i & 31);
					int chunkZ = regionZ * 32 + (i >> 5);
					boolean wasShown = last >= 0 && (this.map.settings.everyChunk() || this.shows(chunkX, chunkZ));
					this.forget(chunkX, chunkZ);
					changed++;
					ServerSave.Info info = this.save.info(chunkX, chunkZ);
					if (info == null || !info.summary().finished()
							|| !this.map.settings.everyChunk() && info.inhabited() <= 0) {
						Drawn gone = this.drawn.remove(ChunkSnapshot.key(chunkX, chunkZ));
						if (gone != null) {
							this.markTiles(chunkX, chunkZ, gone.top(), gone.floor(), dirty);
						} else if (wasShown) {
							this.markTiles(chunkX, chunkZ, this.level.getMaxY(), this.level.getMinY(), dirty);
						}
						continue;
					}
					ChunkTags.Summary summary = info.summary();
					long key = ChunkSnapshot.key(chunkX, chunkZ);
					Drawn before = this.drawn.put(key, new Drawn(info.blocks(), summary.maxY(), summary.floorY()));
					if (last >= 0 && wasShown && before != null && before.blocks() == info.blocks()) continue;
					counted++;
					int top = before != null ? Math.max(before.top(), summary.maxY())
						: last >= 0 ? this.level.getMaxY() : summary.maxY();
					int floor = before != null ? Math.min(before.floor(), summary.floorY())
						: last >= 0 ? this.level.getMinY() : summary.floorY();
					this.markTiles(chunkX, chunkZ, top, floor, dirty);
					if (last < 0 && before != null && before.blocks() != info.blocks()) {
						this.markTiles(chunkX, chunkZ, top, floor, changedTiles);
					}
				}
				if (any) this.source.forgetTops(region.getKey());

				// No more than eight region files or 15 seconds before the first render batch.
				if ((index + 1) % 8 == 0 || System.currentTimeMillis() - wallStart > 15_000 && index == cursor
						|| index + 1 == ordered.size()) {
					// An explicit force/redraw or an incremental change re-renders existing JPEGs.
					// A normal initial full scan only fills in missing tiles.
					for (long tile : dirty) {
						if (force || last >= 0 || changedTiles.contains(tile) || !this.tiles[0].contains(tile)) this.pending.add(tile);
					}
					dirty.clear();
					changedTiles.clear();
					this.queuedCount = this.pending.size();
					if (!this.pending.save()) {
						this.map.setStatus("incomplete " + this.id + ": couldn't persist pending tile queue");
						return PASS_INCOMPLETE;
					}
					progress.setProperty(prefix + "cursor", Integer.toString(index + 1));
					progress.setProperty(prefix + "count", Integer.toString(ordered.size()));
					progress.setProperty(prefix + "since", Long.toString(last));
					progress.setProperty(prefix + "started", Long.toString(started));
					this.map.saveProgress(progress);
					// Give rendering a share of CPU now; do not wait for discovery to finish.
					if (!this.renderQueued(looks, 8)) return PASS_INCOMPLETE;
					batches++;
				}
				long now = System.currentTimeMillis();
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
			while (!this.pending.isEmpty() && !this.map.stopping()) {
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
			progress.remove(prefix + "since");
			progress.remove(prefix + "started");
			this.map.saveProgress(progress);
			Diorama.LOGGER.info("Web map {}: complete; {} base JPEG tiles, {} zoom levels, pending={}, total {} s",
				this.id, this.tiles[0].size(), this.zoomLevels, this.pending.size(),
				(System.currentTimeMillis() - wallStart) / 1000);
			return last < 0 || changed > 0 ? started : PASS_UNCHANGED;
		}

		/** Renders at most limit pending tiles, with the same tick/save safeguards as before. */
		private boolean renderQueued(TableLooks looks, int limit) {
			Set<Long> rendered = new HashSet<>();
			int processed = 0;
			int failures = 0;
			long lastWaitLog = 0;
			for (long tile : this.pending.snapshot()) {
				this.map.awaitActive();
				if (processed >= limit || this.map.stopping()) break;
				long waitStarted = 0;
				while (!this.map.stopping()) {
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
				if (this.map.stopping()) break;
				int u = (int) (tile >> 32);
				int v = (int) tile;
				this.map.setStatus("drawing " + this.id + " tile " + u + "," + v
					+ ", pending=" + this.pending.size() + ", tick "
					+ String.format(Locale.ROOT, "%.1f", this.map.averageTickMs()) + " ms");
				long tileStart = System.nanoTime();
				try {
					int[] pixels = TileBaker.reference(new TileKey(TileKey.SURFACE, u, v), this.source, looks, false, null, null);
					long size = WebTiles.write(WebTiles.file(this.folder, 0, u, v), pixels);
					if (size > 0) {
						this.tiles[0].add(tile);
					} else {
						this.tiles[0].remove(tile);
					}
					rendered.add(tile);
					this.pending.remove(tile);
					processed++;
				} catch (IOException | RuntimeException | LinkageError | StackOverflowError e) {
					failures++;
					Diorama.LOGGER.warn("Couldn't render web-map tile {} {},{}", this.id, u, v, e);
				}
				long took = (System.nanoTime() - tileStart) / 1_000_000;
				if (took >= 10_000) {
					Diorama.LOGGER.warn("Web map {}: tile {},{} took {} s, {} pending",
						this.id, u, v, took / 1000, this.pending.size());
				}
				if (failures != 0) break;
			}
			// Save the queue before declaring a scan batch complete; a crash might cause
			// a harmless duplicate render but cannot silently drop an unfinished tile.
			this.queuedCount = this.pending.size();
			if (!this.pending.save()) return false;
			if (!rendered.isEmpty()) {
				if (this.zoomOut(rendered) < 0) return false;
				this.map.writeMapJson();
				Diorama.LOGGER.info("Web map {}: published {} tiles; {} still queued, zoom levels={}",
					this.id, processed, this.pending.size(), this.zoomLevels);
			}
			if (failures > 0) {
				this.map.setStatus("incomplete " + this.id + ": " + failures + " tile render failure(s)");
				return false;
			}
			return !this.map.stopping();
		}


		/** Queue only eligible saved chunks in the requested radius. Does not generate land. */
		private void renderArea(TableLooks looks, RenderRequest request) {
			Set<Long> tilesToRender = new HashSet<>();
			Set<Long> changedTiles = new HashSet<>();
			int radius = request.radius();
			long radiusSquared = (long) radius * radius;
			int minX = Math.floorDiv(request.x() - radius, 16);
			int maxX = Math.floorDiv(request.x() + radius, 16);
			int minZ = Math.floorDiv(request.z() - radius, 16);
			int maxZ = Math.floorDiv(request.z() + radius, 16);
			this.map.setStatus("scheduling " + this.id + " radius " + radius + " at "
				+ request.x() + "," + request.z());
			int examined = 0;
			int eligible = 0;
			for (Map.Entry<Long, int[]> region : this.save.scan().entrySet()) {
				if (this.map.stopping()) break;
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				if (regionX * 32 > maxX || regionX * 32 + 31 < minX
					|| regionZ * 32 > maxZ || regionZ * 32 + 31 < minZ) continue;
				int[] times = region.getValue();
				for (int i = 0; i < times.length; i++) {
					if (times[i] == 0) continue;
					int chunkX = regionX * 32 + (i & 31);
					int chunkZ = regionZ * 32 + (i >> 5);
					if (chunkX < minX || chunkX > maxX || chunkZ < minZ || chunkZ > maxZ) continue;
					long dx = (long) chunkX * 16 + 8 - request.x();
					long dz = (long) chunkZ * 16 + 8 - request.z();
					if (dx * dx + dz * dz > radiusSquared + 256) continue;
					examined++;
					ServerSave.Info info = this.save.info(chunkX, chunkZ);
					if (info == null || !info.summary().finished()
						|| !this.map.settings.everyChunk() && info.inhabited() <= 0) continue;
					eligible++;
					long chunkKey = ChunkSnapshot.key(chunkX, chunkZ);
					Drawn before = this.drawn.put(chunkKey, new Drawn(info.blocks(), info.summary().maxY(), info.summary().floorY()));
					this.markTiles(chunkX, chunkZ, info.summary().maxY(), info.summary().floorY(), tilesToRender);
					// Like BlueMap update: normal render still updates changed blocks,
					// but does not unnecessarily overwrite an unchanged JPEG.
					if (before != null && before.blocks() != info.blocks()) {
						this.markTiles(chunkX, chunkZ, info.summary().maxY(), info.summary().floorY(), changedTiles);
					}
				}
			}
			// Render fills missing JPEGs; force-render replaces all the affected JPEGs.
			if (!request.force()) {
				tilesToRender.removeAll(this.tiles[0]);
				tilesToRender.addAll(changedTiles);
			}
			this.pending.addFirst(tilesToRender);
			this.queuedCount = this.pending.size();
			Diorama.LOGGER.info("Web map {}: {} radius {} at {},{} scanned {} chunks, {} eligible, queued {} tiles ({} total)",
				this.id, request.force() ? "force-render" : "render",
				radius, request.x(), request.z(), examined, eligible, tilesToRender.size(), this.pending.size());
			if (this.pending.save()) this.renderQueued(looks, 16);
			else this.map.setStatus("incomplete " + this.id + ": couldn't persist operator render queue");
		}

		/** Cancel outstanding queue and scan checkpoints but retain rendered JPEG tiles. */
		private void cancel(Properties progress) {
			this.pending.clear();
			this.queuedCount = 0;
			this.pending.save();
			String prefix = "scan." + this.id + ".";
			progress.remove(prefix + "cursor");
			progress.remove(prefix + "count");
			progress.remove(prefix + "since");
			progress.remove(prefix + "started");
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
			int bottom = this.level.dimensionType().hasCeiling() ? this.level.getMinY() : Math.max(floorY, this.level.getMinY());
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
						if (size > 0) {
							this.tiles[zoom].add(key);
						} else {
							this.tiles[zoom].remove(key);
						}
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

		void json(StringBuilder json) {
			json.append("{\"id\":\"").append(this.id).append("\",\"folder\":\"").append(this.folder.getFileName())
				.append("\",\"zoomLevels\":").append(Math.max(1, this.zoomLevels)).append(",\"versions\":true,\"tiles\":[");
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
					// When the tile was last written: the page asks for it with this, so a browser keeps a
					// picture until it changes rather than asking again each visit.
					long version;
					try {
						version = Files.getLastModifiedTime(WebTiles.file(this.folder, z, u, v)).toMillis() / 1000;
					} catch (IOException e) {
						version = 0;
					}
					json.append(u).append(',').append(v).append(',').append(version);
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
