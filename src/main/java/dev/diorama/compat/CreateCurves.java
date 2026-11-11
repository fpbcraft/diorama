package dev.diorama.compat;

import dev.diorama.Projection;
import dev.diorama.Diorama;
import dev.diorama.Settings;
import dev.diorama.api.MapAnnotations;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.BlockLooks;
import dev.diorama.render.Circle;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * Draws Create's Bezier track sections that have no corresponding block model in Diorama's
 * chunk snapshots. Create synchronizes its track graph to clients, including these curves.
 * Reflection keeps this integration optional and independent of Create's compile-time API.
 *
 * <p>The graph is sampled on the client thread periodically, not from the map renderer. Samples
 * are kept in memory for the current connection and rendered as thin rails on the surface map.
 */
public final class CreateCurves implements MapAnnotations.Source {
    private static final int SAMPLE_TICKS = 80;
    private static final int MAX_EDGES = 30_000;
    private static final int MAX_SAMPLES_PER_EDGE = 192;
    private static final int MAX_RENDER_SEGMENTS = 12_000;
    private static final int DEFAULT_SLEEPER = 0xFF765E48;
    private static final int STEEL_RAIL = 0xFFD2D2D0;
    private static final int GOLD_RAIL = 0xFFEAC36B;

    static record Palette(int sleeper, int metal, float gauge, boolean monorail, boolean invisible) {
    }

    /** A strip of world-space curve with transverse normals, sampled at Create's tie spacing. */
    static record Segment(Vec3 from, Vec3 to, Vec3 across, Vec3 up, Palette palette, boolean girder) {
    }

    private Map<String, List<Segment>> segments = Map.of();
    private final CurveRaster pixels = new CurveRaster();
    private int ticks;
    private Object world;
    private BlockLooks lastLooks;
    private final Map<Object, Palette> materialColours = new IdentityHashMap<>();
    private final Object railways;
    private final Field graphsField;
    private final Method nodesMethod;
    private final Method locateNodeMethod;
    private final Method connectionsFromMethod;
    private final Method locationDimensionMethod;
    private final Method isTurnMethod;
    private final Method isInterDimensionalMethod;
    private final Method nodeIdMethod;
    private final Field edgeEndField;
    private final Method getTurnMethod;
    private final Field primaryField;
    private final Field hasGirderField;
    private final Method trackMaterialMethod;
    private final Method edgeLengthMethod;
    private final Method edgePositionMethod;
    private final Method edgeNormalMethod;
    private final Method turnSegmentCountMethod;
    private final Method turnSegmentTimeMethod;

    private CreateCurves(Object railways, Field graphsField, Method nodesMethod, Method locateNodeMethod,
        Method connectionsFromMethod, Method locationDimensionMethod, Method isTurnMethod, Method isInterDimensionalMethod,
        Method nodeIdMethod, Field edgeEndField, Method getTurnMethod,
        Field primaryField, Field hasGirderField, Method trackMaterialMethod, Method edgeLengthMethod, Method edgePositionMethod,
        Method edgeNormalMethod, Method turnSegmentCountMethod, Method turnSegmentTimeMethod) {
        this.railways = railways;
        this.graphsField = graphsField;
        this.nodesMethod = nodesMethod;
        this.locateNodeMethod = locateNodeMethod;
        this.connectionsFromMethod = connectionsFromMethod;
        this.locationDimensionMethod = locationDimensionMethod;
        this.isTurnMethod = isTurnMethod;
        this.isInterDimensionalMethod = isInterDimensionalMethod;
        this.nodeIdMethod = nodeIdMethod;
        this.edgeEndField = edgeEndField;
        this.getTurnMethod = getTurnMethod;
        this.primaryField = primaryField;
        this.hasGirderField = hasGirderField;
        this.trackMaterialMethod = trackMaterialMethod;
        this.edgeLengthMethod = edgeLengthMethod;
        this.edgePositionMethod = edgePositionMethod;
        this.edgeNormalMethod = edgeNormalMethod;
        this.turnSegmentCountMethod = turnSegmentCountMethod;
        this.turnSegmentTimeMethod = turnSegmentTimeMethod;
    }

