package dev.diorama.frontier;

import dev.diorama.drawing.Drawing;
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

    public FrontierEditScreen(Screen parent, Frontier frontier, @Nullable Drawing sourceDrawing) {
        super(Component.literal(sourceDrawing == null ? "Edit frontier" : "Create frontier"));
        this.parent = parent;
        this.frontier = frontier.copy();
        this.sourceDrawing = sourceDrawing;
    }

    private int left() {
        return this.width / 2 - 105;
    }

    private int top() {
        return this.height / 2 - 59;
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
        this.hex.setResponder(value -> this.validate());
        this.addRenderableWidget(this.hex);

        this.save = this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> this.save())
            .bounds(left, top + 101, 102, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
            .bounds(left + 108, top + 101, 102, 20).build());
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
        if (!FrontierClient.put(proposed, this.sourceDrawing)) {
            this.failure = Component.literal("Could not submit frontier to server");
            return;
        }
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
        if (this.failure != null) {
            graphics.text(this.font, this.failure, left, top + 84, 0xFFFF7777, false);
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
