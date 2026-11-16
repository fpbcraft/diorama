package dev.diorama;

import dev.diorama.bake.TileKey;
import net.minecraft.core.BlockPos;

/** Flat chunk grid shared by claim painting, tooltips and OPAC actions. */
public final class ClaimProjection {
	public static final int SURFACE_Y = 64;

	private ClaimProjection() {
	}

	public static int height(int layer) {
		return layer == TileKey.SURFACE ? SURFACE_Y : layer;
	}

	public static BlockPos spot(double u, double v, int layer) {
		int y = height(layer);
		return Projection.facing().worldPos(new BlockPos((int) Math.floor(u), y,
			(int) Math.floor(Projection.zAt(v, y))));
	}
}
