package dev.diorama.frontier;

import dev.diorama.render.MapViewport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Upright, centred map names with the territory colour and a readable shadow. */
final class TerritoryLabels {
    private TerritoryLabels() {}

    static void draw(GuiGraphicsExtractor graphics, Font font, MapViewport.View view, String name, int color,
                     double u, double v, double clearance, ItemStack banner) {
        if (name.isBlank()) return;
        float x = view.screenX(u), y = view.screenY(v);
        float room = (float) (clearance * view.zoom());
        if (room < 4 || x < view.x() || x > view.x() + view.width() || y < view.y() || y > view.y() + view.height()) return;
        String first = name, second = "";
        float textWidth = font.width(name);
        int split = name.lastIndexOf(' ', name.length() / 2);
        if (split < 0) split = name.indexOf(' ', name.length() / 2);
        if (split > 0 && textWidth > room * 1.55f) {
            first = name.substring(0, split);
            second = name.substring(split + 1);
            textWidth = Math.max(font.width(first), font.width(second));
        }
        int lines = second.isEmpty() ? 1 : 2;
        int flag = banner.isEmpty() ? 0 : 16;
        int height = lines * 10 + flag;
        float extent = (float) Math.hypot(Math.max(textWidth, flag) / 2 + 2, height / 2.0 + 1);
        float scale = Math.min(1.15f, room / extent);
        if (scale < 0.72f && flag > 0) {
            flag = 0;
            height = lines * 10;
            scale = Math.min(1.15f, room / (float) Math.hypot(textWidth / 2 + 2, height / 2.0 + 1));
        }
        if (scale < 0.72f) return;
        graphics.pose().pushMatrix();
        graphics.pose().translate(Math.round(x), Math.round(y));
        graphics.pose().scale(scale);
        int top = -height / 2;
        if (flag > 0) FrontierBannerItems.draw(graphics, banner, -8, top - 1, 16);
        int tint = readable(color);
        graphics.text(font, first, -font.width(first) / 2, top + flag, tint, true);
        if (lines > 1) graphics.text(font, second, -font.width(second) / 2, top + flag + 10, tint, true);
        graphics.pose().popMatrix();
    }

    private static int readable(int color) {
        int r = ((color >> 16 & 255) * 2 + 255) / 3;
        int g = ((color >> 8 & 255) * 2 + 255) / 3;
        int b = ((color & 255) * 2 + 255) / 3;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
