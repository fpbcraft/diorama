package dev.diorama.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** Opt-in regression checks: DIORAMA_TESTS=web-render-queue. */
public final class WebTileQueueTest {
	private WebTileQueueTest() {}

	public static void run() {
		Path dir;
		try {
			dir = Files.createTempDirectory("diorama-web-tiles-");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try {
			long a = WebTileQueue.key(-20, 11);
			long b = WebTileQueue.key(2, -500);
			long c = WebTileQueue.key(0, 0);
			WebTileQueue queue = new WebTileQueue(dir);
			queue.addAll(List.of(a, b, a));
			if (queue.size() != 2 || !queue.save()) throw new AssertionError("Initial queue checkpoint failed");
			// Simulate server restart without losing unfinished tiles.
			WebTileQueue restored = new WebTileQueue(dir);
			if (restored.size() != 2 || !restored.snapshot().containsAll(List.of(a, b))) {
				throw new AssertionError("Persisted queue or signed tile coordinates were lost");
			}
			restored.addFirst(List.of(c, b));
			Iterator<Long> order = restored.snapshot().iterator();
			if (order.next() != c || order.next() != b || order.next() != a || order.hasNext()) {
				throw new AssertionError("Manual render work must take priority and deduplicate");
			}
			restored.remove(b);
			if (!restored.save()) throw new AssertionError("Couldn't checkpoint completed tile");
			WebTileQueue after = new WebTileQueue(dir);
			if (after.size() != 2 || after.snapshot().contains(b)) {
				throw new AssertionError("Completed tile returned to the queue after restart");
			}
			after.clear();
			if (!after.save() || !new WebTileQueue(dir).isEmpty()) {
				throw new AssertionError("Cancellation did not persist an empty queue");
			}
		} finally {
			try (Stream<Path> paths = Files.walk(dir)) {
				for (Path entry : paths.sorted((x, y) -> y.compareTo(x)).toList()) {
					Files.deleteIfExists(entry);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
