package dev.diorama.bake;

import dev.diorama.MapStyle;

/**
 * Shared lighting/colour adaptation for non-voxel map geometry. Normal map blocks are
 * sampled into 4 px per world block, coloured by BlockLooks and lit with Lighting.
 * Curved-track pixels use that same colour pipeline instead of raw Minecraft sprites.
 */
public final class CurveStyle {
    private CurveStyle() {
    }

    /** A map pixel in ABGR (MapTexture's format), from a BlockLooks RGB colour. */
    public static int shade(int rgb, double worldY, boolean top) {
        MapStyle style = MapStyle.get();
        float[] light = new float[4];
        Lighting lighting = new Lighting(style);
        lighting.surface(top ? 1 : 0.45, false, 1, 1, 0, top ? 1 : TileBaker.SIDE, worldY, light);
        int r = rgb >> 16 & 255;
        int g = rgb >> 8 & 255;
        int b = rgb & 255;
        return TileBaker.abgr(style,
            TileBaker.SRGB_TO_LINEAR[r] * light[0],
            TileBaker.SRGB_TO_LINEAR[g] * light[1],
            TileBaker.SRGB_TO_LINEAR[b] * light[2],
            255);
    }
}
