package dev.diorama;

import dev.diorama.api.MapAnnotations;
import dev.diorama.bake.BlockLooks;
import dev.diorama.compat.OpacClaims;
import dev.diorama.bake.MapPick;
import dev.diorama.drawing.DrawMode;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.frontier.Frontier;
import dev.diorama.frontier.TerritoriesScreen;
import dev.diorama.frontier.FrontierEditScreen;
import dev.diorama.frontier.FrontierClient;
import dev.diorama.frontier.FrontierProjection;
import dev.diorama.frontier.FrontierMapEditor;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileSpots;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.Markers;
import dev.diorama.render.Pins;
import dev.diorama.render.Radar;
import dev.diorama.render.SavedChunkImport;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * click a waypoint to edit it, right-click for a menu of what can be done at a spot or waypoint.
 * Each key also has a control on screen: Surface and Caves tabs, a Waypoints button, zoom and
 * recenter buttons, and on the cave map a depth gauge whose band moves the slice.
 *
 * <p>The dimension button, a square at the bottom left, shows the saved map of another dimension,
 * without going there, and each click is the next dimension, as on Xaero's World Map. Only sky
 * views are saved, so that's what shows, without the live things the player's own level gives the
 * map: caves, the radar, claims, overlays and distances. Waypoints, drawings, the right-click menu
 * and teleporting are that dimension's.
 */
public final class MapScreen extends Screen {
	/** How many Overworld blocks a block of the Nether stands for. */
	private static final double NETHER_SCALE = 8;
	/**
	 * How far out the saved surface map zooms, in screen pixels per block: 512 blocks to a pixel, as
	 * Xaero's World Map with its Unlimited Fullscreen Zoom-out on. In screen pixels, not the GUI's,
	 * so the map reaches as far at any GUI scale. Overview tiles go up to a level that draws this
	 * with a texture pixel a screen pixel ({@link TileKey#MAX_LOD}), so it costs what any zoom does.
	 */
	private static final double MIN_SCREEN_ZOOM = 1.0 / 512;
	/** How long the open map keeps the layer on screen while the one the player moved into bakes; as the minimap. */
	private static final long FOLLOW_HOLD_MS = 3000;

	private static final double MAX_ZOOM = 16;

	/** The dimension button's left edge, under the draw tools. */
	private static final int DIMENSION_BUTTON_X = 4;
	/** Height of the top bar, which holds the tabs and buttons. */
	private static final int BAR = 16;
	private static final int BOTTOM_BAR = 14;
	private static final int SIDE_BUTTON = 20;
	private static final int SIDE_STEP = SIDE_BUTTON + 2;
	private static final int FRONTIER_TINT = 0xFF72E3BF;
	private static final int OPAC_TINT = 0xFFD0ADFF;
	private static final int DRAW_TINT = 0xFFFFCB73;
	private static final int EXPORT_TINT = 0xFF9CCFFF;
	private static final int NEUTRAL_TINT = 0xFFF3F5F7;
	private static final String[] FRONTIER_ICON = {"##....##.", "##...###.", ".#####...", ".#....#..", "#......#.", "##.....##", ".#.....##", "..#####..", "....###.."};
	private static final String[] FRONTIER_VISIBILITY_ICON = {"...###...", ".##...##.", "#...#...#", "#..###..#", "#...#...#", ".##...##.", "...###..."};
	private static final String[] CLAIM_ICON = {"###.###", "###.###", "###.###", ".......", "###.###", "###.###", "###.###"};
	private static final String[] CLAIM_EDIT_ICON = {"#########", "#..###..#", "#.#####.#", ".#######.", ".#######.", "..#####..", "...###...", "....#...."};
	private static final String[] TERRITORIES_ICON = {"..##.....", ".#..#....", ".#..#.###", "#....#..#", "#....#..#", ".#..#...#", "..##....#"};
	private static final String[] MENU_ICON = {"##.######", "##.......", ".........", "##.######", "##.......", ".........", "##.######", "##......."};
	private static final String[] CLAIM_TOOL_ICON = {".......", "##...##", "#.....#", "...#...", "..###..", "#..#..#", "##...##"};
	private static final String[] FORCELOAD_ICON = {"....###..", "...###...", "..###....", ".#######.", "....###..", "...###...", "..###...."};
	private static final String[] NEW_ICON = {"..#####..", ".#.....#.", "#...#...#", "#..###..#", "#...#...#", "#.......#", ".#.....#.", "..#####.."};
	private static final String[] ADD_POINT_ICON = {"........#", ".......#.", "......#..", "...###...", "...###...", "...###...", "..#......", ".#.......", "#........"};
	private static final String[] RENAME_ICON = {"#######", "#..#..#", "...#...", "...#...", "...#...", "..###.."};
	private static final String[] DELETE_ICON = {"..###..", "#######", ".......", ".#.#.#.", ".#.#.#.", ".#...#.", ".#####."};
	private static final String[] SAVE_ICON = {"#######", "#...#.#", "#######", "#.....#", "#.###.#", "#.###.#", "#######"};
	private static final String[] CANCEL_ICON = {"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};
	private static final Component HELP = Component.translatable("diorama.map.help");
	private static final String STATUS_GAP = "   ·   ";
	/** One press of a zoom button: two notches of the mouse wheel. */
	private static final int ZOOM_BUTTON_STEPS = 2;
	private static final Identifier TAB = Identifier.withDefaultNamespace("widget/tab");
	private static final Identifier TAB_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_highlighted");
	private static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
	private static final Identifier TAB_SELECTED_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
	//? if <1.21 {
	/*// 1.20.1's tab buttons draw all four looks from rows of one texture.
	private static final Identifier TAB_BUTTON = Identifier.withDefaultNamespace("textures/gui/tab_button.png");
	*///?}
	private static final Component[] TAB_NAMES = {Component.translatable("diorama.map.surface"), Component.translatable("diorama.map.caves")};

	private final MapViewport viewport = new MapViewport();
	private double centerU;
	private double centerV;
	private double zoom = 2.0;
	private int layer;
	/** Set once the player picks a layer with the map open: auto caves stops following them then. */
	private boolean layerPicked;
	/**
	 * When following the player moved them onto a layer whose tiles aren't there yet, since when
	 * the old one has stayed on screen; -1 when not waiting. See {@link #followLayer}.
	 */
	private long followHoldStart = -1;
	private boolean following = true;
	private List<Radar.Blip> blips = List.of();
	/** The map's own tooltip at the mouse this frame: a mob, player, waypoint or spot. Null with none, or over a control. */
	private @Nullable List<FormattedCharSequence> tooltipLines;
	private List<WaypointOverlay.Pin> pins = List.of();
	private MapViewport.@Nullable View lastView;
	/** Where the current mouse press started, to tell a click from a drag. */
	private double pressX;
	private double pressY;
	private boolean dragged;
	/** Shift-drag claims a rectangular area through OPAC; Shift-right-drag unclaims it. */
	private boolean claimSelecting;
	private boolean claimRemoving;
	/** True while selecting chunks to mark/unmark for forceloading instead of claiming. */
	private boolean claimForceloadSelecting;
	/** Short-lived feedback drawn in this map instead of spamming Minecraft chat. */
	private @Nullable Component claimFeedback;
	private boolean claimFeedbackError;
	private boolean claimFeedbackPending;
	private long claimFeedbackUntil;
	private double claimEndX;
	private double claimEndY;
	private static final int MAX_CLAIM_SELECTION_CHUNKS = 4096;
	/** The right-click menu, while it's open. */
	private @Nullable MapMenu menu;
	/** Whether the current mouse press started on the menu. */
	private boolean pressOnMenu;
	/** The button of the latest press, until it's released. */
	private int pressButton = -1;
	/** Whether the latest press went to a control (a button, a tab, the gauge), so it can't pan. */
	private boolean pressOnControl;
	private final DepthGauge gauge = new DepthGauge();
	private Button export;
	private Button zoomIn;
	private Button zoomOut;
	private Button recenter;
	private Button sliceUp;
	private Button sliceDown;
	private Button dimensionButton;
	/** Whether there's a dimension to switch to, worked out when the screen is built. */
	private boolean severalDimensions;
	private Button mapsButton;
	private Button waypointsButton;
	private Button settingsButton;
	/** Quick visibility control for all claim providers; the last non-off setting is remembered. */
	private Button claimsButton;
	/** Global frontiers are server-owned; this toggle only appears if the server supports them. */
	private Button frontierButton;
	private Button frontierVisibilityButton;
	private Button territoriesButton;
	/** Separate OPAC chunk editing mode; OPAC itself authorizes every claim request. */
	private Button opacEditButton;
	private Button opacMenuButton;
	private Button opacToolButton;
	private final Map<Button, SideControl> sideControls = new LinkedHashMap<>();
	private int sideControlsLeft;
	private int sideControlsTop;
	private int contextLeft;
	private int contextTop;
	private int contextRight;
	private int contextBottom;
	private boolean opacEditing;
	/** When true, OPAC edit gestures mark/unmark claims for forceloading. */
	private boolean opacForceloadTool;
	private Button frontierNewButton;
	private Button frontierAddButton;
	private Button frontierRenameButton;
	private Button frontierDeleteButton;
	private Button frontierSaveButton;
	private Button frontierCancelButton;
	private final FrontierMapEditor frontierEditor = new FrontierMapEditor();
	/** Turns draw mode on and off; it sits above the zoom buttons. */
	private Button drawButton;
	private final DrawMode draw = new DrawMode();
	private final WorldBorderOverlay worldBorder = new WorldBorderOverlay();
	/** The tabs' left edges and widths as drawn last frame, and where the status text starts. */
	private final int[] tabX = new int[2];
	private final int[] tabWidth = new int[2];
	private int tabsEnd;
	/** The block under the mouse, kept a few frames while the map point under it stays in one block. */
	private int spotU = Integer.MIN_VALUE;
	private int spotV;
	private int spotLayer;
	private int spotAge;
	private @Nullable BlockPos spot;
	/** The latest look-up of {@link #spot} from a saved tile; an older one's answer is dropped. */
	private @Nullable Object spotRequest;
	/** The dimension whose saved map is shown, or null for the one the player is in. */
	private @Nullable String otherDimension;
	/** That map while it's open. It's closed with the screen, and with each screen opened over this one. */
	private @Nullable OtherMap otherMap;
	private Button facingButton;

	/** A dimension's saved map, and what it was opened for: a change to either opens it again. */
	private record OtherMap(Path folder, MapStyle style, Facing facing, MapLevel map) {
	}

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		this.layer = this.layerOnOpening();
		this.refreshShared();
	}

	/** Every so long while the map is open, as the server saves every few minutes. */
	private static final int REFRESH_TICKS = 20 * 60;
	private int refreshIn = REFRESH_TICKS;

	/**
	 * On a server that shares its map, asks what others changed since: as the map opens, and every
	 * minute while it's open.
	 */
	private void refreshShared() {
		dev.diorama.render.SavedChunkImport fill = MapSession.get() == null ? null : MapSession.get().savedChunks();
		if (fill != null) {
			fill.refresh();
		}
	}

