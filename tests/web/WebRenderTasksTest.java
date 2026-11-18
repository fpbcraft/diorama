package dev.diorama.web;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class WebRenderTasksTest {
	private static final String DIMENSION = "minecraft:overworld";

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args[0]);
		Path file = root.resolve("render-tasks.dat");
		var tasks = new WebRenderTasks(file);
		int first = tasks.enqueue(new WebRenderTasks.Request(null, 0, 0, 0, true));
		int second = tasks.enqueue(new WebRenderTasks.Request(DIMENSION, 100, 20, 64, false));
		check(tasks.begin().id() == first, "jobs start in FIFO order");
		tasks.revalidatePlan("appearance/generated");
		tasks.remaining = new LinkedHashMap<>();
		tasks.remaining.put(DIMENSION, new LinkedHashSet<>(List.of(-10L, 42L, 57L)));
		tasks.total = 3;
		tasks.save();

		tasks.remaining.get(DIMENSION).remove(-10L);
		tasks.completed = 1;
		tasks.save();
		tasks = new WebRenderTasks(file);
		check(tasks.begin().id() == first, "restart must resume the active job");
		tasks.revalidatePlan("appearance/generated");
		check(tasks.total == 3 && tasks.completed == 1, "restart preserves measured progress");
		check(List.copyOf(tasks.remaining.get(DIMENSION)).equals(List.of(42L, 57L)),
			"restart must not schedule already published tiles");
		check(tasks.cancel(second) && !tasks.cancelled(), "cancelling a queued job leaves the active job alone");

		int third = tasks.enqueue(new WebRenderTasks.Request(null, 0, 0, 0, false));
		check(tasks.cancel(first) && tasks.cancelled(), "active cancellation is visible to the worker");
		tasks.save();
		tasks = new WebRenderTasks(file);
		check(tasks.begin().id() == third && tasks.remaining == null,
			"restart must not inherit the cancelled job's tile plan");
		check(WebRenderTasks.progress("tiles", 2, 10, 2, 4_000_000_000L)
			.equals("2/10 tiles (20.0%), ETA 0h 00m 16s"), "ETA uses completed tiles and elapsed time");
		check(!WebRenderTasks.progress("tiles", 0, 10, 0, 1).contains("ETA"), "no ETA before a sample");

		long region = -1L << 32 | 17L;
		var state = new WebChunkState(root.resolve("chunk-state"));
		var known = new WebChunkState.Drawn[WebChunkState.CHUNKS];
		known[17] = new WebChunkState.Drawn(112233, 104, -63);
		state.write(region, known);
		state = new WebChunkState(root.resolve("chunk-state"));
		check(known[17].equals(state.read(region)[17]), "restart keeps the old height and content fingerprint");
		check(state.read(0)[17] == null, "a new region does not affect another region's fingerprint");
		check(state.regions().contains(region), "saved fingerprints identify removed regions");
		state.write(region, new WebChunkState.Drawn[WebChunkState.CHUNKS]);
		check(!state.regions().contains(region), "a completed removal drops its fingerprint file");
		System.out.println("Render task resumption, cancellation, ETA and per-region fingerprints passed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
