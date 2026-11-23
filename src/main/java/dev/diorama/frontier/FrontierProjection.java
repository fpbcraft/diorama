package dev.diorama.frontier;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.drawing.Drawing;
import dev.diorama.render.MapViewport;
import org.jspecify.annotations.Nullable;

/**
 * Client-only adapter between Diorama's tilted map camera and world X/Z
 * frontier geometry. The server's Frontier model never loads map classes.
 */
public final class FrontierProjection {
    private static final double GROUND_Y = 64.0;

    private FrontierProjection() {
    }

    public record EdgePoint(int insertIndex, double x, double z, float screenX, float screenY) {
    }

    /** Nearest point within the GUI radius, including the polygon's closing edge. */
    public static @Nullable EdgePoint edgeAt(Frontier frontier, MapViewport.View view, double mouseX, double mouseY, double radius) {
        double[] vertices = frontier.vertices;
        double nearest = radius * radius;
        EdgePoint hit = null;
        for (int i = 0; i < vertices.length; i += 2) {
            int next = (i + 2) % vertices.length;
            float x = view.screenX(Projection.u(vertices[i], vertices[i + 1]));
            float y = view.screenY(Projection.v(vertices[i], GROUND_Y, vertices[i + 1]));
            double dx = view.screenX(Projection.u(vertices[next], vertices[next + 1])) - x;
            double dy = view.screenY(Projection.v(vertices[next], GROUND_Y, vertices[next + 1])) - y;
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared == 0) continue;
            double along = ((mouseX - x) * dx + (mouseY - y) * dy) / lengthSquared;
            if (along <= 0 || along >= 1) continue;
            double px = x + along * dx;
            double py = y + along * dy;
            double distance = (mouseX - px) * (mouseX - px) + (mouseY - py) * (mouseY - py);
            if (distance <= nearest && (hit == null || distance < nearest)) {
                nearest = distance;
                hit = new EdgePoint(i / 2 + 1, vertices[i] + along * (vertices[next] - vertices[i]),
                    vertices[i + 1] + along * (vertices[next + 1] - vertices[i + 1]), (float) px, (float) py);
            }
        }
        return hit;
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
