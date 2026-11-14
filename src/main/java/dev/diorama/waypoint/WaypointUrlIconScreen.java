package dev.diorama.waypoint;

import dev.diorama.render.UrlWaypointIcons;
import dev.diorama.render.WaypointIcons;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** A URL is contacted only after the user presses Download and use. */
final class WaypointUrlIconScreen extends Screen {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Diorama icon download");
        thread.setDaemon(true);
        return thread;
    });
    private final Screen parent;
    private final Consumer<String> picked;
    private EditBox url;
    private Button download;
    private boolean downloading;
    private Component status = Component.translatable("diorama.waypoint.icon_url_hint");

    WaypointUrlIconScreen(Screen parent, Consumer<String> picked) {
        super(Component.translatable("diorama.waypoint.icon_url"));
        this.parent = parent;
        this.picked = picked;
    }

    @Override
    protected void init() {
        String typed = this.url == null ? "" : this.url.getValue();
        int left = this.width / 2 - 140, top = this.height / 2 - 42;
        this.url = this.addRenderableWidget(new EditBox(this.font, left, top, 280, 20, Component.literal("HTTPS PNG URL")));
        this.url.setMaxLength(2048);
        this.url.setValue(typed);
        this.url.active = !this.downloading;
        this.download = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.icon_url_download"), b -> this.download())
            .bounds(left, top + 76, 168, 20).build());
        this.download.active = !this.downloading;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
            .bounds(left + 176, top + 76, 104, 20).build());
        this.setInitialFocus(this.url);
    }

    private void download() {
        if (!BUSY.compareAndSet(false, true)) {
            this.status = Component.translatable("diorama.waypoint.icon_url_busy");
            return;
        }
        String input = this.url.getValue();
        var folder = WaypointIcons.downloadFolder();
        var client = this.minecraft;
        this.downloading = true;
        this.url.active = false;
        this.download.active = false;
        this.status = Component.translatable("diorama.waypoint.icon_url_loading");
        CompletableFuture.runAsync(() -> {
            String id = null;
            String error = null;
            try { id = UrlWaypointIcons.download(input, folder); }
            catch (java.io.IOException | RuntimeException failure) { error = failure.getMessage(); }
            finally { BUSY.set(false); }
            String imported = id, problem = error;
            client.execute(() -> {
                this.downloading = false;
                if (client.gui.screen() != this) return;
                if (imported != null) {
                    WaypointIcons.reload();
                    this.picked.accept(imported);
                } else {
                    this.url.active = true;
                    this.download.active = true;
                    this.status = Component.literal(problem == null ? "Couldn't download this image" : problem);
                }
            });
        }, DOWNLOADS);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int left = this.width / 2 - 140, top = this.height / 2 - 42;
        graphics.centeredText(this.font, this.title, this.width / 2, top - 22, 0xFFFFFFFF);
        int y = top + 30;
        for (var line : this.font.split(this.status, 280)) {
            graphics.text(this.font, line, left, y, 0xFFAEBCC8, false);
            y += 11;
        }
    }

    @Override public void onClose() { this.minecraft.gui.setScreen(this.parent); }
    @Override public boolean isPauseScreen() { return false; }
}
