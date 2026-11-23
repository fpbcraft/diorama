package dev.diorama.frontier;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.Projection;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.frontier.FrontierProjection.EdgePoint;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Nonpersistent map editing session. Point input, selection and vertex dragging
 * operate in world X/Z. Only Save submits an authoritative server mutation.
 */
public final class FrontierMapEditor {
    private static final float HANDLE_RADIUS = 5;
    private static final double GROUND_Y = 64;
    private final DrawingOverlay overlay = new DrawingOverlay();
    private boolean active;
    private boolean adding;
    private boolean newDraft;
    private int draggedVertex = -1;
    private @Nullable Frontier draft;

    public boolean active() { return active; }
    public boolean adding() { return adding; }
    public boolean inserting() { return adding && !newDraft; }
    public boolean hasDraft() { return draft != null; }
    public @Nullable Frontier draft() { return draft == null ? null : draft.copy(); }

    public void toggle() {
        if (!FrontierClient.canEdit()) {
            stop();
            return;
        }
        if (active) stop();
        else active = true;
    }

    public void stop() {
        active = false;
        cancel();
    }

    public void cancel() {
        draft = null;
        newDraft = false;
        adding = false;
        draggedVertex = -1;
    }

    public void newFrontier(String dimension) {
        if (!active || !FrontierClient.canEdit()) return;
        draft = new Frontier();
        newDraft = true;
        draft.dimension = dimension;
        draft.name = "New Frontier";
        draft.color = 0x48CFA5;
        adding = true;
        draggedVertex = -1;
    }

    public void addPoint() {
        if (active && draft != null) adding = !adding;
    }

    public void leaveDimension() {
        cancel();
    }

    /** World X and Z at the cursor's map-plane intersection. */
    private static double[] worldPoint(MapViewport.View view, double x, double y) {
        double u = view.centerU() + (x - view.x() - view.width() / 2.0) / view.zoom();
        double v = view.centerV() + (y - view.y() - view.height() / 2.0) / view.zoom();
        double z = Projection.zAt(v, GROUND_Y);
        return new double[] {Projection.facing().worldX(u, z), Projection.facing().worldZ(u, z)};
    }

    /** Take an existing vertex for dragging before MapScreen tries to pan. */
    public boolean press(MapViewport.View view, double x, double y, int button) {
        draggedVertex = -1;
        if (!active || button != InputConstants.MOUSE_BUTTON_LEFT || draft == null) return false;
        draggedVertex = vertexAt(view, x, y);
        return draggedVertex >= 0;
    }

    private int vertexAt(MapViewport.View view, double x, double y) {
        for (int i = 0; i < draft.vertices.length; i += 2) {
            float px = view.screenX(Projection.u(draft.vertices[i], draft.vertices[i + 1]));
            float py = view.screenY(Projection.v(draft.vertices[i], GROUND_Y, draft.vertices[i + 1]));
            if (Math.abs(px - x) <= HANDLE_RADIUS && Math.abs(py - y) <= HANDLE_RADIUS) {
                return i / 2;
            }
        }
        return -1;
    }

    public boolean drag(MapViewport.View view, double x, double y) {
        if (!active || draggedVertex < 0 || draft == null) return false;
        double[] world = worldPoint(view, x, y);
        draft.vertices[draggedVertex * 2] = world[0];
        draft.vertices[draggedVertex * 2 + 1] = world[1];
        return true;
    }

