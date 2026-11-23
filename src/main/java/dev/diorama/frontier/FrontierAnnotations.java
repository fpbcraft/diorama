package dev.diorama.frontier;

import dev.diorama.Projection;
import dev.diorama.api.MapAnnotations;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Native frontier overlay for both the fullscreen map and minimap. */
public final class FrontierAnnotations implements MapAnnotations.Source {
    private static final FrontierAnnotations INSTANCE = new FrontierAnnotations();
    private final DrawingOverlay drawings = new DrawingOverlay();

    private FrontierAnnotations() {
    }

    public static void register() {
        MapAnnotations.register(INSTANCE);
    }

    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        List<Frontier> frontiers = FrontierClient.in(dimension);
        List<Drawing> shapes = new ArrayList<>(frontiers.size());
        for (Frontier frontier : frontiers) {
            if (frontier.visible) {
                shapes.add(FrontierProjection.asDrawing(frontier));
            }
        }
        Font font = Minecraft.getInstance().font;
        this.drawings.draw(graphics, font, view, shapes, null, null, false);
        if (!fullscreen) {
            return;
        }
        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        for (Frontier frontier : frontiers) {
            if (!frontier.visible) {
                continue;
            }
            double[] center = frontier.center();
            float x = view.screenX(Projection.u(center[0], center[1]));
            float y = view.screenY(Projection.v(center[0], 64.0, center[1]));
            int width = font.width(frontier.name);
            if (x - width / 2 < view.x() || x + width / 2 > view.x() + view.width()
                || y < view.y() || y + 10 > view.y() + view.height()) {
                continue;
            }
            int px = Math.round(x) - width / 2;
            int py = Math.round(y);
            graphics.fill(px - 3, py - 2, px + width + 3, py + 10, 0xAA101820);
            graphics.text(font, frontier.name, px, py, 0xFFFFFFFF, true);
        }
        graphics.disableScissor();
    }
}
