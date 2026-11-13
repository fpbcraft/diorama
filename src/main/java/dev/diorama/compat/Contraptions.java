package dev.diorama.compat;

import dev.diorama.craft.Body;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;

/** Client enumeration; block and pose capture is shared with the web publisher. */
public final class Contraptions {
	private Contraptions() {}
	public static List<Body> bodies(ClientLevel level) {
		return ContraptionBodies.bodies(level.entitiesForRendering());
	}
}
