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
    private static final int MAX_SAMPLES_PER_EDGE = 72;
    private static final int MAX_RENDER_SEGMENTS = 12_000;
    private static final int DEFAULT_SLEEPER = 0xFF765E48;
    private static final int STEEL_RAIL = 0xFFD2D2D0;
    private static final int GOLD_RAIL = 0xFFEAC36B;

    private record Palette(int sleeper, int metal, float gauge, boolean monorail, boolean invisible) {
    }

    /** Each segment carries its own material colours, including Steam 'n' Rails variants. */
    private record Segment(Vec3 from, Vec3 to, Palette palette) {
    }

    private Map<String, List<Segment>> segments = Map.of();
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
    private final Method getTurnMethod;
    private final Field primaryField;
    private final Method trackMaterialMethod;
    private final Method edgeLengthMethod;
    private final Method edgePositionMethod;

    private CreateCurves(Object railways, Field graphsField, Method nodesMethod, Method locateNodeMethod,
        Method connectionsFromMethod, Method locationDimensionMethod, Method isTurnMethod, Method getTurnMethod,
        Field primaryField, Method trackMaterialMethod, Method edgeLengthMethod, Method edgePositionMethod) {
        this.railways = railways;
        this.graphsField = graphsField;
        this.nodesMethod = nodesMethod;
        this.locateNodeMethod = locateNodeMethod;
        this.connectionsFromMethod = connectionsFromMethod;
        this.locationDimensionMethod = locationDimensionMethod;
        this.isTurnMethod = isTurnMethod;
        this.getTurnMethod = getTurnMethod;
        this.primaryField = primaryField;
        this.trackMaterialMethod = trackMaterialMethod;
        this.edgeLengthMethod = edgeLengthMethod;
        this.edgePositionMethod = edgePositionMethod;
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
                edge.getMethod("getTurn"),
                bezier.getField("primary"),
                edge.getMethod("getTrackMaterial"),
                edge.getMethod("getLength"),
                edge.getMethod("getPosition", graph, double.class)));
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
            return;
        }
        BlockLooks looks = BlockLooks.get();
        if (this.lastLooks != looks) {
            this.lastLooks = looks;
            this.materialColours.clear();
            this.ticks = 0;
        }
        if (this.world != minecraft.level) {
            this.world = minecraft.level;
            this.ticks = 0;
            this.segments = Map.of();
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
                            return;
                        }
                        if (!Boolean.TRUE.equals(this.isTurnMethod.invoke(edge))) {
                            continue;
                        }
                        Object turn = this.getTurnMethod.invoke(edge);
                        if (turn == null || !this.primaryField.getBoolean(turn)) {
                            continue;
                        }
                        Object material = this.trackMaterialMethod.invoke(edge);
                        Palette palette = this.materialColours.get(material);
                        if (palette == null) {
                            palette = paletteFor(material, looks);
                            this.materialColours.put(material, palette);
                        }
                        if (palette.invisible()) {
                            continue;
                        }
                        String dimension = dimensionName(this.locationDimensionMethod.invoke(location));
                        double length = ((Number) this.edgeLengthMethod.invoke(edge)).doubleValue();
                        int count = Math.max(6, Math.min(MAX_SAMPLES_PER_EDGE, (int) Math.ceil(length / 2.0)));
                        List<Segment> list = next.computeIfAbsent(dimension, ignored -> new ArrayList<>());
                        Vec3 previous = (Vec3) this.edgePositionMethod.invoke(edge, graph, 0.0);
                        for (int i = 1; i <= count; i++) {
                            Vec3 current = (Vec3) this.edgePositionMethod.invoke(edge, graph, i / (double) count);
                            list.add(new Segment(previous, current, palette));
                            previous = current;
                        }
                    }
                }
            }
            this.segments = Map.copyOf(next);
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

        int sleeper = 0;
        if (looks != null) {
            Object sleeperIngredient = material.getClass().getField("sleeperIngredient").get(material);
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
        Object railIngredient = material.getClass().getField("railsIngredient").get(material);
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

    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        if (!Settings.get().showCreateCurves || view.layer() != TileKey.SURFACE || view.zoom() < 0.22) {
            return;
        }
        List<Segment> lines = this.segments.get(dimension);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Circle circle = view.round() ? Circle.in(view) : null;
        MapCanvas canvas = MapCanvas.of(graphics);
        // Draw a wood/stone sleeper body and the two rails. Using the material on each
        // individual edge allows differently coloured curves to join without hard-coded
        // Steam 'n' Rails material names.
        int budget = Math.min(lines.size(), MAX_RENDER_SEGMENTS);
        float[] sleeperVertices = circle == null ? new float[budget * 12] : null;
        float[] leftVertices = circle == null ? new float[budget * 12] : null;
        float[] rightVertices = circle == null ? new float[budget * 12] : null;
        int[] sleeperColours = circle == null ? new int[budget] : null;
        int[] railColours = circle == null ? new int[budget] : null;
        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        int drawn = 0;
        for (Segment line : lines) {
            float x0 = view.screenX(Projection.u(line.from.x, line.from.z));
            float y0 = view.screenY(Projection.v(line.from.x, line.from.y, line.from.z));
            float x1 = view.screenX(Projection.u(line.to.x, line.to.z));
            float y1 = view.screenY(Projection.v(line.to.x, line.to.y, line.to.z));
            if (Math.max(x0, x1) < view.x() - 5 || Math.min(x0, x1) > view.x() + view.width() + 5
                || Math.max(y0, y1) < view.y() - 5 || Math.min(y0, y1) > view.y() + view.height() + 5) {
                continue;
            }
            if (drawn >= MAX_RENDER_SEGMENTS) {
                break;
            }
            Palette material = line.palette();
            float width = (float) Math.max(1.8, Math.min(12, view.zoom() * 2.5 * material.gauge()));
            float railWidth = Math.max(0.55f, width * 0.16f);
            float offset = material.monorail() ? 0 : width * 0.30f;
            if (circle != null) {
                stroke(canvas, circle, x0, y0, x1, y1, width, material.sleeper(), 0);
                stroke(canvas, circle, x0, y0, x1, y1, railWidth, material.metal(), -offset);
                if (!material.monorail()) {
                    stroke(canvas, circle, x0, y0, x1, y1, railWidth, material.metal(), offset);
                }
            } else {
                float[] sleeper = vertices(x0, y0, x1, y1, width, 0);
                float[] left = vertices(x0, y0, x1, y1, railWidth, -offset);
                float[] right = material.monorail() ? null : vertices(x0, y0, x1, y1, railWidth, offset);
                if (sleeper == null || left == null) {
                    continue;
                }
                System.arraycopy(sleeper, 0, sleeperVertices, drawn * 12, 12);
                System.arraycopy(left, 0, leftVertices, drawn * 12, 12);
                if (right != null) {
                    System.arraycopy(right, 0, rightVertices, drawn * 12, 12);
                } else {
                    System.arraycopy(left, 0, rightVertices, drawn * 12, 12);
                }
                sleeperColours[drawn] = material.sleeper();
                railColours[drawn] = material.metal();
                drawn++;
            }
        }
        if (drawn > 0) {
            canvas.solids(sleeperVertices, sleeperColours, drawn);
            canvas.solids(leftVertices, railColours, drawn);
            canvas.solids(rightVertices, railColours, drawn);
        }
        graphics.disableScissor();
    }

    /** Quad centered at an offset across the rail, so two rails share a sleeper body. */
    private static float[] vertices(float x0, float y0, float x1, float y1, float width, float offset) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.hypot(dx, dy);
        if (length < 0.0001f) {
            return null;
        }
        float nx = -dy / length;
        float ny = dx / length;
        float a = offset + width / 2;
        float b = offset - width / 2;
        return new float[] {
            x0 + nx * a, y0 + ny * a, 0,
            x0 + nx * b, y0 + ny * b, 0,
            x1 + nx * b, y1 + ny * b, 0,
            x1 + nx * a, y1 + ny * a, 0
        };
    }

    private static void stroke(MapCanvas canvas, Circle circle, float x0, float y0, float x1, float y1,
        float width, int colour, float offset) {
        float[] quad = vertices(x0, y0, x1, y1, width, offset);
        if (quad == null) {
            return;
        }
        float[] flat = {
            quad[0], quad[1], quad[3], quad[4],
            quad[6], quad[7], quad[9], quad[10]
        };
        float[] clipped = circle.clip(flat, 4);
        if (clipped != null) {
            canvas.solid(clipped, colour);
        }
    }

}
