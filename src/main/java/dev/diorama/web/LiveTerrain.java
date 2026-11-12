package dev.diorama.web;

import dev.diorama.data.ChunkSnapshot;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-thread copies and worker-thread rendering share immutable bounded snapshots. */
final class LiveTerrain {
	record Changed(String dimension, long key, ChunkSnapshot snapshot, long capturedAt) {}
	private static final int MAX_SNAPSHOTS = 256;
	private final Map<String, DirtyChunks> dirty = new HashMap<>(); // server thread only
	private final LinkedHashMap<String, Changed> snapshots = new LinkedHashMap<>();
	private final LinkedHashMap<String, Changed> updates = new LinkedHashMap<>();
	private Map<String, Changed> rendering;
	private final long delay;
	LiveTerrain(long delay) { this.delay = delay; }
	void mark(ServerLevel level, BlockPos pos) {
		String dimension = level.dimension().identifier().toString();
		dirty.computeIfAbsent(dimension, ignored -> new DirtyChunks(2048, delay))
			.mark(ChunkSnapshot.key(pos.getX() >> 4, pos.getZ() >> 4), System.currentTimeMillis());
	}
	boolean capture(ServerLevel level, boolean everyChunk) {
		String dimension = level.dimension().identifier().toString();
		var queue = dirty.get(dimension); if (queue == null) return false;
		Long key = queue.poll(System.currentTimeMillis()); if (key == null) return false;
		int x = (int) (key >> 32), z = (int) (long) key;
		var chunk = level.getChunkSource().getChunkNow(x, z);
		if (chunk == null) {
			synchronized (this) {
				String id = dimension + ":" + key;
				Changed previous = snapshots.remove(id);
				if (previous != null) { updates.put(id, previous); return true; }
			}
			return false;
		}
		if (!everyChunk && chunk.getInhabitedTime() <= 0) return false;
		var changed = new Changed(dimension, key, LiveChunkCapture.capture(level, chunk), System.currentTimeMillis());
		synchronized (this) {
			String id = dimension + ":" + key;
			snapshots.remove(id); snapshots.put(id, changed); updates.put(id, changed);
			while (snapshots.size() > MAX_SNAPSHOTS) snapshots.remove(snapshots.keySet().iterator().next());
			while (updates.size() > MAX_SNAPSHOTS) updates.remove(updates.keySet().iterator().next());
		}
		return true;
	}
	synchronized ChunkSnapshot chunk(String dimension, int x, int z) {
		var changed = (rendering == null ? snapshots : rendering).get(dimension + ":" + ChunkSnapshot.key(x, z));
		return changed == null ? null : changed.snapshot();
	}
	void cancel() { dirty.clear(); synchronized (this) { updates.clear(); } }
	synchronized void beginRender() { rendering = Map.copyOf(snapshots); }
	synchronized void endRender() { rendering = null; }
	synchronized void saved(String dimension, int x, int z, long savedSeconds) {
		String id = dimension + ":" + ChunkSnapshot.key(x, z);
		Changed previous = snapshots.get(id);
		if (previous != null && savedSeconds > previous.capturedAt() / 1000) snapshots.remove(id);
	}
	synchronized boolean hasUpdates() { return !updates.isEmpty(); }
	synchronized List<Changed> drain() { var list = List.copyOf(updates.values()); updates.clear(); return list; }
}
