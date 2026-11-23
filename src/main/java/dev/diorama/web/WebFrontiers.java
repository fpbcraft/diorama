package dev.diorama.web;

import com.google.gson.Gson;
import dev.diorama.Diorama;
import dev.diorama.frontier.Frontier;
import dev.diorama.frontier.LabelAnchor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Read-only public export of visible frontiers, written away from the server thread. */
final class WebFrontiers implements AutoCloseable {
	private record Entry(String dimension, String name, int color, double[] vertices, LabelAnchor label) {}
	private static final Gson JSON = new Gson();
	private final Path file;
	private final Supplier<List<Frontier>> source;
	private final Predicate<String> shown;
	private final ScheduledExecutorService worker;
	private List<Frontier> previous;
	private boolean closed;

	WebFrontiers(Path folder, Supplier<List<Frontier>> source, Predicate<String> shown) {
		this.file = folder.resolve("frontiers.json");
		this.source = source;
		this.shown = shown;
		this.worker = Executors.newSingleThreadScheduledExecutor(job -> {
			Thread thread = new Thread(job, "Diorama frontier feed");
			thread.setDaemon(true);
			return thread;
		});
		this.worker.scheduleWithFixedDelay(this::publish, 0, 2, TimeUnit.SECONDS);
	}

	private synchronized void publish() {
		if (this.closed) return;
		List<Frontier> snapshot = this.source.get();
		if (snapshot == this.previous) return;
		List<Entry> entries = snapshot.stream().filter(f -> this.shown.test(f.dimension))
			.map(f -> new Entry(f.dimension, f.name, f.color, f.vertices, LabelAnchor.inside(f.vertices))).toList();
		Path part = this.file.resolveSibling("frontiers.json.part");
		try {
			Files.writeString(part, JSON.toJson(entries));
			Files.move(part, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			this.previous = snapshot;
		} catch (IOException error) {
			Diorama.LOGGER.warn("Couldn't publish web map frontiers", error);
		}
	}

	@Override public synchronized void close() {
		this.closed = true;
		this.worker.shutdownNow();
	}
}
