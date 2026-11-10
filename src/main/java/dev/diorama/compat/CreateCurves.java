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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;

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

    private record Palette(int sleeper, int metal, float gauge, boolean monorail, boolean invisible,
        TextureAtlasSprite tieSprite, TextureAtlasSprite railSprite) {
    }

    /** A strip of world-space curve with transverse normals, sampled at Create's tie spacing. */
    private record Segment(Vec3 from, Vec3 to, Vec3 across, Palette palette) {
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
    private final Method edgeNormalMethod;
    private final Method turnSegmentCountMethod;
    private final Method turnSegmentTimeMethod;

    private CreateCurves(Object railways, Field graphsField, Method nodesMethod, Method locateNodeMethod,
        Method connectionsFromMethod, Method locationDimensionMethod, Method isTurnMethod, Method getTurnMethod,
        Field primaryField, Method trackMaterialMethod, Method edgeLengthMethod, Method edgePositionMethod,
        Method edgeNormalMethod, Method turnSegmentCountMethod, Method turnSegmentTimeMethod) {
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
                edge.getMethod("getTurn"),
                bezier.getField("primary"),
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
                            try {
                                palette = paletteFor(material, looks);
                            } catch (ReflectiveOperationException | RuntimeException exception) {
                                Diorama.LOGGER.warn("Could not read Create track material {}; using neutral rail palette", material, exception);
                                palette = new Palette(DEFAULT_SLEEPER, STEEL_RAIL, 1, false, false, null, null);
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
                        int count = Math.max(1, Math.min(MAX_SAMPLES_PER_EDGE,
                            ((Number) this.turnSegmentCountMethod.invoke(turn)).intValue()));
                        List<Segment> list = next.computeIfAbsent(dimension, ignored -> new ArrayList<>());
                        Vec3 previous = (Vec3) this.edgePositionMethod.invoke(edge, graph, 0.0);
                        double previousT = 0;
                        for (int i = 1; i <= count; i++) {
                            // Create's own arc-length lookup places sleepers at near-equal
                            // distances on tight bends; uniform i/count bunches them up.
                            double t = ((Number) this.turnSegmentTimeMethod.invoke(turn, i)).doubleValue();
                            Vec3 current = (Vec3) this.edgePositionMethod.invoke(edge, graph, t);
                            Vec3 up = (Vec3) this.edgeNormalMethod.invoke(edge, graph, (previousT + t) * .5);
                            Vec3 tangent = current.subtract(previous);
                            Vec3 across = tangent.cross(up);
                            if (across.lengthSqr() < 1e-6) {
                                across = new Vec3(-tangent.z, 0, tangent.x);
                            }
                            if (across.lengthSqr() >= 1e-6) {
                                list.add(new Segment(previous, current, across.normalize(), palette));
                            }
                            previous = current;
                            previousT = t;
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
            return new Palette(DEFAULT_SLEEPER, STEEL_RAIL, 1, false, false, null, null);
        }
        Object id = material.getClass().getField("id").get(material);
        String name = String.valueOf(id);
        if (name.contains("phantom")) {
            return new Palette(0, 0, 1, false, true, null, null);
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
        TextureAtlasSprite ties = partialSprite(material, "tie");
        if (ties == null && base != material) ties = partialSprite(base, "tie");
        TextureAtlasSprite rails = partialSprite(material, "leftSegment");
        if (rails == null && base != material) rails = partialSprite(base, "leftSegment");
        return new Palette(0xEF000000 | sleeper & 0xFFFFFF, 0xF5000000 | rail & 0xFFFFFF,
            gauge, monorail, false, ties, rails);
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

    /**
     * Read a representative *actual texture sprite* from Create's partial models. The
     * Steam 'n' Rails model holder supplies the wood tie and metal rail textures used
     * by the in-world renderer; its wide/narrow variants may reuse a base material.
     * This is optional: exotic renderers can fall back to BlockLooks colours.
     */
    private static TextureAtlasSprite partialSprite(Object material, String part) {
        try {
            Object holder = material.getClass().getMethod("getModelHolder").invoke(material);
            if (holder == null) return null;
            Object partial = holder.getClass().getMethod(part).invoke(holder);
            if (partial == null) return null;
            Object model = partial.getClass().getMethod("get").invoke(partial);
            if (model == null) return null;
            // Prefer the real top-face quads; particle textures can be misleading for
            // complex Steam 'n' Rails track models. NeoForge may expose 3- or 5-argument
            // BakedModel#getQuads, depending on the installed renderer.
            for (Method method : model.getClass().getMethods()) {
                if (!method.getName().equals("getQuads")) continue;
                int parameters = method.getParameterCount();
                if (parameters != 3 && parameters != 5) continue;
                try {
                    Object[] args = parameters == 3
                        ? new Object[] {null, null, RandomSource.create(42L)}
                        : new Object[] {null, null, RandomSource.create(42L),
                            Class.forName("net.neoforged.neoforge.client.model.data.ModelData")
                                .getField("EMPTY").get(null),
                            Class.forName("net.minecraft.client.renderer.RenderType")
                                .getMethod("cutoutMipped").invoke(null)};
                    Object quads = method.invoke(model, args);
                    if (quads instanceof Iterable<?> iterable) {
                        for (Object quad : iterable) {
                            Object sprite = quad.getClass().getMethod("getSprite").invoke(quad);
                            if (sprite instanceof TextureAtlasSprite texture) return texture;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try another overload or particle sprite.
                }
            }
            for (Method method : model.getClass().getMethods()) {
                if (!method.getName().equals("getParticleIcon")) continue;
                try {
                    Object sprite = method.getParameterCount() == 0 ? method.invoke(model)
                        : method.getParameterCount() == 1
                        ? method.invoke(model, Class.forName("net.neoforged.neoforge.client.model.data.ModelData")
                            .getField("EMPTY").get(null)) : null;
                    if (sprite instanceof TextureAtlasSprite texture) return texture;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Other modded partial-model variant.
                }
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
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
     * Draw the actual curved-track layout: discrete transverse ties at Create's half-block
     * spacing plus separate left/right steel rails. A Steam 'n' Rails partial-model sprite
     * textures each section when available. The block atlas is batched into a few GUI draws.
     *
     * Unlike the previous screen-space ribbon, widths are measured in world units using
     * the Bezier tangent and surface normal. Banking, map angle and gauge therefore stay
     * consistent as the map rotates or zooms.
     */
    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        if (!Settings.get().showCreateCurves || view.layer() != TileKey.SURFACE || view.zoom() < 0.22) {
            return;
        }
        List<Segment> lines = this.segments.get(dimension);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int budget = Math.min(lines.size(), MAX_RENDER_SEGMENTS);
        MapCanvas canvas = MapCanvas.of(graphics);
        Circle circle = view.round() ? Circle.in(view) : null;
        // The circular minimap draws clipped solid quads immediately. Avoid allocating
        // fullscreen-sized GPU batches every frame when they will remain empty.
        QuadBatch ties = new QuadBatch(circle == null ? budget : 0);
        QuadBatch railBeds = new QuadBatch(circle == null ? budget * 2 : 0);
        QuadBatch rails = new QuadBatch(circle == null ? budget * 2 : 0);
        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        int drawn = 0;
        for (Segment line : lines) {
            if (drawn >= MAX_RENDER_SEGMENTS) break;
            Palette p = line.palette();
            double gauge = p.gauge();
            Vec3 from = line.from();
            Vec3 to = line.to();
            Vec3 transverse = line.across();
            Vec3 diff = to.subtract(from);
            if (diff.lengthSqr() < 1e-7) continue;

            // A separate tie for each of Create's native Bezier sections. It is recessed
            // below the rail heads just as its partial model is in TrackRenderer.
            Vec3 middle = from.add(to).scale(.5).add(0, -.125, 0);
            Vec3 acrossTie = transverse.scale(1.25 * gauge);
            Vec3 halfLength = diff.normalize().scale(Math.min(.17, diff.length() * .33));
            Vec3[] tie = {
                middle.subtract(halfLength).add(acrossTie),
                middle.subtract(halfLength).subtract(acrossTie),
                middle.add(halfLength).subtract(acrossTie),
                middle.add(halfLength).add(acrossTie)
            };

            // First screen-cull the tie (which bounds this short rail section).
            float[] tieQuad = projected(view, tie);
            if (!onScreen(view, tieQuad, 7)) continue;
            append(canvas, circle, ties, tieQuad, p.tieSprite(), p.sleeper());

            // Sleeper gaps now reveal terrain, unlike the old solid wooden ribbon.
            // The rails follow the exact Bezier in world space; two slim overlapping
            // quads suggest the dark rail side and the material's brighter upper face.
            double offset = .965 * gauge;
            double railWidth = .18 * gauge;
            for (int side = -1; side <= 1; side += 2) {
                if (p.monorail() && side == 1) continue;
                double shift = p.monorail() ? 0 : offset * side;
                float[] bedQuad = projected(view, strip(from, to, transverse, shift, railWidth + .065 * gauge));
                append(canvas, circle, railBeds, bedQuad, null, 0xDB343537);
                Vec3 lift = new Vec3(0, .045, 0);
                float[] railQuad = projected(view,
                    strip(from.add(lift), to.add(lift), transverse, shift, railWidth));
                append(canvas, circle, rails, railQuad, p.railSprite(), p.metal());
            }
            drawn++;
        }
        // Tie texture is shown under the rails; all textures sample the resource-pack
        // block atlas directly without creating or reloading extra GPU textures.
        ties.flush(canvas);
        railBeds.flush(canvas);
        rails.flush(canvas);
        graphics.disableScissor();
    }

    private static Vec3[] strip(Vec3 a, Vec3 b, Vec3 cross, double offset, double width) {
        Vec3 outer = cross.scale(offset + width / 2);
        Vec3 inner = cross.scale(offset - width / 2);
        return new Vec3[] {a.add(outer), a.add(inner), b.add(inner), b.add(outer)};
    }

    private static float[] projected(MapViewport.View view, Vec3[] quad) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            Vec3 pos = quad[i];
            out[2 * i] = view.screenX(Projection.u(pos.x, pos.z));
            out[2 * i + 1] = view.screenY(Projection.v(pos.x, pos.y, pos.z));
        }
        return out;
    }

    private static boolean onScreen(MapViewport.View view, float[] points, int margin) {
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 2) {
            left = Math.min(left, points[i]);
            right = Math.max(right, points[i]);
            top = Math.min(top, points[i + 1]);
            bottom = Math.max(bottom, points[i + 1]);
        }
        return right >= view.x() - margin && left <= view.x() + view.width() + margin
            && bottom >= view.y() - margin && top <= view.y() + view.height() + margin;
    }

    private static void append(MapCanvas canvas, Circle circle, QuadBatch batch, float[] quad,
        TextureAtlasSprite sprite, int fallbackColour) {
        if (circle != null) {
            // Circular minimaps clip against their actual visible disc. A solid sampled
            // material colour is used there because circle-clipping textured UVs requires
            // interpolating extra vertices (and ties are usually sub-pixel at this scale).
            float[] clipped = circle.clip(quad, 4);
            if (clipped != null) canvas.solid(clipped, fallbackColour);
        } else {
            batch.add(quad, sprite, fallbackColour);
        }
    }

    /** Two interleaved batches: actual atlas sprites when present, solid fallback otherwise. */
    private static final class QuadBatch {
        private final float[] texVertices;
        private final float[] texUvs;
        private final int[] texColours;
        private final float[] flatVertices;
        private final int[] flatColours;
        private TextureAtlasSprite textureAtlas;
        private int textures;
        private int flats;

        QuadBatch(int capacity) {
            this.texVertices = new float[capacity * 12];
            this.texUvs = new float[capacity * 8];
            this.texColours = new int[capacity];
            this.flatVertices = new float[capacity * 12];
            this.flatColours = new int[capacity];
        }

        void add(float[] quad, TextureAtlasSprite sprite, int colour) {
            if (sprite != null && (textureAtlas == null
                || textureAtlas.atlasLocation().equals(sprite.atlasLocation()))) {
                if (textureAtlas == null) textureAtlas = sprite;
                int index = textures++;
                positions(quad, texVertices, index);
                int u = index * 8;
                texUvs[u] = sprite.getU0();
                texUvs[u + 1] = sprite.getV0();
                texUvs[u + 2] = sprite.getU0();
                texUvs[u + 3] = sprite.getV1();
                texUvs[u + 4] = sprite.getU1();
                texUvs[u + 5] = sprite.getV1();
                texUvs[u + 6] = sprite.getU1();
                texUvs[u + 7] = sprite.getV0();
                texColours[index] = 0xFFFFFFFF;
            } else {
                int index = flats++;
                positions(quad, flatVertices, index);
                flatColours[index] = colour;
            }
        }

        void flush(MapCanvas canvas) {
            if (flats > 0) canvas.solids(flatVertices, flatColours, flats);
            if (textures > 0 && textureAtlas != null) {
                canvas.blits(textureAtlas.atlasLocation(), texVertices, texUvs, texColours, textures);
            }
        }

        private static void positions(float[] xy, float[] xyz, int index) {
            int offset = index * 12;
            for (int i = 0; i < 4; i++) {
                xyz[offset + 3 * i] = xy[2 * i];
                xyz[offset + 3 * i + 1] = xy[2 * i + 1];
                xyz[offset + 3 * i + 2] = 0;
            }
        }
    }

}
