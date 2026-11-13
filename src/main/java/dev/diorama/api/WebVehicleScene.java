package dev.diorama.api;

import java.nio.file.Path;
import java.util.List;

/** Immutable public publication snapshot. Consumers reuse it rather than sample Create/Sable again. */
public final class WebVehicleScene {
	public record Vehicle(String provider, String sourceId, String label, String dimension, long version,
		double x, double y, double z, double qx, double qy, double qz, double qw, Path sprite, Path atlas) {}
	public record Snapshot(long time, List<Vehicle> vehicles) {}
	private static volatile Snapshot latest = new Snapshot(0, List.of());
	private WebVehicleScene() {}
	public static Snapshot latest() { return latest; }
	public static void publish(Snapshot snapshot) { latest = new Snapshot(snapshot.time(), List.copyOf(snapshot.vehicles())); }
	public static void clear() { latest = new Snapshot(0, List.of()); }
}
