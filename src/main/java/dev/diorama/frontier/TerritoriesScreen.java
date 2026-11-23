package dev.diorama.frontier;

import dev.diorama.Claims;
import dev.diorama.MapScreen;
import dev.diorama.ServerRules;
import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Searchable directory of global frontiers and the OPAC regions known to this client. */
public final class TerritoriesScreen extends Screen {
    private record Entry(String name, String dimension, int color, double x, double z, @Nullable FrontierBanner banner, boolean claim) {}
    private final MapScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private final List<Button> rows = new ArrayList<>();
    private EditBox search;
    private Button kind;
    private Button previous;
    private Button next;
    private int filter;
    private int page;

    public TerritoriesScreen(MapScreen parent) {
        super(Component.translatable("diorama.territories.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String query = this.search == null ? "" : this.search.getValue();
        this.entries.clear();
        for (Frontier frontier : FrontierClient.all()) {
            if (!frontier.visible || ServerRules.mapOff(frontier.dimension)) continue;
            LabelAnchor anchor = LabelAnchor.inside(frontier.vertices);
            this.entries.add(new Entry(frontier.name, frontier.dimension, frontier.color, anchor.x(), anchor.z(), frontier.banner, false));
        }
        if (this.minecraft.getConnection() != null) {
            for (var dimension : this.minecraft.getConnection().levels()) {
                String id = dimension.identifier().toString();
                if (ServerRules.mapOff(id)) continue;
                for (ClaimTerritories.Region region : ClaimTerritories.regions(Claims.directory(dimension))) {
                    this.entries.add(new Entry(region.name(), id, region.color(), region.x(), region.z(), null, true));
                }
            }
        }
        this.entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::dimension).thenComparingDouble(Entry::x).thenComparingDouble(Entry::z));

        int width = Math.min(368, this.width - 20), left = (this.width - width) / 2;
        this.search = new EditBox(this.font, left, 28, width - 110, 20, Component.translatable("diorama.territories.search"));
        this.search.setHint(Component.translatable("diorama.territories.search"));
        this.search.setMaxLength(80);
        this.search.setValue(query);
        this.search.setResponder(ignored -> this.refilter());
        this.addRenderableWidget(this.search);
        this.kind = this.addRenderableWidget(Button.builder(this.filterName(), b -> {
            this.filter = (this.filter + 1) % 3;
            this.kind.setMessage(this.filterName());
            this.refilter();
        }).bounds(left + width - 104, 28, 104, 20).build());
        this.rows.clear();
        int count = Math.max(1, (this.height - 88) / 24);
        for (int i = 0; i < count; i++) {
            int row = i;
            this.rows.add(this.addRenderableWidget(Button.builder(Component.empty(), b -> this.open(row))
                .bounds(left + 30, 54 + i * 24, width - 30, 22).build()));
        }
        int footer = this.height - 26;
        this.previous = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.turn(-1))
            .bounds(left, footer, 24, 20).build());
        this.next = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.turn(1))
            .bounds(left + 62, footer, 24, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
            .bounds(left + width - 90, footer, 90, 20).build());
        this.refilter();
        this.setInitialFocus(this.search);
    }

    private Component filterName() {
        return Component.translatable(switch (this.filter) {
            case 1 -> "diorama.territories.frontiers";
            case 2 -> "diorama.territories.claims";
            default -> "diorama.territories.all";
        });
    }

    private void refilter() {
        String query = this.search.getValue().strip().toLowerCase(Locale.ROOT);
        this.filtered.clear();
        for (Entry entry : this.entries) {
            if ((this.filter == 1 && entry.claim) || (this.filter == 2 && !entry.claim)) continue;
            if (entry.name.toLowerCase(Locale.ROOT).contains(query)
                || Waypoints.dimensionName(entry.dimension).getString().toLowerCase(Locale.ROOT).contains(query)) this.filtered.add(entry);
        }
        this.page = 0;
        this.updateRows();
    }

    private void turn(int delta) {
        if (this.page + delta < 0 || (this.page + delta) * this.rows.size() >= this.filtered.size()) return;
        this.page += delta;
        this.updateRows();
    }

    private void updateRows() {
        int offset = this.page * this.rows.size();
        for (int i = 0; i < this.rows.size(); i++) {
            Button row = this.rows.get(i);
            row.visible = offset + i < this.filtered.size();
            if (row.visible) {
                Entry entry = this.filtered.get(offset + i);
                row.setMessage(Component.translatable("diorama.territories.entry", entry.name, Waypoints.dimensionName(entry.dimension)));
            }
        }
        this.previous.active = this.page > 0;
        this.next.active = offset + this.rows.size() < this.filtered.size();
    }

    private void open(int row) {
        Entry entry = this.filtered.get(this.page * this.rows.size() + row);
        if (this.parent.showTerritory(entry.dimension, entry.x, entry.z)) this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        //? if <1.21 {
        /*this.renderBackground(graphics);
        *///?}
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 13, 0xFFFFFFFF);
        int offset = this.page * this.rows.size();
        for (int i = 0; i < this.rows.size() && offset + i < this.filtered.size(); i++) {
            Button row = this.rows.get(i);
            Entry entry = this.filtered.get(offset + i);
            int x = row.getX() - 26, y = row.getY() + 7;
            graphics.fill(x, y, x + 8, y + 8, 0xFF000000 | entry.color);
            FrontierBannerItems.draw(graphics, FrontierBannerItems.stack(entry.banner), x + 10, y - 4, 16);
        }
        if (this.filtered.isEmpty()) graphics.centeredText(this.font, Component.translatable("diorama.territories.empty"),
            this.width / 2, 65, 0xFFCCCCCC);
        graphics.centeredText(this.font, Component.literal(Integer.toString(this.page + 1)), this.previous.getX() + 43,
            this.previous.getY() + 6, 0xFFFFFFFF);
    }

    @Override public void onClose() { this.minecraft.gui.setScreen(this.parent); }
    @Override public boolean isPauseScreen() { return false; }
}