    /** Register only when Create's NeoForge client railway graph API is available. */
    public static void start() {
        try {
            Class<?> createClient = Class.forName("com.simibubi.create.CreateClient");
            Object railways = createClient.getField("RAILWAYS").get(null);
            Class<?> graph = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph");
            Class<?> location = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation");
            Class<?> node = Class.forName("com.simibubi.create.content.trains.graph.TrackNode");
            Class<?> edge = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge");
            Class<?> bezier = Class.forName("com.simibubi.create.content.trains.track.BezierConnection");
            MapAnnotations.register(new CreateCurves(railways,
                railways.getClass().getField("trackNetworks"),
                graph.getMethod("getNodes"),
                graph.getMethod("locateNode", location),
                graph.getMethod("getConnectionsFrom", node),
                location.getMethod("getDimension"),
                edge.getMethod("isTurn"),
                edge.getMethod("isInterDimensional"),
                node.getMethod("getNetId"),
                edge.getField("node2"),
                edge.getMethod("getTurn"),
                bezier.getField("primary"),
                bezier.getField("hasGirder"),
                edge.getMethod("getTrackMaterial"),
                edge.getMethod("getLength"),
                edge.getMethod("getPosition", graph, double.class),
                edge.getMethod("getNormal", graph, double.class),
                bezier.getMethod("getSegmentCount"),
                bezier.getMethod("getSegmentT", int.class)));
                    Diorama.LOGGER.info("[Diorama] Create curved-track provider registered");
        } catch (ClassNotFoundException ignored) {
            // Create is not installed: no optional provider.
        } catch (ReflectiveOperationException | LinkageError exception) {
            Diorama.LOGGER.warn("[Diorama] Could not register Create curved-track renderer", exception);
        }
    }

