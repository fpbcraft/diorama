package dev.diorama.frontier;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.drawing.Drawing;

/**
 * Client-only adapter between Diorama's tilted map camera and world X/Z
 * frontier geometry. The server's Frontier model never loads map classes.
 */
public final class FrontierProjection {
    private static final double GROUND_Y = 64.0;

    private FrontierProjection() {
    }

    public static Frontier fromDrawing(Drawing area) {
        if (area.kind != Drawing.Kind.AREA || !area.complete()) {
            throw new IllegalArgumentException("A complete area drawing is required");
        }
        double[] shown = area.shown();
        Facing facing = Projection.facing();
        Frontier frontier = new Frontier();
        frontier.dimension = area.dimension;
        frontier.color = area.color & 0xFFFFFF;
        frontier.vertices = new double[shown.length];
        for (int i = 0; i < shown.length; i += 2) {
            double z = Projection.zAt(shown[i + 1], GROUND_Y);
            frontier.vertices[i] = facing.worldX(shown[i], z);
            frontier.vertices[i + 1] = facing.worldZ(shown[i], z);
        }
        return frontier;
    }

    public static Drawing asDrawing(Frontier frontier) {
        double[] shown = new double[frontier.vertices.length];
        for (int i = 0; i < frontier.vertices.length; i += 2) {
            double x = frontier.vertices[i];
            double z = frontier.vertices[i + 1];
            shown[i] = Projection.u(x, z);
            shown[i + 1] = Projection.v(x, GROUND_Y, z);
        }
        return new Drawing(Drawing.Kind.AREA, frontier.dimension, frontier.color, shown, "");
    }
}
