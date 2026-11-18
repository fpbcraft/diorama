package dev.diorama.web;

import com.google.gson.Gson;
import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Operator requests and the remaining tiles of the current job, independent of discovery. */
final class WebRenderTasks {
	static final int LIMIT = 32;
	record Request(String dimension, int x, int z, int radius, boolean force) {
		String description() {
			return (force ? "force-render" : "render") + (dimension == null ? " all dimensions"
				: " " + dimension + " at " + x + "," + z + " radius " + radius);
		}
	}
	record Entry(int id, Request request) {}
	record Saved(int nextId, List<Entry> queue, Map<String, LinkedHashSet<Long>> remaining, long total, long completed, String inputs) {}

	private final Path file;
	private int nextId = 1;
	private final List<Entry> queue = new ArrayList<>();
	private final List<String> recent = new ArrayList<>();
	private boolean cancelActive;
	private boolean active;
	private long revision;
	private long savedRevision = -1;
	// The render thread owns the plan. The command thread reads only the published progress string.
	Map<String, LinkedHashSet<Long>> remaining;
	private String inputs;
	long total;
	long completed;
	long batchTimeNanos;
	long measuredTiles;
	private volatile String progress = "planning";

	WebRenderTasks(Path file) {
		this.file = file;
		if (!Files.isRegularFile(file)) return;
		try (var reader = Files.newBufferedReader(file)) {
			Saved saved = new Gson().fromJson(reader, Saved.class);
			if (saved == null || saved.queue() == null) return;
			nextId = Math.max(1, saved.nextId());
			queue.addAll(saved.queue());
			remaining = saved.remaining();
			total = saved.total();
			completed = saved.completed();
			inputs = saved.inputs();
		} catch (IOException | RuntimeException error) {
			Diorama.LOGGER.warn("Could not restore web render tasks from {}", file, error);
		}
	}

	synchronized int enqueue(Request request) {
		if (queue.size() >= LIMIT) return 0;
		int id = nextId++;
		queue.add(new Entry(id, request));
		revision++;
		return id;
	}

	synchronized boolean cancel(int id) {
		for (int i = 0; i < queue.size(); i++) {
			if (queue.get(i).id() != id) continue;
			if (i == 0 && (active || remaining != null)) cancelActive = true;
			else recordResult(queue.remove(i), "cancelled");
			revision++;
			return true;
		}
		return false;
	}

	synchronized void cancelAll() {
		if (active || remaining != null) {
			if (queue.size() > 1) queue.subList(1, queue.size()).clear();
			cancelActive = true;
		} else queue.clear();
		revision++;
	}

	synchronized boolean hasWork() { return !queue.isEmpty(); }
	synchronized boolean cancelled() { return cancelActive; }

	synchronized Entry begin() {
		if (queue.isEmpty()) return null;
		if (!active) progress = remaining == null ? "planning" : "resuming";
		active = true;
		return queue.get(0);
	}

	synchronized void finish(String outcome) {
		recordResult(queue.remove(0), outcome);
		active = false;
		cancelActive = false;
		remaining = null;
		total = 0;
		completed = 0;
		batchTimeNanos = 0;
		measuredTiles = 0;
		revision++;
	}

	synchronized List<String> describe(boolean paused) {
		List<String> result = new ArrayList<>(recent);
		for (int i = 0; i < queue.size(); i++) {
			Entry entry = queue.get(i);
			String state = i != 0 || !active && remaining == null ? "queued"
				: cancelActive ? "cancelling" : paused ? "paused" : progress;
			result.add("#" + entry.id() + " " + entry.request().description() + " (" + state + ")");
		}
		return result;
	}

	private void recordResult(Entry entry, String outcome) {
		if (recent.size() == 5) recent.remove(0);
		recent.add("#" + entry.id() + " " + entry.request().description() + " (" + outcome + ")");
	}

	void saveQueueChanges() throws IOException {
		synchronized (this) {
			if (revision == savedRevision) return;
		}
		save();
	}

	void revalidatePlan(String currentInputs) {
		if (java.util.Objects.equals(inputs, currentInputs)) return;
		inputs = currentInputs;
		remaining = null;
		total = 0;
		completed = 0;
		batchTimeNanos = 0;
		measuredTiles = 0;
		progress = "planning";
	}

	void progress(String state) { progress = state; }

	/** Writes a snapshot without holding the command thread's monitor during filesystem work. */
	void save() throws IOException {
		Saved snapshot;
		long currentRevision;
		synchronized (this) {
			currentRevision = revision;
			if (remaining == null && currentRevision == savedRevision) return;
			List<Entry> entries = new ArrayList<>(queue);
			boolean cancelled = cancelActive;
			if (cancelled && !entries.isEmpty()) entries.remove(0);
			snapshot = new Saved(nextId, entries, cancelled ? null : remaining, total, completed, inputs);
		}
		Path part = file.resolveSibling(file.getFileName() + ".part");
		Files.writeString(part, new Gson().toJson(snapshot));
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		savedRevision = currentRevision;
	}

	static String progress(String unit, long done, long total, long processed, long elapsedNanos) {
		double percent = total == 0 ? 100 : 100.0 * done / total;
		String result = String.format(Locale.ROOT, "%d/%d %s (%.1f%%)", done, total, unit, percent);
		if (processed == 0 || done >= total || elapsedNanos <= 0) return result;
		long seconds = Math.round((total - done) * (elapsedNanos / 1e9) / processed);
		return result + String.format(Locale.ROOT, ", ETA %dh %02dm %02ds", seconds / 3600, seconds / 60 % 60, seconds % 60);
	}
}