	/** Underground the map opens on the cave the player is in, unless the settings say surface only. */
	private int layerOnOpening() {
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		return session != null && viewer != null && Settings.get().autoCaves && ServerRules.caveMapsAllowed() ? session.autoLayer(viewer) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
		// Built for the old size, so a resize closes it. A press that opened another screen was
		// released there, so none is held on coming back.
		this.menu = null;
		this.gauge.cancel();
		this.pressButton = -1;
		this.pressOnMenu = false;
		this.pressOnControl = false;
		this.claimSelecting = false;
		this.claimRemoving = false;
		this.claimForceloadSelecting = false;
		Settings settings = Settings.get();
		this.dimensionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.nextDimension())
			.size(20, 20).createNarration(n -> this.dimensionName().copy()).build());
		this.severalDimensions = this.dimensions().size() > 1;
		// Any of them can be hidden; the ones shown sit together in the corner, Settings last.
		this.mapsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.map.maps"), b -> {
			MapsScreen maps = new MapsScreen(this);
			if (this.otherDimension != null) {
				maps.show(this.otherDimension, null);
			}
			this.minecraft.gui.setScreen(maps);
		}).size(64, BAR).build());
		this.mapsButton.visible = settings.mapMapsButton;
		this.mapsButton.active = Maps.get() != null;
		this.waypointsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.map.waypoints"), b -> this.openWaypoints()).size(64, BAR).build());
		this.waypointsButton.visible = settings.mapWaypointsButton;
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.settings.button"), b ->
			this.minecraft.gui.setScreen(new SettingsScreen(this))).size(64, BAR).build());
		this.settingsButton.visible = settings.mapSettingsButton;
		this.sideControls.clear();
		this.claimsButton = this.sideButton(() -> CLAIM_ICON, MapScreen::claimsToggleLabel,
			() -> Settings.get().claims != Settings.ClaimsShown.OFF, OPAC_TINT, b -> this.toggleClaims());
		this.opacMenuButton = this.sideButton(() -> MENU_ICON, () -> sideLabel("opac_menu"), () -> false, OPAC_TINT, b -> {
			this.cancelClaimSelection();
			this.menu = null;
			if (!OpacClaims.openMainMenu(this.minecraft, this)) {
				this.showClaimFeedback(Component.literal("Could not open the Open Parties and Claims menu"), true, false);
			}
		});
		this.opacEditButton = this.sideButton(() -> CLAIM_EDIT_ICON,
			() -> sideLabel(this.opacEditing ? "opac_done" : "opac_edit"), () -> this.opacEditing, OPAC_TINT, b -> {
			if (!OpacClaims.available(this.minecraft)) return;
			this.opacEditing = !this.opacEditing;
			this.opacForceloadTool = false;
			this.cancelClaimSelection();
			this.menu = null;
			if (this.opacEditing) {
				this.frontierEditor.stop();
				if (this.draw.active()) this.draw.stop();
			}
		});
		this.frontierButton = this.sideButton(() -> FRONTIER_ICON,
			() -> sideLabel(this.frontierEditor.active() ? "frontier_done" : "frontier_edit"), this.frontierEditor::active, FRONTIER_TINT, b -> {
			if (!FrontierClient.canEdit()) return;
			if (!this.frontierEditor.active() && this.draw.active()) this.draw.stop();
			if (!this.frontierEditor.active()) this.opacEditing = false;
			this.cancelClaimSelection();
			this.frontierEditor.toggle();
			this.menu = null;
		});
		this.frontierVisibilityButton = this.sideButton(() -> FRONTIER_VISIBILITY_ICON,
			() -> sideLabel(this.frontiersShown() ? "frontiers_on" : "frontiers_off"),
			this::frontiersShown, FRONTIER_TINT, b -> this.toggleFrontiers());
		this.territoriesButton = this.sideButton(() -> TERRITORIES_ICON, "territories",
			b -> this.minecraft.gui.setScreen(new TerritoriesScreen(this)));
		this.placeHeaderButtons();
		this.export = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.minecraft.gui.setScreen(new ExportScreen(this, this.centerU, this.centerV, this.zoom, this.layer, this.width, this.height));
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.export.title")).build());
		this.zoomIn = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoomButton(ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_in")).build());
		this.zoomOut = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoomButton(-ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_out")).build());
		this.recenter = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.following = true;
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.map.recenter")).build());
		this.facingButton = this.addRenderableWidget(Button.builder(facingLetter(), b -> this.turn(DrawMode.shiftDown() ? -1 : 1))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.facing." + Projection.facing().id())).build());
		this.sliceUp = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(DepthGauge.STEP, MapSession.viewer());
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_up")).build());
		this.sliceDown = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(-DepthGauge.STEP, MapSession.viewer());
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_down")).build());
		this.drawButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			if (!this.draw.active()) {
				this.frontierEditor.stop();
				this.opacEditing = false;
				this.cancelClaimSelection();
			}
			this.draw.toggle();
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.draw.button")).build());
		this.frontierNewButton = this.sideButton(() -> NEW_ICON, "frontier_new",
			b -> this.frontierEditor.newFrontier(this.dimension()));
		this.frontierAddButton = this.sideButton(() -> ADD_POINT_ICON,
			() -> sideLabel(this.frontierEditor.adding() ? "frontier_stop_points" : "frontier_add_point"),
			this.frontierEditor::adding, b -> this.frontierEditor.addPoint());
		this.frontierRenameButton = this.sideButton(() -> RENAME_ICON, "frontier_rename", b -> this.renameFrontierDraft());
		this.frontierDeleteButton = this.sideButton(() -> DELETE_ICON, "frontier_delete", b -> {
			if (this.frontierEditor.draft() != null) {
				this.menu = new MapMenu(List.of(
					MapMenu.Item.confirmed(Component.literal("Delete global frontier"),
						Component.translatable("diorama.menu.delete_confirm"), () -> this.frontierEditor.delete())),
					this.font, b.getX(), b.getY(), this.width, this.height);
			}
		});
		this.frontierSaveButton = this.sideButton(() -> SAVE_ICON, "frontier_save", b -> this.saveFrontierDraft());
		this.frontierCancelButton = this.sideButton(() -> CANCEL_ICON, "frontier_cancel", b -> this.frontierEditor.cancel());
		this.opacToolButton = this.sideButton(() -> this.opacForceloadTool ? FORCELOAD_ICON : CLAIM_TOOL_ICON,
			() -> sideLabel(this.opacForceloadTool ? "opac_tool_forceload" : "opac_tool_claims"),
			() -> this.opacForceloadTool, b -> {
				this.opacForceloadTool = !this.opacForceloadTool;
				this.cancelClaimSelection();
			});
		this.draw.init(this.font, this::addRenderableWidget, this::setFocused, (x, y) -> this.spotAt(x, y, false).thenApply(MapLevel.Spot::pos),
			(u, v) -> this.spotAtMap(u, v, false).thenApply(MapLevel.Spot::pos), this::dimension);
		this.placeControls();
	}

	private record SideControl(Supplier<String[]> icon, Supplier<Component> label, BooleanSupplier selected, int tint) {
	}

	private static Component sideLabel(String key) {
		return Component.translatable("diorama.map.tool." + key);
	}

	private Button sideButton(Supplier<String[]> icon, String label, Button.OnPress action) {
		return this.sideButton(icon, () -> sideLabel(label), () -> false, action);
	}

	private Button sideButton(Supplier<String[]> icon, Supplier<Component> label, BooleanSupplier selected, Button.OnPress action) {
		return this.sideButton(icon, label, selected, NEUTRAL_TINT, action);
	}

	private Button sideButton(Supplier<String[]> icon, Supplier<Component> label, BooleanSupplier selected, int tint, Button.OnPress action) {
		Button button = this.addRenderableWidget(Button.builder(Component.empty(), action).size(SIDE_BUTTON, SIDE_BUTTON)
			.createNarration(n -> label.get().copy()).build());
		this.sideControls.put(button, new SideControl(icon, label, selected, tint));
		return button;
	}

	private void placeHeaderButtons() {
		int right = this.width;
		for (Button button : List.of(this.settingsButton, this.waypointsButton, this.mapsButton)) {
			if (button.visible) {
				right -= button.getWidth();
				button.setPosition(right, 0);
				right -= 2;
			}
		}
	}

	private void updateSideControls() {
		this.claimsButton.visible = Claims.supported();
		this.claimsButton.active = this.otherDimension == null;
		this.opacMenuButton.visible = OpacClaims.installedOnClient();
		this.opacMenuButton.active = this.minecraft.level != null;
		this.opacEditButton.visible = this.opacMenuButton.visible;
		this.opacEditButton.active = OpacClaims.available(this.minecraft) && this.otherDimension == null;
		if (!this.opacEditButton.active && this.opacEditing) {
			this.opacEditing = false;
			this.opacForceloadTool = false;
			this.cancelClaimSelection();
		}
		this.opacToolButton.visible = this.opacEditing;
		this.frontierButton.visible = FrontierClient.available();
		this.frontierVisibilityButton.visible = this.frontierButton.visible;
		this.territoriesButton.visible = this.frontierButton.visible || this.opacMenuButton.visible;
		this.frontierButton.active = FrontierClient.canEdit();
		if (!this.frontierButton.active && this.frontierEditor.active()) {
			this.frontierEditor.stop();
			this.menu = null;
		}
		boolean show = this.frontierEditor.active();
		boolean draft = show && this.frontierEditor.hasDraft();
		this.frontierNewButton.visible = show;
		this.frontierAddButton.visible = draft;
		this.frontierRenameButton.visible = draft;
		this.frontierDeleteButton.visible = draft;
		this.frontierSaveButton.visible = draft;
		this.frontierCancelButton.visible = draft;
		this.frontierAddButton.active = this.frontierEditor.hasDraft();
		this.frontierRenameButton.active = this.frontierEditor.validDraft();
		this.frontierDeleteButton.active = this.frontierEditor.hasDraft();
		this.frontierSaveButton.active = this.frontierEditor.validDraft();
		this.frontierCancelButton.active = this.frontierEditor.hasDraft();
	}

	/** Keep the edit modes on the right, with the active editor's actions beside them. */
	private void placeSideControls(int x, int zoomY) {
		this.updateSideControls();
		this.sideControlsLeft = Math.min(x - SIDE_STEP, this.width - 56);
		this.sideControlsTop = zoomY;
		int bottom = zoomY - SIDE_STEP;
		List<Button> primary = List.of(this.drawButton, this.opacEditButton, this.frontierButton,
			this.opacMenuButton, this.claimsButton, this.frontierVisibilityButton);
		List<Button> shown = primary.stream().filter(button -> button.visible).toList();
		int rows = sideRows(bottom);
		int groupGap = bottom - (shown.size() - 1) * SIDE_STEP >= BAR + 5 ? 4 : 0;
		int modes = (int) primary.subList(0, 3).stream().filter(button -> button.visible).count();
		for (int i = 0; i < shown.size(); i++) {
			int offset = i >= modes ? groupGap : 0;
			this.placeSideButton(shown.get(i), x - (i / rows) * SIDE_STEP, bottom - (i % rows) * SIDE_STEP - offset);
		}
		int secondaryX = x - Math.max(1, (shown.size() + rows - 1) / rows) * SIDE_STEP - 5;
		if (this.export.visible) this.placeSideButton(this.export, secondaryX, zoomY + SIDE_STEP + 4);
		if (this.territoriesButton.visible) this.placeSideButton(this.territoriesButton, secondaryX, zoomY + 48);

		this.contextLeft = this.contextRight = secondaryX;
		this.contextTop = this.contextBottom = zoomY;
		List<Button> actions = this.opacEditing ? List.of(this.opacToolButton)
			: List.of(this.frontierCancelButton, this.frontierSaveButton, this.frontierDeleteButton,
				this.frontierRenameButton, this.frontierAddButton, this.frontierNewButton);
		int contextRows = sideRows(zoomY);
		int index = 0;
		for (Button button : actions) {
			if (!button.visible) continue;
			int left = secondaryX - (index / contextRows) * SIDE_STEP;
			int top = zoomY - (index % contextRows) * SIDE_STEP;
			this.placeSideButton(button, left, top);
			this.contextLeft = Math.min(this.contextLeft, left);
			this.contextTop = Math.min(this.contextTop, top);
			this.contextRight = secondaryX + SIDE_BUTTON;
			this.contextBottom = zoomY + SIDE_BUTTON;
			index++;
		}
		if (index > 0) {
			this.sideControlsLeft = Math.min(this.sideControlsLeft, this.contextLeft - 4);
			this.sideControlsTop = Math.min(this.sideControlsTop, this.contextTop - 3);
		}
	}

	private static int sideRows(int bottom) {
		return Math.max(1, 1 + (bottom - BAR - 1) / SIDE_STEP);
	}

	private void placeSideButton(Button button, int x, int y) {
		button.setPosition(x, y);
		this.sideControlsLeft = Math.min(this.sideControlsLeft, x);
		this.sideControlsTop = Math.min(this.sideControlsTop, y);
	}

	private void renameFrontierDraft() {
		Frontier draft = this.frontierEditor.draft();
		if (draft != null && draft.valid()) {
			this.minecraft.gui.setScreen(new FrontierEditScreen(this, draft, null, this.frontierEditor::updateAppearance));
		}
	}

	private void saveFrontierDraft() {
		Frontier draft = this.frontierEditor.draft();
		if (draft == null || !draft.valid()) return;
		boolean exists = FrontierClient.all().stream().anyMatch(f -> f.id.equals(draft.id));
		if (exists) {
			if (!this.frontierEditor.saved()) this.showClaimFeedback(Component.literal("Could not submit global frontier"), true, false);
		} else {
			this.frontierEditor.cancel();
			this.minecraft.gui.setScreen(new FrontierEditScreen(this, draft, null));
		}
	}

	/** The dimensions to step through: the player's, and every other with a saved map. */
	private List<String> dimensions() {
		Maps maps = Maps.get();
		ClientPacketListener connection = this.minecraft.getConnection();
		if (maps == null || connection == null || this.minecraft.level == null) {
			return List.of();
		}
		// Not the ones with the map off: what was mapped there before stays out of sight too.
		List<String> saved = maps.saved(this.minecraft.level.dimension().identifier().toString(),
			connection.levels().stream().map(level -> level.identifier().toString()).toList())
			.stream().filter(dimension -> !ServerRules.mapOff(dimension)).toList();
		// The one shown stays a stop even if nothing is left in it, or there'd be no way off it.
		if (this.otherDimension == null || saved.contains(this.otherDimension)) {
			return saved;
		}
		List<String> withShown = new ArrayList<>(saved);
		withShown.add(this.otherDimension);
		return withShown;
	}

	/** The dimension button: the next dimension's map, or the last one's with Shift. */
	private void nextDimension() {
		List<String> dimensions = this.dimensions();
		if (dimensions.size() < 2 || this.minecraft.level == null) {
			return;
		}
		int step = DrawMode.shiftDown() ? -1 : 1;
		String next = dimensions.get(Math.floorMod(dimensions.indexOf(this.dimension()) + step, dimensions.size()));
		this.draw.leaveDimension();
		this.closeOtherMap();
		this.otherDimension = next.equals(this.minecraft.level.dimension().identifier().toString()) ? null : next;
		this.viewChanged();
	}

	/** Centre the map from the territory directory, including saved maps in other dimensions. */
	public boolean showTerritory(String dimension, double x, double z) {
		if (this.minecraft.level == null || ServerRules.mapOff(dimension)) return false;
		if (!this.dimension().equals(dimension)) {
			this.draw.leaveDimension();
			this.closeOtherMap();
			this.otherDimension = dimension.equals(this.minecraft.level.dimension().identifier().toString()) ? null : dimension;
			this.viewChanged();
		}
		this.lookAt(Projection.u(x, z), Projection.v(x, 64, z), Math.max(this.zoom, 1), TileKey.SURFACE);
		return true;
	}

	/** The map now shows another dimension, or the player's own again. */
	private void viewChanged() {
		this.frontierEditor.leaveDimension();
		this.opacEditing = false;
		this.opacForceloadTool = false;
		this.menu = null;
		this.gauge.cancel();
		this.following = true;
		this.layerPicked = false;
		this.followHoldStart = -1;
		this.layer = this.otherDimension == null ? this.layerOnOpening() : TileKey.SURFACE;
		this.spotU = Integer.MIN_VALUE;
		this.spot = null;
		this.spotRequest = null;
	}

	/**
	 * The map to draw: the live one, or the saved one of the dimension being looked at. Walking into
	 * that dimension meanwhile brings the live one back, which has everything the saved one has.
	 */
	private MapLevel shownMap(MapSession session) {
		String other = this.otherDimension;
		Maps maps = Maps.get();
		if (other == null) {
			return session.map();
		}
		// Or the player turned the map off in the dimension shown, in Settings over this screen.
		if (maps == null || this.minecraft.level == null || other.equals(this.minecraft.level.dimension().identifier().toString()) || ServerRules.mapOff(other)) {
			this.draw.leaveDimension();
			this.closeOtherMap();
			this.otherDimension = null;
			this.viewChanged();
			return session.map();
		}
		// The player can switch this dimension's map, or change the view, from the screens over this one.
		Path folder = maps.tileFolder(other, maps.active(other));
		if (this.otherMap == null || !this.otherMap.folder().equals(folder) || this.otherMap.style() != MapStyle.get() || this.otherMap.facing() != Projection.facing()) {
			this.closeOtherMap();
			this.otherMap = new OtherMap(folder, MapStyle.get(), Projection.facing(), MapLevel.saved(folder));
		}
		this.otherMap.map().setFogOfWar(ServerRules.fogOfWar(other.equals("minecraft:the_nether"), this.otherMap.map().roofless()));
		return this.otherMap.map();
	}

	private void closeOtherMap() {
		if (this.otherMap != null) {
			this.otherMap.map().close();
			this.otherMap = null;
		}
	}

	/** The dimension shown, as its button says it. */
	private Component dimensionName() {
		return Waypoints.dimensionName(this.dimension());
	}

	/**
	 * How a position in the player's dimension reads in the one shown. A modded dimension can scale
	 * too, but nothing says so until the player is there.
	 */
	private double scaleToShown() {
		String here = this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
		return dimensionScale(here) / dimensionScale(this.dimension());
	}

	public static double dimensionScale(String dimension) {
		return dimension.equals("minecraft:the_nether") ? NETHER_SCALE : 1;
	}

	@Override
	public void tick() {
		super.tick();
		if (--this.refreshIn <= 0) {
			this.refreshIn = REFRESH_TICKS;
			this.refreshShared();
		}
		// The session updates its own map.
		if (this.otherMap != null) {
			this.otherMap.map().update();
		}
	}

	/** Puts the zoom buttons beside the gauge on the cave map, in the corner otherwise. */
	private static Component facingLetter() {
		return Component.translatable("diorama.map.facing_letter." + Projection.facing().id());
	}

	/** Turns this world's map, keeping the same ground in the middle of the view. */
	public void turn(int steps) {
		Maps maps = Maps.get();
		MapSession session = MapSession.get();
		if (maps == null || session == null) {
			return;
		}
		this.menu = null;
		Facing was = Projection.facing();
		Facing now = was.next(steps);
		if (!maps.facing(now)) {
			return;
		}
		// The ground in the middle stays there: on a cave slice, the slice's.
		double y = this.layer == TileKey.SURFACE ? USUAL_GROUND_Y : this.layer;
		double z = Projection.zAt(this.centerV, y);
		double worldX = was.worldX(this.centerU, z);
		double worldZ = was.worldZ(this.centerU, z);
		Projection.setFacing(now);
		this.closeOtherMap();
		MapSession.facingChanged();
		// The block under the mouse was the old facing's.
		this.spotU = Integer.MIN_VALUE;
		this.spot = null;
		this.spotRequest = null;
		this.centerU = Projection.u(worldX, worldZ);
		this.centerV = Projection.v(worldX, y, worldZ);
		this.facingButton.setMessage(facingLetter());
	}

	private void placeControls() {
		boolean caves = this.layer != TileKey.SURFACE;
		int x = this.width - (caves ? 56 : 26);
		int y = Math.max(BAR + 30, this.height - 88);
		// The sky view is exported from what's saved, a cave slice from the chunks the map holds; not
		// while drawing on it.
		MapSession session = MapSession.get();
		this.export.visible = !this.draw.active() && this.otherDimension == null && session != null && (caves || session.map().savedMap() != null);
		this.placeSideControls(x, y);
		this.zoomIn.setPosition(x, y);
		this.zoomOut.setPosition(x, y + 22);
		this.recenter.setPosition(x, y + 48);
		this.facingButton.setPosition(x - 22, y + 48);
		// The draw tools can reach down this far on a short window, and turning moves what's being drawn on.
		this.facingButton.visible = !this.draw.active();
		// Nothing to switch to until a second dimension has a map. Not while drawing, where the draw
		// tools can reach down this far on a short window.
		this.dimensionButton.visible = this.severalDimensions && Settings.get().mapDimensionButton && !this.draw.active();
		this.dimensionButton.setPosition(DIMENSION_BUTTON_X, y + 48);
		this.zoomIn.active = this.zoom < MAX_ZOOM;
		this.zoomOut.active = this.zoom > this.minZoom();
		this.sliceUp.visible = caves;
		this.sliceDown.visible = caves;
		ClientLevel level = this.minecraft.level;
		if (caves && level != null) {
			this.sliceUp.active = this.layer < level.getMaxY() + 1;
			this.sliceDown.active = this.layer > level.getMinY() + DepthGauge.STEP;
		}
	}

	// No blurred, dimmed world behind the map. On 1.20.1 a screen draws no background unless it
	// asks for one, so there's nothing to turn off.
	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.tooltipLines = null;
		if (!ServerRules.minimapAllowed()) {
			// The server can switch maps off while this screen is open.
			this.onClose();
			return;
		}
		// The map can open before a server names its world, and the list of maps is the world's.
		this.mapsButton.active = Maps.get() != null;
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		if (session == null || viewer == null) {
			return;
		}
		MapLevel shown = this.shownMap(session);
		boolean elsewhere = this.otherDimension != null;
		Vec3 pos = viewer.getPosition(a);
		double scale = this.scaleToShown();
		double playerU = Projection.u(pos.x * scale, pos.z * scale);
		double playerV = Projection.v(pos.x * scale, pos.y, pos.z * scale);
		if (this.following) {
			this.centerU = playerU;
			this.centerV = playerV;
		}
		this.followLayer(session, viewer);
		// Switching to a cave slice while zoomed far out comes back to the closest zoom it can show.
		this.zoom = Math.max(this.minZoom(), this.zoom);
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		this.lastView = view;
		if (this.draw.holding() && this.pressButton != InputConstants.MOUSE_BUTTON_LEFT) {
			// The left button is still down for the pen, eraser or measuring tool, but another button was
			// pressed over it (the middle one, to pan). The game sends drags for the latest press only, and
			// none once that is let go, so the drawing follows the cursor from here instead. lastView is
			// already this frame's, which the measuring tool picks its block through.
			this.draw.follow(view, this.font, this.dimension(), mouseX, mouseY);
		}
		// Held lights belong to this dimension's map, not one shown from elsewhere.
		this.viewport.draw(graphics, shown, view, elsewhere ? 0 : overlayBits(session), !elsewhere && Settings.get().claims != Settings.ClaimsShown.OFF,
			elsewhere ? null : () -> session.lights().draw(graphics, view, a, 1, ServerRules.radarAllowed() && Settings.get().radarOnMap));
		if (!elsewhere) {
			session.crafts().draw(graphics, view, a, 1);
		}
		// The client only knows the border of the dimension it's in.
		if (!elsewhere && Settings.get().worldBorder != Settings.WorldBorderShown.OFF) {
			this.worldBorder.draw(graphics, this.font, view, this.minecraft.level, viewer);
		}
		boolean caves = this.layer != TileKey.SURFACE;
		this.placeControls();
		MapAnnotations.draw(graphics, view, this.dimension(), true);
		this.frontierEditor.draw(graphics, view, mouseX, mouseY,
			this.menu == null && !this.overControls(mouseX, mouseY));
		DrawingStore drawings = DrawingStore.get();
		if (drawings != null && (this.draw.active() || Settings.get().drawings != Settings.DrawingsShown.OFF)) {
			this.draw.drawMap(graphics, this.font, view, drawings.in(this.dimension()), mouseX, mouseY, !this.overControls(mouseX, mouseY));
		}
		Radar.Blip hovered = null;
		this.blips = List.of();
		if (!elsewhere && ServerRules.radarAllowed() && Settings.get().radarOnMap) {
			this.blips = Radar.collect(view, a, true);
			Radar.draw(graphics, view, this.blips, true, false);
			hovered = Radar.under(this.blips, mouseX, mouseY);
		}
		WaypointStore store = WaypointStore.get();
		if (Settings.get().waypointsOnMap) {
			List<Waypoint> mapPins = new ArrayList<>();
			if (store != null) {
				mapPins.addAll(store.shownIn(this.dimension()));
			}
			mapPins.addAll(MapAnnotations.markers(this.dimension()));
			this.pins = this.layoutPins(view, mapPins);
		} else {
			this.pins = List.of();
		}
		WaypointOverlay.Pin hoveredPin = WaypointOverlay.underHover(this.pins, mouseX, mouseY);
		WaypointOverlay.draw(graphics, view, this.pins, true, hoveredPin);
		Radar.draw(graphics, view, this.blips, true, true);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), Markers.onMap(viewer.getViewYRot(a)), 6);
		if (caves) {
			this.gauge.layout(this.minecraft.level, viewer.blockPosition(), this.layer, this.width, this.height);
			this.sliceUp.setPosition(this.gauge.upButtonX(), this.gauge.upButtonY());
			this.sliceDown.setPosition(this.gauge.upButtonX(), this.gauge.downButtonY());
			// Its labels slide left of the map's buttons (draw, zoom, recenter) rather than under them.
			int avoidX = this.sideControlsLeft - 3;
			int avoidTop = this.sideControlsTop - 2;
			int avoidBottom = this.recenter.getY() + this.recenter.getHeight() + 2;
			//? if >=1.21.2 {
			this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom);
			//?} else {
			/*// Each fill would be its own draw call otherwise.
			graphics.drawManaged(() -> this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom));
			*///?}
		} else {
			this.gauge.cancel();
		}
		// The tooltip is for a control under the mouse, else whatever is drawn on top: a player,
		// then a waypoint, then a mob, then the spot itself. The menu covers them while it's open.
		if (this.menu == null) {
			if (this.draw.active() && !this.overControls(mouseX, mouseY)) {
				// Drawing: the map's own tooltips would sit on what's being drawn.
			} else if (this.overControls(mouseX, mouseY)) {
				Tip tip = this.controlTooltip(mouseX, mouseY);
				if (tip != null) {
					// Placed like a button's own tooltip: below the control, or above where there's no room.
					//? if >=1.21.6 {
					graphics.setTooltipForNextFrame(this.font, tip.lines(), new MenuTooltipPositioner(tip.control()), mouseX, mouseY, false);
					//?} else if >=1.21 {
					/*this.setTooltipForNextRenderPass(tip.lines(), new MenuTooltipPositioner(tip.control()), false);
					*///?} else {
					/*// 1.20.1's positioner takes a widget, of which it reads only the top and height, and
					// the tabs aren't widgets: a button over the control stands in for it.
					ScreenRectangle control = tip.control();
					Button stand = Button.builder(Component.empty(), b -> {
					}).bounds(control.left(), control.top(), control.width(), control.height()).build();
					this.setTooltipForNextRenderPass(tip.lines(), new MenuTooltipPositioner(stand), false);
					*///?}
				}
			} else if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
				this.tooltipLines = this.withClaimAt(Radar.describe(hovered, viewer), mouseX, mouseY);
			} else if (hoveredPin != null) {
				Waypoint waypoint = hoveredPin.waypoint();
				// Another dimension's map: the claims known are those of the dimension the player is in.
				this.tooltipLines = elsewhere ? List.of(Component.literal(waypoint.shownName()).getVisualOrderText())
					: this.withClaimAt(WaypointOverlay.describe(waypoint, viewer), mouseX, mouseY);
			} else if (hovered != null) {
				this.tooltipLines = this.withClaimAt(Radar.describe(hovered, viewer), mouseX, mouseY);
			} else if (this.pressButton == -1) {
				this.tooltipLines = this.spotTooltip(mouseX, mouseY);
			}
			if (this.tooltipLines != null) {
				graphics.setTooltipForNextFrame(this.font, this.tooltipLines, mouseX, mouseY);
			}
		}

		this.drawClaimableRange(graphics, view);
		if (this.claimSelecting) {
			BlockPos from = this.roughSpot(this.pressX, this.pressY);
			BlockPos to = this.roughSpot(this.claimEndX, this.claimEndY);
			boolean tooFar = (from != null && OpacClaims.outsideClaimDistance(this.minecraft, from.getX() >> 4, from.getZ() >> 4))
				|| (to != null && OpacClaims.outsideClaimDistance(this.minecraft, to.getX() >> 4, to.getZ() >> 4));
			int color = tooFar ? 0x77FF3C3C : this.claimForceloadSelecting
				? (this.claimRemoving ? 0x77FF9764 : 0x777EBEFF)
				: this.claimRemoving ? 0x55FF6666 : 0x5566DD88;
			if(from!=null&&to!=null)this.drawClaimChunks(graphics,view,Math.min(from.getX()>>4,to.getX()>>4),Math.min(from.getZ()>>4,to.getZ()>>4),Math.max(from.getX()>>4,to.getX()>>4),Math.max(from.getZ()>>4,to.getZ()>>4),color);
		} else if (this.opacGestures() && !this.overControls(mouseX, mouseY)) {
			BlockPos at = this.roughSpot(mouseX, mouseY);
			if (at != null) {
				int cx = at.getX() >> 4, cz = at.getZ() >> 4;
				int color = OpacClaims.outsideClaimDistance(this.minecraft, cx, cz) ? 0x77FF3C3C : 0x5566DD88;
				this.drawClaimChunks(graphics, view, cx, cz, cx, cz, color);
			}
		}

		// The bars: tabs and status on top with the buttons, the mouse's ways in at the bottom.
		graphics.fill(0, 0, this.width, BAR, 0x90000000);
		this.drawTabs(graphics, mouseX, mouseY);
		int statusX = this.tabsEnd + 8;
		graphics.text(this.font, this.statusText(shown, viewer, caves, this.buttonsStart() - 2 - statusX), statusX, 4, 0xFFFFFFFF, false);
		graphics.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0x90000000);
		String help;
		if (this.claimSelecting) {
			BlockPos current = this.roughSpot(this.claimEndX, this.claimEndY);
			boolean tooFar = current != null && OpacClaims.outsideClaimDistance(this.minecraft,
				current.getX() >> 4, current.getZ() >> 4);
			help = tooFar ? "OPAC: Outside claimable range · Esc to cancel"
				: "OPAC: " + (this.claimForceloadSelecting
					? (this.claimRemoving ? "Unmark for forceload" : "Mark for forceload")
					: (this.claimRemoving ? "Unclaim" : "Claim"))
					+ " area · Release to submit · Esc to cancel";
		} else if (this.opacGestures()) {
			BlockPos current = this.roughSpot(mouseX, mouseY);
			boolean tooFar = current != null && OpacClaims.outsideClaimDistance(this.minecraft,
				current.getX() >> 4, current.getZ() >> 4);
			help = tooFar ? "OPAC: Outside claimable range"
				: this.opacEditing
					? (this.opacForceloadTool
						? "OPAC Forceload: left drag to mark · right drag to unmark · striped chunks are marked"
						: "OPAC Edit: left drag to claim · right drag to unclaim · OPAC checks permissions")
					: "OPAC: Shift+left drag to claim · Shift+right drag to unclaim · Esc cancels";
		} else if (this.frontierEditor.active()) {
			String action = !this.frontierEditor.adding() ? "frontier_select_help"
				: this.frontierEditor.inserting() ? "frontier_insert_help" : "frontier_append_help";
			help = sideLabel(action).getString();
		} else {
			help = (this.draw.active() ? this.draw.help() : HELP).getString();
		}
		help = this.font.plainSubstrByWidth(help, this.width - 12);
		graphics.text(this.font, help, 6, this.height - 11, 0xFFC8D0DA, false);
		this.drawMapName(graphics, 6 + this.font.width(help) + 12);
		// Group the contextual tools with the colour of the mode that opened them.
		if (this.contextRight > this.contextLeft) {
			graphics.fill(this.contextLeft - 4, this.contextTop - 3, this.contextRight + 3, this.contextBottom + 3, 0xB0111824);
			graphics.fill(this.contextLeft - 4, this.contextTop - 3, this.contextLeft - 3, this.contextBottom + 3,
				this.opacEditing ? OPAC_TINT : FRONTIER_TINT);
		}
		// Widgets (the buttons) draw over the map, then the icons on them.
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.drawClaimFeedback(graphics);
		Markers.player(graphics, this.recenter.getX() + 10, this.recenter.getY() + 10.5F, 180, 5);
		DrawMode.icon(graphics, DrawMode.PENCIL_ICON, this.drawButton.getX(), this.drawButton.getY(), DRAW_TINT);
		if (this.draw.active()) {
			DrawMode.outline(graphics, this.drawButton.getX(), this.drawButton.getY(), 20, 20, DRAW_TINT);
		}
		this.draw.drawControls(graphics);
		this.sideControls.forEach((button, control) -> {
			if (!button.visible) return;
			DrawMode.icon(graphics, control.icon().get(), button.getX(), button.getY(), button.active ? control.tint() : 0xFFAAAAAA);
			if (control.selected().getAsBoolean()) {
				DrawMode.outline(graphics, button.getX(), button.getY(), SIDE_BUTTON, SIDE_BUTTON, control.tint());
			}
		});
		if (this.export.visible) {
			DrawMode.icon(graphics, PICTURE, this.export.getX(), this.export.getY(), EXPORT_TINT);
		}
		if (this.dimensionButton.visible) {
			this.drawDimensionIcon(graphics);
		}
		if (caves) {
			this.caret(graphics, this.sliceUp, false);
			this.caret(graphics, this.sliceDown, true);
		}
		if (this.menu != null) {
			// Over everything drawn so far, text included.
			//? if >=1.21.6 {
			graphics.nextStratum();
			this.menu.draw(graphics, mouseX, mouseY);
			//?} else {
			/*graphics.flush();
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 400);
			this.menu.draw(graphics, mouseX, mouseY);
			pose.popPose();
			*///?}
		}
	}

	/**
	 * The status line: whether the view is under the Nether's roof, the player's position, the
	 * chunks mapped, whether baking is running and how far an image export is. It has {@code room}
	 * pixels before the buttons; what doesn't fit is left out whole, the chunk count first, then
	 * baking, the roof and the export. On another dimension's map, where the player's position
	 * doesn't belong, it says where they are instead, and there are no chunks or bakes to count.
	 */
	private String statusText(MapLevel shown, Entity viewer, boolean caves, int room) {
		boolean elsewhere = this.otherDimension != null;
		String roof = !caves && shown.roofless() ? Component.translatable("diorama.map.under_roof").getString() : null;
		String position = elsewhere
			? Component.translatable("diorama.map.you_are_in", Waypoints.dimensionName(viewer.level().dimension().identifier().toString())).getString()
			: String.format("%d, %d, %d", viewer.getBlockX(), viewer.getBlockY(), viewer.getBlockZ());
		String chunks = elsewhere ? null : Component.translatable("diorama.map.chunks", shown.chunkCount()).getString();
		SavedChunkImport job = elsewhere ? null : MapSession.get().savedChunks();
		boolean redrawing = job != null && shown.memory() != null && job.chunksLeft() > 0;
		String baking = redrawing ? Component.translatable("diorama.map.redrawing", job.percentDone()).getString()
			: !elsewhere && shown.bakingCount() > 0 ? Component.translatable("diorama.map.baking").getString() : null;
		MapExport export = MapExport.running();
		String saving = export != null ? Component.translatable("diorama.export.saving", Math.round(export.progress() * 100)).getString() : null;
		List<String> parts = new ArrayList<>();
		for (String part : new String[] {roof, position, chunks, baking, saving}) {
			if (part != null) {
				parts.add(part);
			}
		}
		for (String dropped : new String[] {chunks, baking, roof, saving}) {
			if (this.font.width(String.join(STATUS_GAP, parts)) <= room) {
				break;
			}
			parts.remove(dropped);
		}
		return this.font.plainSubstrByWidth(String.join(STATUS_GAP, parts), Math.max(0, room));
	}

	/**
	 * The name of the dimension's map in use, at the right end of the bottom bar, once the dimension
	 * has more than one. Shortened to fit after the help text, or left out if even a little won't.
	 */
	private void drawMapName(GuiGraphicsExtractor graphics, int left) {
		Maps maps = Maps.get();
		if (maps == null || this.minecraft.level == null) {
			return;
		}
		String dimension = this.dimension();
		if (!maps.several(dimension)) {
			return;
		}
		String name = Component.translatable("diorama.map.current_map", maps.name(dimension, maps.active(dimension))).getString();
		int room = this.width - 6 - left;
		if (this.font.width(name) > room) {
			String ellipsis = CommonComponents.ELLIPSIS.getString();
			if (room < this.font.width(ellipsis) * 4) {
				return;
			}
			name = this.font.plainSubstrByWidth(name, room - this.font.width(ellipsis)) + ellipsis;
		}
		graphics.text(this.font, name, this.width - 6 - this.font.width(name), this.height - 11, 0xFFFFFFFF, false);
	}

	/** Toggles the existing claims setting without losing the fullscreen-only preference. */
	private void toggleClaims() {
		this.cancelClaimSelection();
		Settings settings = Settings.get();
		if (settings.claims == Settings.ClaimsShown.OFF) {
			settings.claims = settings.claimsWhenEnabled == Settings.ClaimsShown.OFF
				? Settings.ClaimsShown.ALWAYS : settings.claimsWhenEnabled;
		} else {
			settings.claimsWhenEnabled = settings.claims;
			settings.claims = Settings.ClaimsShown.OFF;
		}
		settings.save();
		this.menu = null;
	}

	private static net.minecraft.network.chat.MutableComponent claimsToggleLabel() {
		return Component.translatable(Settings.get().claims == Settings.ClaimsShown.OFF
			? "diorama.map.claims_off" : "diorama.map.claims_on");
	}

	private boolean frontiersShown() {
		return Settings.get().frontiers != Settings.FrontiersShown.OFF;
	}

	private void toggleFrontiers() {
		Settings settings = Settings.get();
		if (this.frontiersShown()) {
			settings.frontiersWhenEnabled = settings.frontiers;
			settings.frontiers = Settings.FrontiersShown.OFF;
		} else {
			settings.frontiers = settings.frontiersWhenEnabled;
		}
		settings.save();
		this.menu = null;
	}

	/** Where the buttons at the top right start: the status line has to end before them. */
	private int buttonsStart() {
		int start = this.width;
		for (Button button : List.of(this.mapsButton, this.waypointsButton, this.settingsButton)) {
			if (button.visible) {
				start = Math.min(start, button.getX());
			}
		}
		return start;
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.draw.anyPress(event.x(), event.y());
		return this.boundPressed(event.button(), DioramaClient.WAYPOINT_LIST.matchesMouse(event), DioramaClient.OPEN_MAP.matchesMouse(event), DioramaClient.TOGGLE_DIMENSION.matchesMouse(event))
			|| this.menuPressed(event.x(), event.y(), event.button()) || this.clicked(super.mouseClicked(event, doubleClick), event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.released(event.x(), event.y(), event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x(), event.y(), event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key(), DioramaClient.WAYPOINT_LIST.matches(event), DioramaClient.OPEN_MAP.matches(event), DioramaClient.TOGGLE_DIMENSION.matches(event))
			|| super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.draw.anyPress(x, y);
		return this.boundPressed(button, DioramaClient.WAYPOINT_LIST.matchesMouse(button), DioramaClient.OPEN_MAP.matchesMouse(button), DioramaClient.TOGGLE_DIMENSION.matchesMouse(button))
			|| this.menuPressed(x, y, button) || this.clicked(super.mouseClicked(x, y, button), x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return this.released(x, y, button) || super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key, DioramaClient.WAYPOINT_LIST.matches(key, scancode), DioramaClient.OPEN_MAP.matches(key, scancode), DioramaClient.TOGGLE_DIMENSION.matches(key, scancode))
			|| super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	/**
	 * A mouse button bound to one of the map's keys, checked before the menu and the widgets as a key
	 * is: widgets only click with the left button. The map is used with the left and right buttons,
	 * and pans with the middle one while drawing, so a binding on those does nothing here.
	 */
	private boolean boundPressed(int button, boolean waypointListKey, boolean mapKey, boolean dimensionKey) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT
			|| (button == InputConstants.MOUSE_BUTTON_MIDDLE && this.draw.active())) {
			return false;
		}
		return this.bindingPressed(waypointListKey, mapKey, dimensionKey);
	}

	/**
	 * A press while the menu is open, which the widgets don't get: on the menu it's for the menu,
	 * and anywhere else its release only closes the menu. False when the menu isn't open.
	 */
	private boolean menuPressed(double x, double y, int button) {
		if (this.menu != null && this.claimSelecting) {
			this.cancelClaimSelection();
		}
		if (this.menu == null) {
			return false;
		}
		this.pressOnMenu = this.menu.contains(x, y);
		this.pressButton = button;
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A press; {@code widgetTook} if a widget (a button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y, int button) {
		// A second click may arrive while the first button's release was missed.
		// Never let the old rectangle keep intercepting unrelated gestures.
		this.cancelClaimSelection();
		this.pressButton = button;
		this.pressOnControl = false;
		if (!widgetTook && button == InputConstants.MOUSE_BUTTON_LEFT) {
			int tab = this.tabAt(x, y);
			if (tab >= 0) {
				if ((tab == 1) != (this.layer != TileKey.SURFACE)) {
					this.toggleCaves();
				} else if (this.otherDimension == null) {
					// The tab already shown, clicked: a pick too, so the map stops following.
					this.layerPicked = true;
				}
				this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				widgetTook = true;
			} else if (this.layer != TileKey.SURFACE && this.minecraft.level != null && this.gauge.contains(x, y)) {
				this.menu = null;
				// Picking a slice: following the player could switch layers under the drag.
				this.layerPicked = true;
				this.gauge.grab(this.minecraft.level, y);
				widgetTook = true;
			}
		}
		if (!widgetTook && (this.onButton(x, y) || this.onContextPanel(x, y))) {
			// A button that can't go further (zoom at its limit) still isn't the map.
			widgetTook = true;
		}
		if (widgetTook) {
			this.pressOnControl = true;
			this.dragged = true;
			return true;
		}
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		if (this.frontierEditor.active() && this.lastView != null
			&& this.frontierEditor.press(this.lastView, x, y, button)) return true;
		if (this.opacGestures()
			&& (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
			this.claimSelecting = true;
			this.claimRemoving = button == InputConstants.MOUSE_BUTTON_RIGHT;
			this.claimForceloadSelecting = this.opacEditing && this.opacForceloadTool;
			this.claimEndX = x;
			this.claimEndY = y;
			return true;
		}
		if (this.draw.active() && this.lastView != null) {
			this.draw.press(this.lastView, this.font, this.dimension(), x, y, button);
		}
		return true;
	}

	/** A release; false leaves it to the screen's widgets. */
	private boolean released(double x, double y, int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT && this.gauge.dragging()) {
			// The slice moves only now, so the map prepares just the one it was let go at.
			int slice = this.gauge.release();
			if (this.layer != TileKey.SURFACE) {
				this.layer = slice;
				this.layerPicked = true;
			}
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && button != this.pressButton && this.draw.holding() && this.lastView != null) {
			// Another button was pressed while drawing with the left: letting go of the left still ends the stroke,
			// erase or measurement.
			this.draw.release(this.lastView, this.font, this.dimension(), x, y, button, true);
			return true;
		}
		if (this.claimSelecting) {
			if (button != this.pressButton) {
				// Lost/overwritten initial mouse press. Cancel rather than getting stuck.
				this.cancelClaimSelection();
				return true;
			}
			boolean remove = this.claimRemoving;
			boolean forceload = this.claimForceloadSelecting;
			this.cancelClaimSelection();
			this.claimSelection(this.pressX, this.pressY, x, y, remove, forceload);
			return true;
		}
		// Only the release of the latest press counts: letting go of a button held since before
		// the menu opened mustn't close it.
		if (button != this.pressButton) {
			return true;
		}
		this.pressButton = -1;
		boolean moved = this.dragged || Math.hypot(x - this.pressX, y - this.pressY) > 3;
		if (this.pressOnMenu) {
			this.pressOnMenu = false;
			if (!moved && this.menu != null && (button == InputConstants.MOUSE_BUTTON_RIGHT
				|| (button == InputConstants.MOUSE_BUTTON_LEFT && this.menu.click(x, y)))) {
				this.menu = null;
			}
			return true;
		}
		if (this.menu != null) {
			this.menu = null;
			if (!moved && button == InputConstants.MOUSE_BUTTON_RIGHT && !this.overControls(x, y)) {
				this.openMenu(x, y);
			}
			return true;
		}
		if (this.frontierEditor.active() && !this.pressOnControl && this.lastView != null
			&& this.frontierEditor.release(this.lastView, this.dimension(), x, y, button, moved)) return true;
		if (this.draw.active() && !this.pressOnControl && this.lastView != null
			&& this.draw.release(this.lastView, this.font, this.dimension(), x, y, button, moved)) {
			return true;
		}
		if (moved) {
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, x, y);
			if (pin != null && pin.waypoint().editable()) {
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, pin.waypoint().source(), false));
			} else if (pin != null) {
				// A waystone can't be edited here: its menu instead.
				this.openMenu(x, y);
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.openMenu(x, y);
			return true;
		}
		return false;
	}

	/** Called on the Minecraft client thread by OPAC's server-result listener. */
	public void showClaimFeedback(Component message, boolean error, boolean pending) {
		this.claimFeedback = message;
		this.claimFeedbackError = error;
		this.claimFeedbackPending = pending;
		this.claimFeedbackUntil = System.currentTimeMillis() + (pending ? 3500L : 6500L);
	}

	/** Render the most recent claim result inside Diorama, above the map help bar. */
	private void drawClaimFeedback(GuiGraphicsExtractor graphics) {
		if (this.claimFeedback == null) return;
		if (System.currentTimeMillis() >= this.claimFeedbackUntil) {
			this.claimFeedback = null;
			return;
		}
		int right = this.sideControlsLeft - 6;
		int maxTextWidth = Math.max(1, Math.min(440, right - 30));
		List<FormattedCharSequence> lines = this.font.split(this.claimFeedback, maxTextWidth);
		int lineCount = Math.min(lines.size(), 4);
		if (lineCount == 0) return;
		int textWidth = 0;
		for (int i = 0; i < lineCount; i++) {
			textWidth = Math.max(textWidth, this.font.width(lines.get(i)));
		}
		int left = (6 + right - textWidth - 24) / 2;
		int top = this.height - BOTTOM_BAR - 16 - lineCount * 10;
		graphics.fill(left, top, left + textWidth + 24, top + lineCount * 10 + 12, 0xDE141C24);
		graphics.fill(left, top, left + 3, top + lineCount * 10 + 12,
			this.claimFeedbackPending ? 0xFFE0B858 : this.claimFeedbackError ? 0xFFE66A62 : 0xFF69CF8D);
		for (int i = 0; i < lineCount; i++) {
			graphics.text(this.font, lines.get(i), left + 12, top + 6 + i * 10, 0xFFFFFFFF, false);
		}
	}

	/** Reset every bit of claim-drag input state, without changing OPAC's claim data. */
	private void cancelClaimSelection() {
		if (this.claimSelecting) {
			this.pressButton = -1;
			this.dragged = false;
		}
		this.claimSelecting = false;
		this.claimRemoving = false;
		this.claimForceloadSelecting = false;
	}

	/**
	 * Approximate claim-distance outline while holding Shift or dragging a claim area.
	 * OPAC limits X and Z independently, giving a square around the player's chunk.
	 * The map uses the same fixed height as the claim rectangles, so the outline follows its projection
	 * but does not account for varying terrain elevations.
	 */
	private void drawClaimChunks(GuiGraphicsExtractor graphics,MapViewport.View view,int x0,int z0,int x1,int z1,int color){
		double y=ClaimProjection.height(this.layer);
		float ax=view.screenX(Projection.u((double)x0*16,(double)z0*16)),bx=view.screenX(Projection.u(((double)x1+1)*16,((double)z1+1)*16));
		float ay=view.screenY(Projection.v((double)x0*16,y,(double)z0*16)),by=view.screenY(Projection.v(((double)x1+1)*16,y,((double)z1+1)*16));
		int l=Math.max(0,(int)Math.floor(Math.min(ax,bx))),r=Math.min(this.width,(int)Math.ceil(Math.max(ax,bx))),t=Math.max(BAR,(int)Math.floor(Math.min(ay,by))),b=Math.min(this.height-BOTTOM_BAR,(int)Math.ceil(Math.max(ay,by)));
		if(l>=r||t>=b)return;
		graphics.fill(l,t,r,b,color);graphics.fill(l,t,r,t+1,0xFFFFFFFF);graphics.fill(l,b-1,r,b,0xFFFFFFFF);graphics.fill(l,t,l+1,b,0xFFFFFFFF);graphics.fill(r-1,t,r,b,0xFFFFFFFF);
	}

	private boolean opacGestures() {
		return !this.frontierEditor.active() && !this.draw.active() && this.otherDimension == null
			&& (this.opacEditing || DrawMode.shiftDown()) && OpacClaims.available(this.minecraft);
	}

	private void drawClaimableRange(GuiGraphicsExtractor graphics, MapViewport.View view) {
		if (!this.claimSelecting && !this.opacGestures()) {
			return;
		}
		int range = OpacClaims.maxClaimDistance(this.minecraft);
		if (range < 0 || this.minecraft.player == null) {
			return;
		}
		int cx = this.minecraft.player.chunkPosition().x();
		int cz = this.minecraft.player.chunkPosition().z();
		long[] x = {(long) (cx - range) * 16, ((long) cx + range + 1) * 16};
		long[] z = {(long) (cz - range) * 16, ((long) cz + range + 1) * 16};
		double y = ClaimProjection.height(this.layer);
		float[] px = new float[4], py = new float[4];
		for (int i = 0; i < 4; i++) {
			double wx = x[i == 1 || i == 2 ? 1 : 0];
			double wz = z[i >= 2 ? 1 : 0];
			px[i] = view.screenX(Projection.u(wx, wz));
			py[i] = view.screenY(Projection.v(wx, y, wz));
		}
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		for (int i = 0; i < 4; i++) {
			int next = (i + 1) & 3;
			float dx = px[next] - px[i], dy = py[next] - py[i];
			float len = (float) Math.hypot(dx, dy);
			if (len < 0.001f) continue;
			float nx = -dy / len, ny = dx / len;
			MapCanvas.of(graphics).solid(new float[] {
				px[i] + nx, py[i] + ny,
				px[i] - nx, py[i] - ny,
				px[next] - nx, py[next] - ny,
				px[next] + nx, py[next] + ny
			}, 0xCC58DB83);
		}
		graphics.disableScissor();
	}

	/** Claim exactly the flat chunk rectangles previewed by the drag highlight. */
	private void claimSelection(double fromX, double fromY, double toX, double toY, boolean remove, boolean forceload) {
		BlockPos from = this.roughSpot(fromX, fromY);
		BlockPos to = this.roughSpot(toX, toY);
		if (from == null || to == null || this.minecraft.level == null) return;
		int x0 = from.getX() >> 4, z0 = from.getZ() >> 4;
		int x1 = to.getX() >> 4, z1 = to.getZ() >> 4;
		long count = (Math.abs((long) x1 - x0) + 1) * (Math.abs((long) z1 - z0) + 1);
		if (count > MAX_CLAIM_SELECTION_CHUNKS) {
			this.showClaimFeedback(Component.literal("Selection is too large (maximum 4096 chunks)"), true, false);
			return;
		}
		OpacClaims.requestArea(this.minecraft, x0, z0, x1, z1,
			forceload
				? (remove ? OpacClaims.Action.UNFORCELOAD : OpacClaims.Action.FORCELOAD)
				: (remove ? OpacClaims.Action.UNCLAIM : OpacClaims.Action.CLAIM));
	}

	/** Opens the menu for what's under a screen point: a waypoint's, or the spot's. */
	private void openFrontierMenu(double x, double y, MapViewport.View view) {
		Frontier target = this.frontierEditor.frontierAt(view, this.dimension(), x, y);
		Frontier draft = this.frontierEditor.draft();
		List<MapMenu.Item> items = new ArrayList<>();
		if (target == null) {
			items.add(MapMenu.Item.action(sideLabel("frontier_new"), true, () -> this.frontierEditor.newFrontier(this.dimension())));
			DrawingStore drawn = DrawingStore.get();
			if (drawn != null && Settings.get().drawings != Settings.DrawingsShown.OFF) {
				Drawing area = DrawingOverlay.under(this.font, view, drawn.in(this.dimension()), x, y, false);
				if (area != null && area.kind == Drawing.Kind.AREA && area.complete()) {
					items.add(MapMenu.Item.action(Component.literal("Convert to named frontier"), true, () ->
						this.minecraft.gui.setScreen(new FrontierEditScreen(this, FrontierProjection.fromDrawing(area), area))));
				}
			}
		} else {
			items.add(MapMenu.Item.title(Component.literal(target.name)));
			if (draft == null || !draft.id.equals(target.id)) {
				items.add(MapMenu.Item.action(sideLabel("frontier_select"), true, () -> this.frontierEditor.select(target)));
				items.add(MapMenu.Item.action(sideLabel("frontier_rename"), true,
					() -> this.minecraft.gui.setScreen(new FrontierEditScreen(this, target, null))));
				items.add(MapMenu.Item.confirmed(sideLabel("frontier_delete"),
					Component.translatable("diorama.menu.delete_confirm"), () -> FrontierClient.remove(target.id)));
			} else {
				items.add(MapMenu.Item.action(sideLabel(this.frontierEditor.adding() ? "frontier_stop_points" : "frontier_add_point"),
					true, () -> this.frontierEditor.addPoint()));
				items.add(MapMenu.Item.action(sideLabel("frontier_rename"), draft.valid(), this::renameFrontierDraft));
				items.add(MapMenu.Item.action(sideLabel("frontier_save"), draft.valid(), this::saveFrontierDraft));
				items.add(MapMenu.Item.action(sideLabel("frontier_cancel"), true, () -> this.frontierEditor.cancel()));
				items.add(MapMenu.Item.confirmed(sideLabel("frontier_delete"),
					Component.translatable("diorama.menu.delete_confirm"), () -> this.frontierEditor.delete()));
			}
		}
		this.menu = new MapMenu(items, this.font, x, y, this.width, this.height);
	}

	private void openMenu(double screenX, double screenY) {
		if (this.frontierEditor.active() && this.lastView != null) {
			this.openFrontierMenu(screenX, screenY, this.lastView);
			return;
		}
		WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, screenX, screenY);
		if (pin != null) {
			this.menu = new MapMenu(this.waypointItems(pin.waypoint()), this.font, screenX, screenY, this.width, this.height);
			return;
		}
		CompletableFuture<MapLevel.Spot> spot = this.spotAt(screenX, screenY, true);
		BlockPos rough = this.roughSpot(screenX, screenY);
		try {
			// Where the chunks are gone, the spot comes off the disk, usually in a few milliseconds.
			MapLevel.Spot found = spot.get(SPOT_WAIT_MS, TimeUnit.MILLISECONDS);
			this.menu = new MapMenu(this.spotItems(found, rough), this.font, screenX, screenY, this.width, this.height);
		} catch (TimeoutException e) {
			// A slow disk: say so until it answers, unless the menu has been closed or replaced by then.
			MapMenu waiting = new MapMenu(List.of(MapMenu.Item.info(Component.translatable("diorama.menu.finding"))),
				this.font, screenX, screenY, this.width, this.height);
			this.menu = waiting;
			spot.thenAcceptAsync(found -> {
				if (this.menu == waiting) {
					this.menu = new MapMenu(this.spotItems(found, rough), this.font, screenX, screenY, this.width, this.height);
				}
			}, this.minecraft);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			this.menu = new MapMenu(this.spotItems(MapLevel.Spot.NOTHING, rough), this.font, screenX, screenY, this.width, this.height);
		}
	}

	/** How long a right-click waits for a spot from a saved tile before opening the menu without it. */
	private static final long SPOT_WAIT_MS = 50;

	/**
	 * Where a player would stand on the block shown under a screen point. Answered at once from
	 * the map's chunks; where they're gone, from the spots saved with the tile, which takes a read
	 * off the render thread, or with {@code readWorld} from the world's save where the tile has none.
	 */
	private CompletableFuture<MapLevel.Spot> spotAt(double screenX, double screenY, boolean readWorld) {
		MapViewport.View view = this.lastView;
		if (view == null) {
			return CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
		}
		return this.spotAtMap(view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom(), readWorld);
	}

	/** {@link #spotAt} for a map point, wherever the map has moved since. */
	private CompletableFuture<MapLevel.Spot> spotAtMap(double u, double mapV, boolean readWorld) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		if (session == null || looks == null) {
			return CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
		}
		double v = TileSpots.rowMiddle(mapV);
		if (this.otherDimension != null) {
			// No chunks here, and the world's save is for where the player is.
			return this.shownMap(session).savedSpot(this.layer, u, v, looks, false);
		}
		TileKey tile = new TileKey(this.layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		MapPick pick = new MapPick(session.map(), looks, tile, session.map().fogOfWar());
		return switch (pick.pick(u, v)) {
			case MapPick.FOUND -> CompletableFuture.completedFuture(new MapLevel.Spot(Projection.facing().worldPos(new BlockPos((int) Math.floor(u), pick.y, pick.z)), false));
			case MapPick.NOTHING -> CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
			default -> session.map().savedSpot(this.layer, u, v, looks, readWorld);
		};
	}

	/** The height most ground is near, for a point the map knows nothing about. */
	private static final int USUAL_GROUND_Y = ClaimProjection.SURFACE_Y;

	/**
	 * Roughly where a screen point is when the map can't say. The camera is tilted, so a point alone
	 * gives x but not z: z is off by about 0.8 of a block for each block the real ground is from this guess.
	 */
	private @Nullable BlockPos roughSpot(double screenX, double screenY) {
		MapViewport.View view = this.lastView;
		if (view == null) {
			return null;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		return ClaimProjection.spot(u, v, this.layer);
	}

	private List<MapMenu.Item> spotItems(MapLevel.Spot found, @Nullable BlockPos rough) {
		BlockPos spot = found.pos();
		if (spot == null) {
			List<MapMenu.Item> unknown = new ArrayList<>();
			// Drawn by Diorama 0.3.0 or older, with no save to read the ground from: it comes back when the player does.
			unknown.add(MapMenu.Item.info(Component.translatable(found.unsaved() ? "diorama.menu.not_saved" : "diorama.menu.not_mapped")));
			if (rough != null) {
				// The distance would be across two dimensions.
				if (this.otherDimension == null) {
					unknown.add(MapMenu.Item.info(this.roughly(rough.getX(), rough.getZ())));
				}
				unknown.add(this.copyItem(rough.getX() + " " + rough.getZ()));
			}
			this.addClaimItems(unknown, rough);
			return unknown;
		}
		int x = spot.getX();
		int y = spot.getY();
		int z = spot.getZ();
		String dimension = this.dimension();
		List<MapMenu.Item> items = new ArrayList<>();
		items.add(MapMenu.Item.info(this.where(x, y, z)));
		items.add(MapMenu.Item.action(Component.translatable("diorama.menu.teleport_here"), Waypoints.canTeleport(dimension), () -> {
			Waypoints.teleport(dimension, x, y, z);
			this.minecraft.gui.setScreen(null);
		}));
		if (Settings.get().mapAddWaypoints) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.add_waypoint"), true, () -> {
				Waypoint waypoint = Waypoints.at(spot, dimension);
				if (waypoint != null) {
					this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, true));
				}
			}));
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.add_temporary"), true, () -> Waypoints.addTemporary(spot, dimension)));
		}
		this.addClaimItems(items, rough);
		items.add(this.copyItem(x + " " + y + " " + z));
		// Chat can't take it back, so sharing asks twice.
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.menu.share_location"), Component.translatable("diorama.menu.share_confirm"), () -> {
			Waypoint location = new Waypoint(Component.translatable("diorama.menu.shared_location").getString(), x, y, z, dimension, Waypoint.PALETTE[0]);
			if (WaypointShare.send(location)) {
				this.minecraft.gui.setScreen(null);
			}
		}));
		return items;
	}

	/** OPAC itself validates permissions, including party and server claiming modes. */
	private void addClaimItems(List<MapMenu.Item> items, @Nullable BlockPos position) {
		if (position == null || this.otherDimension != null || !OpacClaims.available(this.minecraft)) {
			return;
		}
		int cx = position.getX() >> 4, cz = position.getZ() >> 4;
		Claims.Claim claim = Claims.get().at(cx, cz);
		if (claim == null) {
			items.add(MapMenu.Item.action(Component.literal("OPAC: Claim chunk"), true,
				() -> OpacClaims.requestArea(this.minecraft, cx, cz, cx, cz, OpacClaims.Action.CLAIM)));
		} else {
			items.add(MapMenu.Item.info(Component.literal("OPAC: " + claim.name()).append(Component.translatable("diorama.map.claim.chunk", cx, cz))));
			items.add(MapMenu.Item.action(Component.literal("OPAC: Unclaim chunk"), true,
				() -> OpacClaims.requestArea(this.minecraft, cx, cz, cx, cz, OpacClaims.Action.UNCLAIM)));
			items.add(MapMenu.Item.action(Component.literal(claim.forceLoaded() ? "OPAC: Unforce-load chunk" : "OPAC: Force-load chunk"), true,
				() -> OpacClaims.requestArea(this.minecraft, cx, cz, cx, cz,
					claim.forceLoaded() ? OpacClaims.Action.UNFORCELOAD : OpacClaims.Action.FORCELOAD)));
		}
	}


	/**
	 * A pin's menu. For another dimension's waypoint shown on this map, the name, position and
	 * copied coordinates are the pin's, here; everything else acts on the waypoint itself, so
	 * Teleport takes the player to it in its own dimension, as Xaero's Minimap does.
	 */
	private List<MapMenu.Item> waypointItems(Waypoint shown) {
		Waypoint waypoint = shown.source();
		List<MapMenu.Item> items = new ArrayList<>();
		items.add(MapMenu.Item.title(Component.literal(shown.shownName())));
		items.add(MapMenu.Item.info(this.where(shown.x, shown.y, shown.z)));
		if (waypoint.editable()) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.edit"), true, () ->
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false))));
		}
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoint.teleport"), Waypoints.canTeleport(waypoint.dimension), () -> {
			Waypoints.teleport(waypoint.dimension, waypoint.x, waypoint.y, waypoint.z);
			this.minecraft.gui.setScreen(null);
		}));
		items.add(this.copyItem(shown.x + " " + shown.y + " " + shown.z));
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.share"), Component.translatable("diorama.menu.share_confirm"), () -> {
			if (WaypointShare.send(waypoint)) {
				this.minecraft.gui.setScreen(null);
			}
		}));
		if (waypoint.kind == Waypoint.Kind.TEMPORARY) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.keep"), true, () -> {
				waypoint.kind = Waypoint.Kind.MANUAL;
				this.changed();
			}));
		}
		if (waypoint.kind != Waypoint.Kind.LIVE) {
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.hide"), true, () -> {
			WaypointStore store = WaypointStore.get();
			if (store != null) {
				store.setVisible(waypoint, false);
			}
		}));
		}
		if (waypoint.editable()) {
			items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.delete"), Component.translatable("diorama.menu.delete_confirm"), () -> {
				WaypointStore store = WaypointStore.get();
				if (store != null) {
					store.remove(waypoint);
				}
			}));
		}
		return items;
	}

	/** A line putting {@code coordinates} on the clipboard, spaced the way commands take them. */
	private MapMenu.Item copyItem(String coordinates) {
		return MapMenu.Item.stays(Component.translatable("diorama.menu.copy_coordinates"), Component.translatable("diorama.menu.copied"),
			() -> this.minecraft.keyboardHandler.setClipboard(coordinates));
	}

	private void changed() {
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.changed();
		}
	}

	/** "120, 64, -340 · 212 m": a block position and how far the player is from it, if they're in this dimension. */
	private Component where(int x, int y, int z) {
		if (this.otherDimension != null) {
			return Component.translatable("diorama.map.spot", x, y, z);
		}
		Entity viewer = MapSession.viewer();
		long distance = viewer == null ? 0 : Math.round(Math.sqrt(viewer.distanceToSqr(x + 0.5, y, z + 0.5)));
		return Component.translatable("diorama.menu.where", x, y, z, distance);
	}

	/** "About 120, -340 · 212 m": a point whose height isn't known, and how far the player is from it across the ground. */
	private Component roughly(int x, int z) {
		Entity viewer = MapSession.viewer();
		long distance = viewer == null ? 0 : Math.round(Math.hypot(viewer.getX() - (x + 0.5), viewer.getZ() - (z + 0.5)));
		return Component.translatable("diorama.menu.where_rough", x, z, distance);
	}

	/** A drag; false leaves it to the screen's widgets. */
	private boolean dragged(double x, double y, int button, double dx, double dy) {
		if (this.pressButton == -1) {
			// No press of this screen's: it was reset (a resize rebuilt the screen mid-drag).
			return false;
		}
		if (Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			this.dragged = true;
		}
		if (this.pressOnMenu) {
			return true;
		}
		if (this.gauge.dragging()) {
			if (this.minecraft.level != null) {
				this.gauge.drag(this.minecraft.level, y);
			}
			return true;
		}
		if (this.pressOnControl) {
			return false;
		}
		if (this.frontierEditor.active() && this.lastView != null
			&& this.frontierEditor.drag(this.lastView, x, y)) return true;
		if (this.claimSelecting) {
			this.claimEndX = x;
			this.claimEndY = y;
			return true;
		}
		if (this.draw.active() && !this.draw.pans(button)) {
			if (this.lastView != null) {
				this.draw.drag(this.lastView, this.font, this.dimension(), x, y);
			}
			return true;
		}
		// While drawing, the right and middle buttons pan too.
		if ((button == InputConstants.MOUSE_BUTTON_LEFT || this.draw.active()) && this.dragged) {
			// The menu stays where it opened, so it can't follow the map.
			this.menu = null;
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return false;
	}

	// The mouse wheel had only the one axis on 1.20.1.
	@Override
	//? if >=1.21 {
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
	//?} else {
	/*public boolean mouseScrolled(double x, double y, double scrollY) {
	*///?}
		this.menu = null;
		double before = this.zoom;
		double after = Math.max(this.minZoom(), Math.min(MAX_ZOOM, before * Math.pow(1.25, scrollY)));
		if (after != before) {
			// Keep the map point under the cursor where it is.
			double mapU = this.centerU + (x - this.width / 2.0) / before;
			double mapV = this.centerV + (y - this.height / 2.0) / before;
			this.centerU = mapU - (x - this.width / 2.0) / after;
			this.centerV = mapV - (y - this.height / 2.0) / after;
			this.zoom = after;
			if (this.following) {
				this.following = false;
			}
		}
		return true;
	}

	/** A zoom button: zooms about the middle of the screen, so the map keeps following the player. */
	private void zoomButton(int steps) {
		this.menu = null;
		this.zoom = Math.max(this.minZoom(), Math.min(MAX_ZOOM, this.zoom * Math.pow(1.25, steps)));
	}

	/** A key press, and whether it is bound to the waypoint list or the map; false leaves it to the screen. */
	private boolean keyDown(int key, boolean waypointListKey, boolean mapKey, boolean dimensionKey) {
		if (key == InputConstants.KEY_ESCAPE && this.menu != null) {
			this.menu = null;
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.frontierEditor.active()) {
			this.frontierEditor.stop();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.claimSelecting) {
			this.cancelClaimSelection();
			return true;
		}
		if (this.draw.typing()) {
			// Typing a note: only Enter and Esc are the map's, the rest are the note's.
			return this.draw.key(key, this.dimension());
		}
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (this.bindingPressed(waypointListKey, mapKey, dimensionKey)) {
			return true;
		}
		if (this.draw.key(key, this.dimension())) {
			return true;
		}
		Entity viewer = MapSession.viewer();
		// Each of these changes the view, which an open menu belongs to, so they close it.
		switch (key) {
			case InputConstants.KEY_C -> {
				this.toggleCaves();
				return true;
				}
			case InputConstants.KEY_PAGEUP -> {
				this.menu = null;
				this.shiftSlice(4, viewer);
				return true;
				}
			case InputConstants.KEY_PAGEDOWN -> {
				this.menu = null;
				this.shiftSlice(-4, viewer);
				return true;
				}
			case InputConstants.KEY_SPACE -> {
				this.menu = null;
				this.following = true;
				return true;
				}
			default -> {
			}
		}
		return false;
	}

	private boolean bindingPressed(boolean waypointListKey, boolean mapKey, boolean dimensionKey) {
		if (dimensionKey) {
			this.nextDimension();
			return true;
		}
		if (waypointListKey) {
			this.openWaypoints();
			return true;
		}
		if (mapKey) {
			this.onClose();
			return true;
		}
		return false;
	}

	/** The waypoint list, on the folder of the dimension shown. */
	private void openWaypoints() {
		WaypointListScreen list = new WaypointListScreen(this);
		if (this.otherDimension != null) {
			list.showDimension(this.otherDimension);
		}
		this.minecraft.gui.setScreen(list);
	}

	/** Between the surface and the slice at the player's feet, as C and the tabs do. */
	private void toggleCaves() {
		this.menu = null;
		if (this.otherDimension != null || !ServerRules.caveMapsAllowed()) {
			return;
		}
		this.gauge.cancel();
		Entity viewer = MapSession.viewer();
		this.layer = this.layer == TileKey.SURFACE && viewer != null ? playerSlice(viewer) : TileKey.SURFACE;
		this.layerPicked = true;
	}

	/** The slice the minimap shows for the viewer underground, so C and the slice keys start where it is. */
	private static int playerSlice(Entity viewer) {
		MapSession session = MapSession.get();
		return session != null ? session.caveLayer(viewer) : MapSession.caveLayerFor(viewer.getY());
	}

	private void shiftSlice(int delta, @Nullable Entity viewer) {
		if (this.otherDimension != null || !ServerRules.caveMapsAllowed()) {
			return;
		}
		if (this.layer == TileKey.SURFACE) {
			if (viewer == null) {
				return;
			}
			this.layer = playerSlice(viewer);
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
		this.layerPicked = true;
	}

	/**
	 * Lays out the waypoint pins with the edge ones kept clear of the bars, the depth gauge and the
	 * zoom buttons: pins inside the view stay where they are.
	 */
	private List<WaypointOverlay.Pin> layoutPins(MapViewport.View view, List<Waypoint> waypoints) {
		int top = BAR + 1;
		int bottom = this.height - BOTTOM_BAR - 1;
		int right = this.width - (this.layer != TileKey.SURFACE ? 60 : 0);
		// The same map under a smaller view: points land where they do in the full one.
		MapViewport.View clear = new MapViewport.View(0, top, right, bottom - top,
			view.centerU() + (right / 2.0 - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + ((top + bottom) / 2.0 - view.y() - view.height() / 2.0) / view.zoom(),
			view.zoom(), view.layer());
		List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(clear, waypoints, true);
		// The column of buttons, and the facing button left of recenter.
		int bx0 = (this.facingButton.visible ? this.facingButton.getX() : this.zoomIn.getX()) - 2;
		// From the export (or draw) button down to recenter.
		int by0 = (this.export.visible ? this.export.getY() : this.drawButton.getY()) - 2;
		int bx1 = this.zoomIn.getX() + this.zoomIn.getWidth() + 2;
		int by1 = this.recenter.getY() + this.recenter.getHeight() + 2;
		int dx1 = this.dimensionButton.getX() + this.dimensionButton.getWidth() + 2;
		int dy0 = this.dimensionButton.getY() - 2;
		List<WaypointOverlay.Pin> out = new ArrayList<>(pins.size());
		for (WaypointOverlay.Pin pin : pins) {
			float r = Pins.reach(pin.waypoint(), (int) pin.size()) * pin.scale();
			// Initials of three or four letters make the badge wider than it's tall.
			float rx = r + Pins.stretch(pin.waypoint(), (int) pin.size()) * pin.scale() / 2.0F;
			if (pin.clamped() && pin.x() + rx > bx0 && pin.x() - rx < bx1 && pin.y() + r > by0 && pin.y() - r < by1) {
				// An edge pin behind the zoom buttons moves the shorter way out: above them or left.
				float up = pin.y() + r - by0;
				float left = pin.x() + rx - bx0;
				pin = up <= left
					? new WaypointOverlay.Pin(pin.waypoint(), pin.x(), by0 - r, pin.size(), pin.scale(), true)
					: new WaypointOverlay.Pin(pin.waypoint(), bx0 - rx, pin.y(), pin.size(), pin.scale(), true);
			}
			if (this.dimensionButton.visible && pin.clamped() && pin.x() - rx < dx1 && pin.y() + r > dy0) {
				// And behind the dimension button, above it or right.
				float up = pin.y() + r - dy0;
				float across = dx1 - (pin.x() - rx);
				pin = up <= across
					? new WaypointOverlay.Pin(pin.waypoint(), pin.x(), dy0 - r, pin.size(), pin.scale(), true)
					: new WaypointOverlay.Pin(pin.waypoint(), dx1 + rx, pin.y(), pin.size(), pin.scale(), true);
			}
			out.add(pin);
		}
		return out;
	}

	/** Surface and Caves as the game's tabs, the open one taller and underlined. */
	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = 2;
		for (int i = 0; i < this.tabs(); i++) {
			boolean selected = (i == 1) == (this.layer != TileKey.SURFACE);
			String name = TAB_NAMES[i].getString();
			int nameWidth = this.font.width(name);
			int w = nameWidth + 13;
			int h = selected ? BAR + 2 : BAR;
			boolean hovered = this.menu == null && mouseX >= x && mouseX < x + w && mouseY >= 0 && mouseY < h;
			sprite(graphics, selected ? (hovered ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED) : (hovered ? TAB_HIGHLIGHTED : TAB), x, 0, w, h);
			if (selected) {
				// The selected tab's sprite is open in the middle; the game puts its menu backdrop there.
				// 1.20.1's tab texture is filled in, so it needs none.
				//? if >=1.21 {
				graphics.fill(x + 2, 2, x + w - 2, h, 0x40000000);
				//?}
				int underline = nameWidth - 1;
				graphics.fill(x + (w - underline) / 2, h - 3, x + (w - underline) / 2 + underline, h - 2, 0xFFFFFFFF);
			}
			graphics.text(this.font, name, x + w / 2 - nameWidth / 2, selected ? 4 : 6, selected ? 0xFFFFFFFF : 0xFFE0E0E0, true);
			this.tabX[i] = x;
			this.tabWidth[i] = w;
			x += w;
		}
		this.tabsEnd = x;
	}

	/**
	 * How many tabs show: cave slices aren't saved, so another dimension's map has only Surface, as
	 * does a server that turns cave maps off.
	 */
	private int tabs() {
		return this.otherDimension == null && ServerRules.caveMapsAllowed() ? 2 : 1;
	}

	/** The tab under a point: 0 Surface, 1 Caves, -1 neither. */
	private int tabAt(double x, double y) {
		for (int i = 0; i < this.tabs(); i++) {
			if (x >= this.tabX[i] && x < this.tabX[i] + this.tabWidth[i] && y >= 0 && y < BAR) {
				return i;
			}
		}
		return -1;
	}

	/** One of the game's tabs, as the map's Surface and Caves are drawn. */
	static void tab(GuiGraphicsExtractor graphics, boolean selected, boolean hovered, int x, int y, int w, int h) {
		sprite(graphics, selected ? (hovered ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED) : (hovered ? TAB_HIGHLIGHTED : TAB), x, y, w, h);
	}

	private static void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int w, int h) {
		//? if >=1.21.6 {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
		//?} else if >=1.21.2 {
		/*graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h);
		*///?} else if >=1.21 {
		/*graphics.blitSprite(sprite, x, y, w, h);
		*///?} else {
		/*// From 1.20.1's tab texture, one 24 pixel row per state. Its tabs are always 24 tall; these
		// are shorter, so they take the top h rows and slice only the 2 pixel sides (1.20.1's
		// nine-slice divides by the bottom border when the height differs, and the tabs have none).
		int row = sprite == TAB_SELECTED ? 0 : sprite == TAB_SELECTED_HIGHLIGHTED ? 1 : sprite == TAB ? 2 : 3;
		graphics.blitNineSliced(TAB_BUTTON, x, y, w, h, 2, 2, 2, 0, 130, h, 0, row * 24);
		*///?}
	}

	/** The dimension button's face: its icon, or for a modded dimension the first letter of its name. */
	private void drawDimensionIcon(GuiGraphicsExtractor graphics) {
		int x = this.dimensionButton.getX();
		int y = this.dimensionButton.getY();
		String[] icon = switch (this.dimension()) {
			case "minecraft:overworld" -> OVERWORLD_ICON;
			case "minecraft:the_nether" -> NETHER_ICON;
			case "minecraft:the_end" -> END_ICON;
			default -> null;
		};
		if (icon != null) {
			DrawMode.icon(graphics, icon, x, y, 0xFFFFFFFF);
			return;
		}
		String name = this.dimensionName().getString();
		String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
		graphics.text(this.font, letter, x + 10 - this.font.width(letter) / 2, y + 6, 0xFFFFFFFF, true);
	}

	/** The Overworld on the dimension button: a tree, 11 by 9. */
	private static final String[] OVERWORLD_ICON = {
		"....###....",
		"..#######..",
		".#########.",
		".#########.",
		"..#######..",
		"....###....",
		".....#.....",
		".....#.....",
		"....###...."
	};

	/** The Nether on the dimension button: a flame, 11 by 9. */
	private static final String[] NETHER_ICON = {
		".....#.....",
		"....##.....",
		"....###.#..",
		"...#####...",
		"..#######..",
		".####.####.",
		".###...###.",
		"..##...##..",
		"...#####..."
	};

	/** The End on the dimension button: an eye, 11 by 7. */
	private static final String[] END_ICON = {
		"...#####...",
		".##.....##.",
		"#...###...#",
		"#..#####..#",
		"#...###...#",
		".##.....##.",
		"...#####..."
	};

	/** The Export button's icon: a framed picture of a hill under the sun, 11 by 9. */
	private static final String[] PICTURE = {
		"###########",
		"#.........#",
		"#......##.#",
		"#......##.#",
		"#...#.....#",
		"#..#.#....#",
		"#.#...#.#.#",
		"##.....#.##",
		"###########"
	};

	/** A small up or down arrow on a slice button, grey when it can't go further. */
	private void caret(GuiGraphicsExtractor graphics, Button button, boolean down) {
		int colour = button.active ? 0xFFFFFFFF : 0xFFA0A0A0;
		int cx = button.getX() + button.getWidth() / 2;
		int y = button.getY() + (button.getHeight() - 3) / 2;
		for (int row = 0; row < 3; row++) {
			int half = down ? 2 - row : row;
			graphics.fill(cx - half, y + row, cx + half + 1, y + row + 1, colour);
		}
	}

	/** Whether a point is on the bars or a control, where the map's own tooltips don't show. */
	private boolean overControls(double x, double y) {
		if (y < BAR || y >= this.height - BOTTOM_BAR) {
			return true;
		}
		return this.onButton(x, y) || this.onContextPanel(x, y) || this.layer != TileKey.SURFACE && this.gauge.contains(x, y) || this.draw.onControls(x, y);
	}

	private boolean onContextPanel(double x, double y) {
		return this.contextRight > this.contextLeft && x >= this.contextLeft - 4 && x < this.contextRight + 3
			&& y >= this.contextTop - 3 && y < this.contextBottom + 3;
	}

	/** Whether a point is on a shown button, whether or not it can be pressed right now. */
	private boolean onButton(double x, double y) {
		for (AbstractWidget widget : new AbstractWidget[] {this.facingButton, this.dimensionButton, this.mapsButton, this.waypointsButton, this.settingsButton, this.claimsButton, this.opacMenuButton, this.opacEditButton, this.opacToolButton, this.frontierButton, this.frontierVisibilityButton, this.territoriesButton, this.frontierNewButton, this.frontierAddButton, this.frontierRenameButton, this.frontierDeleteButton, this.frontierSaveButton, this.frontierCancelButton, this.export, this.zoomIn, this.zoomOut, this.recenter, this.sliceUp, this.sliceDown, this.drawButton}) {
			if (on(widget, x, y)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a point is on a widget that's shown; unlike isMouseOver, also when it's inactive. */
	private static boolean on(AbstractWidget widget, double x, double y) {
		return widget.visible && x >= widget.getX() && x < widget.getX() + widget.getWidth() && y >= widget.getY() && y < widget.getY() + widget.getHeight();
	}

	/** A control's tooltip and where the control is. */
	private record Tip(List<FormattedCharSequence> lines, ScreenRectangle control) {
	}

	/** What a control does and its key: the player's binding where there is one. */
	private @Nullable Tip controlTooltip(double x, double y) {
		int tab = this.tabAt(x, y);
		if (tab >= 0) {
			return new Tip(lines(TAB_NAMES[tab], key(keyName(InputConstants.KEY_C))), new ScreenRectangle(this.tabX[tab], 0, this.tabWidth[tab], BAR));
		}
		if (on(this.dimensionButton, x, y)) {
			return tip(this.dimensionButton, this.dimensionName(), DioramaClient.TOGGLE_DIMENSION.isUnbound()
				? Component.translatable("diorama.map.dimension_button")
				: Component.translatable("diorama.map.dimension_button_key", DioramaClient.TOGGLE_DIMENSION.getTranslatedKeyMessage()));
		}
		if (on(this.waypointsButton, x, y) && !DioramaClient.WAYPOINT_LIST.isUnbound()) {
			return tip(this.waypointsButton, this.waypointsButton.getMessage(), key(DioramaClient.WAYPOINT_LIST.getTranslatedKeyMessage()));
		}
		if (on(this.export, x, y)) {
			return new Tip(List.of(Component.translatable("diorama.export.title").getVisualOrderText()),
				new ScreenRectangle(this.export.getX(), this.export.getY(), this.export.getWidth(), this.export.getHeight()));
		}
		if (on(this.zoomIn, x, y)) {
			return tip(this.zoomIn, Component.translatable("diorama.map.zoom_in"), Component.translatable("diorama.map.scroll_up"));
		}
		if (on(this.zoomOut, x, y)) {
			return tip(this.zoomOut, Component.translatable("diorama.map.zoom_out"), Component.translatable("diorama.map.scroll_down"));
		}
		if (on(this.facingButton, x, y)) {
			MapSession session = MapSession.get();
			boolean walk = session != null && session.map().memory() != null && session.savedChunks() == null;
			return tip(this.facingButton, Component.translatable("diorama.map.facing." + Projection.facing().id()),
				Component.translatable(walk ? "diorama.map.facing_walk" : "diorama.map.facing_turn"));
		}
		if (on(this.recenter, x, y)) {
			return tip(this.recenter, Component.translatable("diorama.map.recenter"), key(keyName(InputConstants.KEY_SPACE)));
		}
		if (on(this.drawButton, x, y)) {
			return tip(this.drawButton, Component.translatable("diorama.draw.button"), Component.translatable(this.draw.active() ? "diorama.draw.button.stop" : "diorama.draw.button.start"));
		}
		for (Map.Entry<Button, SideControl> entry : this.sideControls.entrySet()) {
			Button button = entry.getKey();
			if (on(button, x, y)) {
				return new Tip(List.of(entry.getValue().label().get().getVisualOrderText()),
					new ScreenRectangle(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
			}
		}
		if (on(this.sliceUp, x, y)) {
			return tip(this.sliceUp, Component.translatable("diorama.map.slice_up"), key(keyName(InputConstants.KEY_PAGEUP)));
		}
		if (on(this.sliceDown, x, y)) {
			return tip(this.sliceDown, Component.translatable("diorama.map.slice_down"), key(keyName(InputConstants.KEY_PAGEDOWN)));
		}
		return null;
	}

	private static Tip tip(AbstractWidget control, Component name, Component second) {
		return new Tip(lines(name, second), new ScreenRectangle(control.getX(), control.getY(), control.getWidth(), control.getHeight()));
	}

	/** "-382, 62, 825" over "River · 146 m" for the block under an empty spot of the map. */
	private @Nullable List<FormattedCharSequence> spotTooltip(int mouseX, int mouseY) {
		MapViewport.View view = this.lastView;
		Entity viewer = MapSession.viewer();
		if (view == null || viewer == null) {
			return null;
		}
		// The pick walks a ray through the map's blocks; do it again only for another block.
		double u = view.centerU() + (mouseX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (mouseY - view.y() - view.height() / 2.0) / view.zoom();
		int blockU = (int) Math.floor(u);
		int blockV = (int) Math.floor(v);
		boolean moved = blockU != this.spotU || blockV != this.spotV || this.layer != this.spotLayer;
		if (moved || ++this.spotAge > 10) {
			this.spotU = blockU;
			this.spotV = blockV;
			this.spotLayer = this.layer;
			this.spotAge = 0;
			// Not from the world's save: that loads chunks on the server, too much for every block the mouse crosses.
			CompletableFuture<@Nullable BlockPos> found = this.spotAt(mouseX, mouseY, false).thenApply(MapLevel.Spot::pos);
			Object request = new Object();
			this.spotRequest = request;
			if (found.isDone()) {
				this.spot = found.getNow(null);
			} else {
				// From a saved tile: the old answer stands meanwhile if the mouse is still on its block.
				if (moved) {
					this.spot = null;
				}
				found.thenAcceptAsync(spot -> {
					if (this.spotRequest == request) {
						this.spot = spot;
					}
				}, this.minecraft);
			}
		}
		BlockPos spot = this.spot;
		if (spot == null) {
			List<FormattedCharSequence> claim = this.withClaimAt(List.of(), mouseX, mouseY);
			return claim.isEmpty() ? null : claim;
		}
		if (this.otherDimension != null) {
			return List.of(Component.translatable("diorama.map.spot", spot.getX(), spot.getY(), spot.getZ()).getVisualOrderText());
		}
		long distance = Math.round(Math.sqrt(viewer.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)));
		Component biome = this.biomeAt(spot);
		Component detail = biome != null
			? Component.translatable("diorama.map.spot_detail", biome, distance)
			: Component.translatable("diorama.map.distance", distance);
		return this.withClaimAt(lines(Component.translatable("diorama.map.spot", spot.getX(), spot.getY(), spot.getZ()), detail), mouseX, mouseY);
	}

	/** Add the claim rectangle under the cursor, including when terrain height is unavailable. */
	private List<FormattedCharSequence> withClaimAt(Component described, int mouseX, int mouseY) {
		return this.withClaimAt(List.of(described.getVisualOrderText()), mouseX, mouseY);
	}

	private List<FormattedCharSequence> withClaimAt(List<FormattedCharSequence> lines, int mouseX, int mouseY) {
		if (this.otherDimension != null || Settings.get().claims == Settings.ClaimsShown.OFF) return lines;
		BlockPos at = this.roughSpot(mouseX, mouseY);
		if (at == null) return lines;
		int cx = at.getX() >> 4, cz = at.getZ() >> 4;
		Claims.Claim claim = Claims.get().at(cx, cz);
		if (claim == null) return lines;
		List<FormattedCharSequence> both = new ArrayList<>(lines);
		both.add(claimLine(claim).copy().append(Component.translatable("diorama.map.claim.chunk", cx, cz)).getVisualOrderText());
		return both;
	}

	/** The text of the tooltip the map itself showed at the mouse last frame, a line each; empty with none. Public for tests. */
	public List<String> tooltipText() {
		List<String> text = new ArrayList<>();
		for (FormattedCharSequence line : this.tooltipLines == null ? List.<FormattedCharSequence>of() : this.tooltipLines) {
			StringBuilder builder = new StringBuilder();
			line.accept((index, style, codePoint) -> {
				builder.appendCodePoint(codePoint);
				return true;
			});
			text.add(builder.toString());
		}
		return text;
	}

	/** Who owns the chunk under the mouse: a square of their colour, then their name, or the claim's own title. */
	private static Component claimLine(Claims.Claim claim) {
		if (claim.titled()) {
			return Component.literal("■ ").withStyle(s -> s.withColor(claim.rgb()))
				.append(Component.literal(claim.name()).withStyle(ChatFormatting.GRAY));
		}
		Component owner = claim.name().isEmpty() ? Component.translatable("diorama.map.claim.unnamed") : Component.literal(claim.name());
		Component line = Component.literal("■ ").withStyle(s -> s.withColor(claim.rgb()))
			.append(Component.translatable("diorama.map.claim", owner).withStyle(ChatFormatting.GRAY));
		return claim.forceLoaded() ? line.copy().append(Component.translatable("diorama.map.claim.force_loaded").withStyle(ChatFormatting.GRAY)) : line;
	}

	/** The biome at a spot, where the game has its chunk loaded; null elsewhere. Public for tests. */
	public @Nullable Component biomeAt(BlockPos pos) {
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return null;
		}
		// The game's lookup picks the biome from one of the cells around the spot, and near a chunk's
		// edge that cell can be in the next chunk. Where that one isn't loaded, the game's stand-in
		// chunk answers, all Plains. So every chunk a candidate cell is in has to be loaded. Ask the
		// chunk source: the client level answers that every chunk is loaded (#262).
		int cellX = (pos.getX() - 2) >> 2;
		int cellZ = (pos.getZ() - 2) >> 2;
		for (int chunkX = cellX >> 2; chunkX <= ((cellX + 1) >> 2); chunkX++) {
			for (int chunkZ = cellZ >> 2; chunkZ <= ((cellZ + 1) >> 2); chunkZ++) {
				if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
					return null;
				}
			}
		}
		return level.getBiome(pos).unwrapKey()
			.map(k -> (Component) Component.translatableWithFallback(k.identifier().toLanguageKey("biome"), k.identifier().getPath()))
			.orElse(null);
	}

	/** A tooltip: a white first line and a grey second one. */
	private static List<FormattedCharSequence> lines(Component first, Component second) {
		return List.of(first.getVisualOrderText(), second.copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
	}

	private static Component key(Component name) {
		return Component.translatable("diorama.map.key", name);
	}

	/** A fixed key's name in the player's keyboard layout and language. */
	private static Component keyName(int key) {
		//? if >=26.3 {
		return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName();
		//?} else {
		/*return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName();
		*///?}
	}

	/** For tests on versions without a tick freeze: the map pauses a singleplayer world while it's open. */
	private static boolean pausesForTests;

	/** For tests: see {@link #pausesForTests}. */
	public static void pauseForTests(boolean pause) {
		pausesForTests = pause;
	}

	@Override
	public boolean isPauseScreen() {
		return pausesForTests;
	}

	@Override
	public void removed() {
		this.cancelClaimSelection();
		this.viewport.close();
		this.draw.close();
		this.closeOtherMap();
	}

	/** The dimension the map shows. */
	public String dimension() {
		if (this.otherDimension != null) {
			return this.otherDimension;
		}
		return this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
	}

	/** For tests: draw mode. */
	public DrawMode drawMode() {
		return this.draw;
	}

	/** For tests: the button that turns draw mode on and off. */
	public Button drawButton() {
		return this.drawButton;
	}

	/** For tests: the button that steps through the dimensions. */
	public Button dimensionButton() {
		return this.dimensionButton;
	}

	/** For tests: the saved map of the dimension shown, if it isn't the player's. */
	public @Nullable MapLevel otherMap() {
		return this.otherMap == null ? null : this.otherMap.map();
	}

	/** For tests: the view drawn last frame. */
	public MapViewport.@Nullable View view() {
		return this.lastView;
	}

	/** For tests: the waypoint pins drawn last frame, in GUI coordinates. */
	public List<WaypointOverlay.Pin> pins() {
		return this.pins;
	}

	/** For tests: the Export button. */
	public Button exportButton() {
		return this.export;
	}

	/** For tests: the right-click menu, if it's open. */
	public @Nullable MapMenu menu() {
		return this.menu;
	}

	/** For tests: the middle of the depth gauge's slice band last frame, in GUI coordinates; null on the surface. */
	public float @Nullable [] sliceBand() {
		return this.layer == TileKey.SURFACE ? null : this.gauge.band();
	}

	/** For tests: the radar markers drawn last frame, in GUI coordinates. */
	public List<Radar.Blip> blips() {
		return this.blips;
	}

	/** The {@link Overlays} bits to draw, from the settings. */
	private static int overlayBits(MapSession session) {
		Settings s = Settings.get();
		int bits = 0;
		if (s.chunkGrid) {
			bits |= Overlays.CHUNK_WEST | Overlays.CHUNK_NORTH;
		}
		if (s.slimeChunks && session.slimeSeed() != null) {
			bits |= Overlays.SLIME;
		}
		if (s.spawnSpots != Settings.SpawnSpots.OFF) {
			bits |= Overlays.SPAWN_ANY_TIME;
		}
		if (s.spawnSpots == Settings.SpawnSpots.AT_NIGHT_TOO) {
			bits |= Overlays.SPAWN_AT_NIGHT;
		}
		return bits;
	}

	/** Cave slices and unsaved maps have no overview tiles, so they stop at the detailed zoom. */
	/**
	 * Follows the player between the surface and caves, until they pick a layer themselves. Not
	 * while looking at another dimension, which the player isn't standing in. As on the minimap,
	 * the layer on screen stays until the new one has its tiles, or for {@link #FOLLOW_HOLD_MS},
	 * so climbing past a slice edge doesn't swap a map for fog.
	 */
	private void followLayer(MapSession session, Entity viewer) {
		if (!ServerRules.caveMapsAllowed()) {
			this.layer = TileKey.SURFACE;
		}
		if (this.layerPicked || this.otherDimension != null || !Settings.get().autoCaves || !ServerRules.caveMapsAllowed()) {
			this.followHoldStart = -1;
			return;
		}
		int auto = session.autoLayer(viewer);
		if (auto == this.layer) {
			this.followHoldStart = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (this.followHoldStart < 0) {
			this.followHoldStart = now;
		}
		MapLevel level = session.map();
		MapViewport.View wanted = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, Math.max(this.minZoom(auto), this.zoom), auto);
		MapViewport.View current = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		// The old layer only helps if it has something to show: after a teleport it doesn't.
		if (MapViewport.ready(level, wanted) || now - this.followHoldStart > FOLLOW_HOLD_MS || !MapViewport.showsAnything(level, current)) {
			this.layer = auto;
			this.followHoldStart = -1;
			// A menu belongs to the view it was opened on, as when the player changes layer.
			this.menu = null;
		}
	}

	private double minZoom() {
		return this.minZoom(this.layer);
	}

	private double minZoom(int layer) {
		MapSession session = MapSession.get();
		boolean overviews = layer == TileKey.SURFACE && session != null && (this.otherDimension != null || session.map().hasOverviews());
		return overviews ? MIN_SCREEN_ZOOM / net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale() : MapViewport.DETAILED_ZOOM;
	}

	/** For tests: the zoom the map last drew at, in GUI pixels per block. */
	public double zoom() {
		return this.zoom;
	}

	/** For tests: put the view at a spot without following the player. */
	public void lookAt(double u, double v, double zoom, int layer) {
		this.centerU = u;
		this.centerV = v;
		this.zoom = zoom;
		this.layer = layer;
		this.layerPicked = true;
		this.following = false;
	}
}