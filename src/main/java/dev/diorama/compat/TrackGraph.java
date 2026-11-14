package dev.diorama.compat;

import dev.diorama.Diorama;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/** Optional Create graph sampler shared by client and server. Call snapshot on the owning game thread. */
public final class TrackGraph {
    private static final int MAX_EDGES = 30_000;
    private static final int MAX_SAMPLES_PER_EDGE = 192;
    private static final int MAX_RENDER_SEGMENTS = 100_000;
    private static final int DEFAULT_SLEEPER = 0xFF765E48;
    private static final int STEEL_RAIL = 0xFFD2D2D0;
    private static final int GOLD_RAIL = 0xFFEAC36B;

    public record Palette(Paint sleeper, Paint metal, float gauge, boolean monorail, boolean invisible) {
    }

    public record Paint(int rgb, List<Block> blocks) {
        static Paint solid(int rgb) { return new Paint(rgb, List.of()); }
    }

    /** A strip of world-space curve with transverse normals, sampled at Create's tie spacing. */
    public record Segment(Vec3 from, Vec3 to, Vec3 across, Vec3 up, Palette palette, boolean girder, boolean straight) {
    }

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

    private TrackGraph(Object railways, Field graphsField, Method nodesMethod, Method locateNodeMethod,
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

    public static TrackGraph open(String owner) {
        try {
            Class<?> create = Class.forName(owner);
            Object railways = create.getField("RAILWAYS").get(null);
            Class<?> graph = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph");
            Class<?> location = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation");
            Class<?> node = Class.forName("com.simibubi.create.content.trains.graph.TrackNode");
            Class<?> edge = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge");
            Class<?> bezier = Class.forName("com.simibubi.create.content.trains.track.BezierConnection");
            return new TrackGraph(railways,
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
                bezier.getMethod("getSegmentT", int.class));

        } catch (ClassNotFoundException ignored) {
            // Create is not installed: no optional provider.
        } catch (ReflectiveOperationException | LinkageError exception) {
            Diorama.LOGGER.warn("[Diorama] Could not register Create curved-track renderer", exception);
        }
        return null;
    }

    public Map<String,List<Segment>> snapshot() {
        try {
            Object network = this.graphsField.get(this.railways);
            if (!(network instanceof Map<?, ?> graphs)) {
                return Map.of();
            }
            Map<String, List<Segment>> next = new HashMap<>();
            int edges = 0;
            int samples = 0;
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
                            return freeze(next);
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
                                palette = paletteFor(material);
                            } catch (ReflectiveOperationException | RuntimeException exception) {
                                Diorama.LOGGER.warn("Could not read Create track material {}; using neutral rail palette", material, exception);
                                palette = new Palette(Paint.solid(DEFAULT_SLEEPER), Paint.solid(STEEL_RAIL), 1, false, false);
                            }
                            this.materialColours.put(material, palette);
                        }
                        if (palette.invisible()) {
                            continue;
                        }
                        String dimension = dimensionName(this.locationDimensionMethod.invoke(location));
                        double length = ((Number) this.edgeLengthMethod.invoke(edge)).doubleValue();
                        // Sample all Create graph edges, straight and curved, at matching sleeper spacing.
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
                                list.add(new Segment(previous, current, across.normalize(), up.normalize(), palette, girder, !curved));
                                if (++samples >= MAX_RENDER_SEGMENTS) {
                                    // Cap world-wide graph snapshots so a very large modpack
                                    // cannot freeze the client when reconstructing rails.
                                    return freeze(next);
                                }
                            }
                            previous = current;
                            previousT = t;
                        }
                    }
                }
            }
            return freeze(next);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Create's synchronized railway graph", exception);
        }
    }

    /**
     * The track graph sends the same material on straight and Bezier edges. Steam 'n' Rails
     * registers wood-specific sleeper ingredients, so we sample their actual block textures
     * through the viewer's Looks table instead of assigning arbitrary oak/spruce colours.
     * This also respects resource packs and works with other add-ons' registered materials.
     */
    private static Palette paletteFor(Object material) throws ReflectiveOperationException {
        if (material == null) {
            return new Palette(Paint.solid(DEFAULT_SLEEPER), Paint.solid(STEEL_RAIL), 1, false, false);
        }
        Object id = material.getClass().getField("id").get(material);
        String name = String.valueOf(id);
        if (name.contains("phantom")) {
            return new Palette(Paint.solid(0), Paint.solid(0), 1, false, true);
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
        List<Block> sleepers = new ArrayList<>();
        Object sleeperIngredient = base.getClass().getField("sleeperIngredient").get(base);
        if (sleeperIngredient instanceof Ingredient ingredient) sleepers.addAll(blocksFromIngredient(ingredient));
        if (material.getClass().getMethod("getBlock").invoke(material) instanceof Block block) sleepers.add(block);
        Paint sleeper = new Paint(DEFAULT_SLEEPER, List.copyOf(sleepers));
        Paint rail = Paint.solid(STEEL_RAIL);
        Object railIngredient = base.getClass().getField("railsIngredient").get(base);
        if (railIngredient instanceof Ingredient ingredient) {
            if (ingredient.test(new ItemStack(Items.GOLD_NUGGET))) rail = Paint.solid(GOLD_RAIL);
            else rail = new Paint(STEEL_RAIL, blocksFromIngredient(ingredient));
        }
        return new Palette(sleeper, rail, gauge, monorail, false);
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

    private static List<Block> blocksFromIngredient(Ingredient ingredient) {
        List<Block> blocks = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) if (stack.getItem() instanceof BlockItem blockItem) blocks.add(blockItem.getBlock());
        return List.copyOf(blocks);
    }

    private static String dimensionName(Object key) throws ReflectiveOperationException {
        try {
            return String.valueOf(key.getClass().getMethod("identifier").invoke(key));
        } catch (NoSuchMethodException ignored) {
            return String.valueOf(key.getClass().getMethod("location").invoke(key));
        }
    }

    private static Map<String,List<Segment>> freeze(Map<String,List<Segment>> input) {
        var result=new HashMap<String,List<Segment>>();input.forEach((id,rows)->result.put(id,List.copyOf(rows)));return Map.copyOf(result);
    }
}