    /**
     * Add Point appends on a new frontier and inserts on an existing frontier.
     * A click outside Add Point mode selects the server's region under the cursor.
     */
    public boolean release(MapViewport.View view, String dimension, double x, double y, int button, boolean moved) {
        if (!active || button != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (draggedVertex >= 0) {
            if (moved) this.drag(view, x, y);
            draggedVertex = -1;
            return true;
        }
        if (moved) return false;
        double[] point = worldPoint(view, x, y);
        if (adding && draft != null) {
            if (draft.vertices.length / 2 >= Frontier.MAX_VERTICES) return true;
            if (newDraft) {
                insertPoint(draft.vertices.length / 2, point[0], point[1]);
            } else {
                EdgePoint edge = insertionAt(view, x, y);
                if (edge != null) insertPoint(edge.insertIndex(), edge.x(), edge.z());
            }
            return true;
        }
        draft = FrontierClient.at(dimension, point[0], point[1]);
        newDraft = false;
        adding = false;
        return true;
    }

    public @Nullable Frontier frontierAt(MapViewport.View view, String dimension, double x, double y) {
        double[] point = worldPoint(view, x, y);
        if (draft != null && draft.dimension.equals(dimension) && draft.contains(point[0], point[1])) return draft.copy();
        return FrontierClient.at(dimension, point[0], point[1]);
    }

    public void updateAppearance(Frontier frontier) {
        if (draft == null || !draft.id.equals(frontier.id)) return;
        draft.name = frontier.name;
        draft.color = frontier.color;
        draft.banner = frontier.banner;
    }

    public void select(Frontier frontier) {
        draft = frontier.copy();
        adding = false;
        newDraft = false;
        draggedVertex = -1;
    }

    private void insertPoint(int index, double x, double z) {
        double[] vertices = draft.vertices;
        int offset = index * 2;
        double[] next = Arrays.copyOf(vertices, vertices.length + 2);
        System.arraycopy(vertices, offset, next, offset + 2, vertices.length - offset);
        next[offset] = x;
        next[offset + 1] = z;
        draft.vertices = next;
    }

    private @Nullable EdgePoint insertionAt(MapViewport.View view, double x, double y) {
        if (!inserting() || draft == null || draft.vertices.length / 2 >= Frontier.MAX_VERTICES
            || vertexAt(view, x, y) >= 0) return null;
        return FrontierProjection.edgeAt(draft, view, x, y, HANDLE_RADIUS);
    }

    public boolean validDraft() {
        return draft != null && draft.valid();
    }

    public boolean saved() {
        if (!active || !validDraft()) return false;
        // Keep the in-progress polygon until the server broadcasts its
        // authoritative update. A stale revision or denied write must not
        // silently discard the operator's unsaved vertices.
        return FrontierClient.put(draft);
    }

    public boolean delete() {
        if (!active || draft == null) return false;
        Frontier original = FrontierClient.all().stream()
            .filter(f -> f.id.equals(draft.id)).findFirst().orElse(null);
        if (original == null) {
            cancel();
            return true;
        }
        if (FrontierClient.remove(draft.id)) {
            cancel();
            return true;
        }
        return false;
    }

    /** Preview the unsaved polygon and draggable vertex nodes over the real map. */
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, double mouseX, double mouseY, boolean preview) {
        if (!active || draft == null) return;
        int count = draft.vertices.length / 2;
        if (count >= 2) {
            List<double[]> points = new ArrayList<>();
            for (int i = 0; i < draft.vertices.length; i += 2) {
                double x = draft.vertices[i], z = draft.vertices[i + 1];
                points.add(new double[] {Projection.u(x, z), Projection.v(x, GROUND_Y, z)});
            }
            double[] flattened = new double[points.size() * 2];
            for (int i = 0; i < points.size(); i++) {
                flattened[i * 2] = points.get(i)[0];
                flattened[i * 2 + 1] = points.get(i)[1];
            }
            Drawing drawing = new Drawing(count >= 3 ? Drawing.Kind.AREA : Drawing.Kind.STROKE,
                draft.dimension, draft.color, flattened, "");
            overlay.draw(graphics, Minecraft.getInstance().font, view, List.of(drawing), null, null, false);
        }
        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        for (int i = 0; i < draft.vertices.length; i += 2) {
            int x = Math.round(view.screenX(Projection.u(draft.vertices[i], draft.vertices[i + 1])));
            int y = Math.round(view.screenY(Projection.v(draft.vertices[i], GROUND_Y, draft.vertices[i + 1])));
            graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF101820);
            graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFF6CFFCB);
        }
        EdgePoint edge = preview && draggedVertex < 0 ? insertionAt(view, mouseX, mouseY) : null;
        if (edge != null) {
            int x = Math.round(edge.screenX());
            int y = Math.round(edge.screenY());
            graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF101820);
            graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
        }
        graphics.disableScissor();
    }
}
