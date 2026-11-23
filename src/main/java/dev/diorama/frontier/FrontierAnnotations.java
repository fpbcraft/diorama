package dev.diorama.frontier;

import dev.diorama.ClaimProjection;
import dev.diorama.Claims;
import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.Settings;
import dev.diorama.api.MapAnnotations;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Native frontier overlay for both the fullscreen map and minimap. */
public final class FrontierAnnotations implements MapAnnotations.Source {
    private static final FrontierAnnotations INSTANCE = new FrontierAnnotations();
    private final DrawingOverlay drawings = new DrawingOverlay();
    private final Map<UUID, Placement> placements = new HashMap<>();
    private Object level;
    private record Placement(double[] vertices, Facing facing, double pitch, LabelAnchor anchor) {}

    private FrontierAnnotations() {
    }

    public static void register() {
        MapAnnotations.register(INSTANCE);
    }

    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        Settings settings = Settings.get();
        Minecraft mc = Minecraft.getInstance();
        if (this.level != mc.level) {
            this.placements.clear();
            this.level = mc.level;
        }
        Font font = mc.font;
        boolean visible = settings.frontiers != Settings.FrontiersShown.OFF
            && (fullscreen || settings.frontiers == Settings.FrontiersShown.ALWAYS);
        List<Frontier> frontiers = visible ? FrontierClient.in(dimension) : List.of();
        List<Drawing> shapes = new ArrayList<>(frontiers.size());
        for (Frontier frontier : frontiers) {
            if (frontier.visible) shapes.add(FrontierProjection.asDrawing(frontier));
        }
        this.drawings.draw(graphics, font, view, shapes, null, null, false);
        if (!fullscreen) return;

        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        if (mc.level != null && view.layer() == TileKey.SURFACE && settings.claims != Settings.ClaimsShown.OFF
            && dimension.equals(mc.level.dimension().identifier().toString())) {
            double y = ClaimProjection.height(view.layer());
            for (ClaimTerritories.Region region : ClaimTerritories.regions(Claims.get())) {
                TerritoryLabels.draw(graphics, font, view, region.name(), region.color(),
                    Projection.u(region.x(), region.z()), Projection.v(region.x(), y, region.z()),
                    region.clearance() * Projection.sin(), ItemStack.EMPTY);
            }
        }
        for (Frontier frontier : frontiers) {
            if (!frontier.visible) continue;
            LabelAnchor anchor = this.placement(frontier);
            TerritoryLabels.draw(graphics, font, view, frontier.name, frontier.color, anchor.x(), anchor.z(),
                anchor.clearance(), FrontierBannerItems.stack(frontier.banner));
        }
        graphics.disableScissor();
        if (this.placements.size() > FrontierStore.MAX_FRONTIERS) this.placements.clear();
    }

    private LabelAnchor placement(Frontier frontier) {
        Placement old = this.placements.get(frontier.id);
        if (old != null && old.facing == Projection.facing() && old.pitch == Projection.sin()
            && Arrays.equals(old.vertices, frontier.vertices)) return old.anchor;
        LabelAnchor anchor = LabelAnchor.inside(FrontierProjection.asDrawing(frontier).shown());
        this.placements.put(frontier.id, new Placement(frontier.vertices, Projection.facing(), Projection.sin(), anchor));
        return anchor;
    }
}
