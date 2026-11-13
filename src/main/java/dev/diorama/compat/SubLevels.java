package dev.diorama.compat;

import dev.diorama.craft.Body;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;

/** Client settings and enumeration; capture and poses are shared with the web publisher. */
public final class SubLevels {
	private SubLevels() {}
	public static boolean holds(ClientLevel level, int x, int z) { return SubLevelBodies.holds(level, x, z); }
	public static void edited(int x, int z) { SubLevelBodies.edited(x, z); }
	public static List<Body> bodies(ClientLevel level) {
		return dev.diorama.Settings.get().showSableShipShapes ? SubLevelBodies.bodies(level) : List.of();
	}
}
