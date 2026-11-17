package dev.diorama.api;

import java.nio.file.Path;
import java.util.List;

/** Immutable public publication snapshot. Consumers reuse it rather than sample Create/Sable again. */
public final class WebVehicleScene {
	public record Vehicle(String provider, String sourceId, String label, String dimension, long version,
		double x, double y, double z, double qx, double qy, double qz, double qw, Path sprite, Path atlas) {}
	public record Snapshot(long time, List<Vehicle> vehicles) {}
	private static volatile Snapshot latest = new Snapshot(0, List.of());
	private static volatile boolean historyEnabled = true;
	private static volatile int historyIntervalMs = 500;
	private WebVehicleScene() {}
	public static Snapshot latest() { return latest; }
	/** History is independent of the live vehicle feed. Read by optional recorder integrations. */
	public static boolean historyEnabled() { return historyEnabled; }
	public static int historyIntervalMs() { return historyIntervalMs; }
	public static void configureHistory(boolean enabled, int intervalMs) {
		historyIntervalMs = Math.max(100, Math.min(60000, intervalMs));
		historyEnabled = enabled;
	}
	public static void publish(Snapshot snapshot) { latest = new Snapshot(snapshot.time(), List.copyOf(snapshot.vehicles())); }
	public static void clear() { latest = new Snapshot(0, List.of()); }
}
