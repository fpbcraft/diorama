package dev.diorama.frontier;

import dev.diorama.drawing.Drawing;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Name and colour a new or existing local frontier. On first save, an area drawing
 * can be converted into a frontier without leaving a duplicate area behind.
 */
public final class FrontierEditScreen extends Screen {
    private final Screen parent;
    private final Frontier frontier;
    private final @Nullable Drawing sourceDrawing;
    private @Nullable EditBox name;
    private @Nullable EditBox hex;
    private @Nullable Button save;
    private @Nullable Component failure;
    private boolean bannerEdited;
    private @Nullable Button removeBanner;
    private final Consumer<Frontier> appearanceSaved;
    private final ChannelSlider[] channels = new ChannelSlider[3];


    public FrontierEditScreen(Screen parent, Frontier frontier, @Nullable Drawing sourceDrawing) {
        this(parent, frontier, sourceDrawing, ignored -> {});
    }

    public FrontierEditScreen(Screen parent, Frontier frontier, @Nullable Drawing sourceDrawing, Consumer<Frontier> appearanceSaved) {
        super(Component.literal(sourceDrawing == null ? "Edit frontier" : "Create frontier"));
        this.parent = parent;
        this.frontier = frontier.copy();
        this.sourceDrawing = sourceDrawing;
        this.appearanceSaved = appearanceSaved;
    }

    private int left() {
        return this.width / 2 - 105;
    }

    private int top() {
        return this.height / 2 - 94;
    }

    @Override
    protected void init() {
        int left = this.left();
        int top = this.top();
        String draftName = this.name == null ? this.frontier.name : this.name.getValue();
        String draftColor = this.hex == null
            ? String.format("#%06X", this.frontier.color & 0xFFFFFF) : this.hex.getValue();

        this.name = new EditBox(this.font, left, top + 18, 210, 20, Component.literal("Frontier name"));
        this.name.setMaxLength(48);
        this.name.setValue(draftName);
        this.name.setResponder(value -> this.validate());
        this.addRenderableWidget(this.name);

        this.hex = new EditBox(this.font, left, top + 59, 95, 20, Component.literal("Hex colour"));
        this.hex.setMaxLength(7);
        this.hex.setValue(draftColor);
        this.hex.setResponder(value -> {
            Integer color = parseHex(value);
            if (color != null) for (ChannelSlider slider : this.channels) if (slider != null) slider.setColor(color);
            this.validate();
        });
        this.addRenderableWidget(this.hex);

        for (int i = 0; i < this.channels.length; i++) {
            ChannelSlider slider = new ChannelSlider(left + i * 71, top + 84, i);
            this.channels[i] = this.addRenderableWidget(slider);
        }

        Button assignBanner = this.addRenderableWidget(Button.builder(Component.translatable("diorama.frontier.banner.assign"), button -> {
            if (this.minecraft.player == null) return;
            FrontierBanner held = FrontierBannerItems.read(this.minecraft.player.getMainHandItem());
            if (held == null) return;
            this.frontier.banner = held;
            this.bannerEdited = true;
            this.removeBanner.active = true;
        }).bounds(left, top + 111, 113, 20).build());
        assignBanner.active = this.minecraft.player != null
            && FrontierBannerItems.read(this.minecraft.player.getMainHandItem()) != null;
        this.removeBanner = this.addRenderableWidget(Button.builder(Component.translatable("diorama.frontier.banner.remove"), button -> {
            this.frontier.banner = null;
            this.bannerEdited = true;
            button.active = false;
        }).bounds(left + 118, top + 111, 92, 20).build());
        this.removeBanner.active = this.frontier.banner != null;
        this.save = this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> this.save())
            .bounds(left, top + 162, 102, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
            .bounds(left + 108, top + 162, 102, 20).build());
        this.validate();
        this.setInitialFocus(this.name);
    }

    private static @Nullable Integer parseHex(String input) {
        String value = input.startsWith("#") ? input.substring(1) : input;
        if (value.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void validate() {
        String text = this.name == null ? "" : this.name.getValue().trim();
        Integer color = this.hex == null ? null : parseHex(this.hex.getValue());
        this.failure = text.isBlank() ? Component.literal("Enter a frontier name")
            : color == null ? Component.literal("Enter a 6-digit hex colour") : null;
        if (this.save != null) {
            this.save.active = this.failure == null;
        }
    }

    private void save() {
        if (this.name == null || this.hex == null) {
            return;
        }
        Integer color = parseHex(this.hex.getValue());
        if (color == null) {
            return;
        }
        if (!FrontierClient.canEdit()) {
            this.failure = Component.literal("Only server operators can edit global frontiers");
            return;
        }
        Frontier proposed = this.frontier.copy();
        proposed.name = this.name.getValue().trim();
        proposed.color = color;
        if (!FrontierClient.put(proposed, this.sourceDrawing, this.bannerEdited)) {
            this.failure = Component.literal("Could not submit frontier to server");
            return;
        }
        this.appearanceSaved.accept(proposed);
        // The authoritative update comes back from the server. Only then is a
        // converted area drawing removed (FrontierClient), preserving it on failure.
        this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21 {
        /*this.renderBackground(graphics);
        *///?}
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.left();
        int top = this.top();
        graphics.centeredText(this.font, this.title, this.width / 2, top - 22, 0xFFFFFFFF);
        graphics.text(this.font, "Name", left, top + 6, 0xFFCCCCCC, false);
        graphics.text(this.font, "Colour", left, top + 47, 0xFFCCCCCC, false);
        Integer color = this.hex == null ? null : parseHex(this.hex.getValue());
        if (color != null) graphics.fill(left + 102, top + 59, left + 122, top + 79, 0xFF000000 | color);
        FrontierBannerItems.draw(graphics, FrontierBannerItems.stack(this.frontier.banner), left + 165, top + 51, 32);
        if (this.failure != null) {
            graphics.text(this.font, this.failure, left, top + 139, 0xFFFF7777, false);
        } else if (this.frontier.banner == null) {
            graphics.text(this.font, Component.translatable("diorama.frontier.banner.held"), left, top + 139, 0xFFCCCCCC, false);
        }
    }

    private final class ChannelSlider extends AbstractSliderButton {
        private final int channel;
        private final int shift;

        ChannelSlider(int x, int y, int channel) {
            super(x, y, 68, 18, Component.empty(), 0);
            this.channel = channel;
            this.shift = (2 - channel) * 8;
            Integer color = parseHex(FrontierEditScreen.this.hex.getValue());
            this.setColor(color == null ? FrontierEditScreen.this.frontier.color : color);
        }

        void setColor(int color) {
            this.value = (color >> this.shift & 255) / 255.0;
            this.updateMessage();
        }

        private int channelValue() {
            return (int) Math.round(this.value * 255);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("RGB".charAt(this.channel) + " " + this.channelValue()));
        }

        @Override
        protected void applyValue() {
            Integer parsed = parseHex(FrontierEditScreen.this.hex.getValue());
            int previous = parsed == null ? FrontierEditScreen.this.frontier.color : parsed;
            int color = (previous & ~(255 << this.shift)) | (this.channelValue() << this.shift);
            FrontierEditScreen.this.hex.setValue(String.format("#%06X", color));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