    @Override
    public void tick(Minecraft minecraft) {
        if (minecraft.level == null) {
            this.world = null;
            this.segments = Map.of();
            this.materialColours.clear();
            this.pixels.clear();
            return;
        }
        BlockLooks looks = BlockLooks.get();
        if (this.lastLooks != looks) {
            this.lastLooks = looks;
            this.materialColours.clear();
            this.pixels.clear();
            this.ticks = 0;
        }
        if (this.world != minecraft.level) {
            this.world = minecraft.level;
            this.ticks = 0;
            this.segments = Map.of();
            this.pixels.clear();
        }
        if (--this.ticks > 0) {
            return;
        }
        this.ticks = SAMPLE_TICKS;
        try {
            Object network = this.graphsField.get(this.railways);
            if (!(network instanceof Map<?, ?> graphs)) {
                return;
            }
            Map<String, List<Segment>> next = new HashMap<>();
            int edges = 0;
            for (Object graph : graphs.values()) {
                if (!(this.nodesMethod.invoke(graph) instanceof Iterable<?> nodes)) {
                    continue;
                }
                for (Object location : nodes) {
                    Object node = this.locateNodeMethod.invoke(graph, location);
                    Object found = this.connectionsFromMethod.invoke(graph, node);
                    if (!(found instanceof Map<?, ?> connections)) {
                        continue;
                    }
                    for (Object edge : connections.values()) {
                        if (++edges > MAX_EDGES) {
                            this.segments = Map.copyOf(next);
                            this.pixels.update(this.segments);
                            return;
                        }
                        if (Boolean.TRUE.equals(this.isInterDimensionalMethod.invoke(edge))) {
                            // Interdimensional portals have no actual straight rail geometry.
                            continue;
                        }
                        boolean curved = Boolean.TRUE.equals(this.isTurnMethod.invoke(edge));
                        Object turn = curved ? this.getTurnMethod.invoke(edge) : null;
                        boolean girder = false;
                        if (curved) {
                            if (turn == null || !this.primaryField.getBoolean(turn)) {
                                // Every Bezier edge has a mirrored secondary connection.
                                continue;
                            }
                            girder = this.hasGirderField.getBoolean(turn);
                        } else {
                            // A straight edge is stored in *both* directions in Create's
                            // graph. Only rasterize the canonical one.
                            Object other = this.edgeEndField.get(edge);
                            int fromId = ((Number) this.nodeIdMethod.invoke(node)).intValue();
                            int toId = ((Number) this.nodeIdMethod.invoke(other)).intValue();
                            if (fromId >= toId) {
                                continue;
                            }
                        }
                        Object material = this.trackMaterialMethod.invoke(edge);
                        Palette palette = this.materialColours.get(material);
                        if (palette == null) {
                            try {
                                palette = paletteFor(material, looks);
                            } catch (ReflectiveOperationException | RuntimeException exception) {
                                Diorama.LOGGER.warn("Could not read Create track material {}; using neutral rail palette", material, exception);
                                palette = new Palette(DEFAULT_SLEEPER, STEEL_RAIL, 1, false, false);
                            }
                            this.materialColours.put(material, palette);
                        }
                        if (palette.invisible()) {
                            continue;
                        }
                        String dimension = dimensionName(this.locationDimensionMethod.invoke(location));
                        double length = ((Number) this.edgeLengthMethod.invoke(edge)).doubleValue();
                        // Match Create's own Bezier segment count: one tie per rendered section.
                        // The limit prevents malformed third-party graphs from overwhelming the GUI.
                        // Bezier curves already carry an arc-length lookup; straight edges
                        // use equally spaced half-block samples. Both flow into the same
                        // Diorama-style rasterizer, with the same palettes and gauge.
                        int nativeSegments = curved
                            ? Math.max(1, ((Number) this.turnSegmentCountMethod.invoke(turn)).intValue())
                            : Math.max(1, (int) Math.ceil(length * 2.0));
                        int count = Math.min(MAX_SAMPLES_PER_EDGE, nativeSegments);
                        List<Segment> list = next.computeIfAbsent(dimension, ignored -> new ArrayList<>());
                        Vec3 previous = (Vec3) this.edgePositionMethod.invoke(edge, graph, 0.0);
                        double previousT = 0;
                        for (int i = 1; i <= count; i++) {
                            // Create's own arc-length lookup places sleepers at near-equal
                            // distances on tight bends; uniform i/count bunches them up.
                            // For exceptionally long curves, decimate *across the full edge*,
                            // never truncate its last sections when limiting draw complexity.
                            int sample = (int) Math.round(i * (double) nativeSegments / count);
                            double t = curved
                                ? ((Number) this.turnSegmentTimeMethod.invoke(turn, sample)).doubleValue()
                                : sample / (double) nativeSegments;
                            Vec3 current = (Vec3) this.edgePositionMethod.invoke(edge, graph, t);
                            Vec3 up = (Vec3) this.edgeNormalMethod.invoke(edge, graph, (previousT + t) * .5);
                            Vec3 tangent = current.subtract(previous);
                            Vec3 across = tangent.cross(up);
                            if (across.lengthSqr() < 1e-6) {
                                across = new Vec3(-tangent.z, 0, tangent.x);
                            }
                            if (across.lengthSqr() >= 1e-6) {
                                list.add(new Segment(previous, current, across.normalize(), up.normalize(), palette, girder));
                            }
                            previous = current;
                            previousT = t;
                        }
                    }
                }
            }
            this.segments = Map.copyOf(next);
            this.pixels.update(this.segments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Create's synchronized railway graph", exception);
        }
    }

    /**
     * The track graph sends the same material on straight and Bezier edges. Steam 'n' Rails
     * registers wood-specific sleeper ingredients, so we sample their *actual block textures*
     * through Diorama's BlockLooks table instead of assigning arbitrary oak/spruce colours.
     * This also respects resource packs and works with other add-ons' registered materials.
     */
    private static Palette paletteFor(Object material, BlockLooks looks) throws ReflectiveOperationException {
        if (material == null) {
            return new Palette(DEFAULT_SLEEPER, STEEL_RAIL, 1, false, false);
        }
        Object id = material.getClass().getField("id").get(material);
        String name = String.valueOf(id);
        if (name.contains("phantom")) {
            return new Palette(0, 0, 1, false, true);
        }

        Object type = material.getClass().getField("trackType").get(material);
        String gaugeType = String.valueOf(type.getClass().getField("id").get(type));
        boolean monorail = gaugeType.endsWith(":monorail");
        float gauge = gaugeType.endsWith(":wide_gauge") ? 1.5f
            : gaugeType.endsWith(":narrow_gauge") ? 0.72f : 1f;

        // Steam 'n' Rails' wide/narrow variants inherit models and particle textures
        // from a base track, but their recipe ingredients may be empty. Use the base
        // material for colours, preserving the actual gauge from this edge.
        Object base = inheritedMaterial(material, name);
        int sleeper = 0;
        if (looks != null) {
            Object sleeperIngredient = base.getClass().getField("sleeperIngredient").get(base);
            if (sleeperIngredient instanceof Ingredient ingredient) {
                sleeper = colourFromIngredient(looks, ingredient);
            }
            // Exotic track materials (monorail, bamboo, andesite) may have no block
            // sleeper; use their own rendered track-block palette in that case.
            if (sleeper == 0 && material.getClass().getMethod("getBlock").invoke(material) instanceof Block block) {
                sleeper = colourOf(looks, block);
            }
        }
        if (sleeper == 0) {
            sleeper = DEFAULT_SLEEPER;
        }
        int rail = STEEL_RAIL;
        Object railIngredient = base.getClass().getField("railsIngredient").get(base);
        if (railIngredient instanceof Ingredient ingredient) {
            if (ingredient.test(new ItemStack(Items.GOLD_NUGGET))) {
                rail = GOLD_RAIL;
            } else if (looks != null) {
                int fromBlock = colourFromIngredient(looks, ingredient);
                if (fromBlock != 0) {
                    rail = fromBlock;
                }
            }
        }
        return new Palette(0xEF000000 | sleeper & 0xFFFFFF, 0xF5000000 | rail & 0xFFFFFF,
            gauge, monorail, false);
    }

    /**
     * Railways' registered gauge variants can be railways:spruce_wide,
     * railways:spruce_narrow or railways:create_andesite_wide. Their registered
     * base material carries the sleeper/rail ingredients.
     */
    private static Object inheritedMaterial(Object material, String name) throws ReflectiveOperationException {
        if (!name.endsWith("_wide") && !name.endsWith("_narrow")) {
            return material;
        }
        int colon = name.indexOf(':');
        if (colon <= 0) {
            return material;
        }
        String namespace = name.substring(0, colon);
        String path = name.substring(colon + 1);
        String basePath = path.substring(0, path.lastIndexOf('_'));
        String standard = namespace + ":" + basePath;
        String fromOtherNamespace = "";
        int firstUnderscore = basePath.indexOf('_');
        if (firstUnderscore > 0) {
            fromOtherNamespace = basePath.substring(0, firstUnderscore) + ":"
                + basePath.substring(firstUnderscore + 1);
        }

        Object registered = material.getClass().getField("ALL").get(null);
        if (registered instanceof Map<?, ?> materials) {
            for (Object candidate : materials.values()) {
                String key = String.valueOf(candidate.getClass().getField("id").get(candidate));
                if (key.equals(standard) || key.equals(fromOtherNamespace)) {
                    return candidate;
                }
            }
        }
        return material;
    }

    private static int colourFromIngredient(BlockLooks looks, Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.getItem() instanceof BlockItem blockItem) {
                int colour = colourOf(looks, blockItem.getBlock());
                if (colour != 0) {
                    return colour;
                }
            }
        }
        return 0;
    }

    private static int colourOf(BlockLooks looks, Block block) {
        int index = looks.lookId(block.defaultBlockState());
        int colour = looks.top[index];
        return colour != 0 ? colour : looks.topTinted[index];
    }

    private static String dimensionName(Object key) throws ReflectiveOperationException {
        try {
            return String.valueOf(key.getClass().getMethod("identifier").invoke(key));
        } catch (NoSuchMethodException ignored) {
            return String.valueOf(key.getClass().getMethod("location").invoke(key));
        }
    }

    /**
     * Static curves are baked onto Diorama's 4-pixels-per-block map grid and rendered with
     * the same tile sampling as terrain. See CurveRaster for girder support and occlusion.
     */
    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        if (Settings.get().showCreateCurves && view.layer() == TileKey.SURFACE) {
            this.pixels.draw(graphics, view, dimension);
        }
    }

}
