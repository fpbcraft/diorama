package dev.diorama.compat;

import dev.diorama.Projection;
import dev.diorama.api.MapAnnotations;
import dev.diorama.bake.TileKey;
import dev.diorama.render.Circle;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

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
    private static final int DARK = 0xD038302C;
    private static final int RAIL = 0xE0C3B8A9;

    private record Segment(Vec3 from, Vec3 to) {
    }

    private Map<String, List<Segment>> segments = Map.of();
    private int ticks;
    private Object world;
    private final Object railways;
    private final Field graphsField;
    private final Method nodesMethod;
    private final Method locateNodeMethod;
    private final Method connectionsFromMethod;
    private final Method locationDimensionMethod;
    private final Method isTurnMethod;
    private final Method getTurnMethod;
    private final Method isPrimaryMethod;
    private final Method edgeLengthMethod;
    private final Method edgePositionMethod;

    private CreateCurves(Object railways, Field graphsField, Method nodesMethod, Method locateNodeMethod,
        Method connectionsFromMethod, Method locationDimensionMethod, Method isTurnMethod, Method getTurnMethod,
        Method isPrimaryMethod, Method edgeLengthMethod, Method edgePositionMethod) {
        this.railways = railways;
        this.graphsField = graphsField;
        this.nodesMethod = nodesMethod;
        this.locateNodeMethod = locateNodeMethod;
        this.connectionsFromMethod = connectionsFromMethod;
        this.locationDimensionMethod = locationDimensionMethod;
        this.isTurnMethod = isTurnMethod;
        this.getTurnMethod = getTurnMethod;
        this.isPrimaryMethod = isPrimaryMethod;
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
                bezier.getMethod("isPrimary"),
                edge.getMethod("getLength"),
                edge.getMethod("getPosition", graph, double.class)));
        } catch (ClassNotFoundException ignored) {
            // Create absent (or an older Create version): no adapter.
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Create variant without these public graph methods: remain an optional integration.
        }
    }

    @Override
    public void tick(Minecraft minecraft) {
        if (minecraft.level == null) {
            this.world = null;
            this.segments = Map.of();
            return;
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
                        if (turn == null || !Boolean.TRUE.equals(this.isPrimaryMethod.invoke(turn))) {
                            continue;
                        }
                        String dimension = dimensionName(this.locationDimensionMethod.invoke(location));
                        double length = ((Number) this.edgeLengthMethod.invoke(edge)).doubleValue();
                        int count = Math.max(6, Math.min(MAX_SAMPLES_PER_EDGE, (int) Math.ceil(length / 2.0)));
                        List<Segment> list = next.computeIfAbsent(dimension, ignored -> new ArrayList<>());
                        Vec3 previous = (Vec3) this.edgePositionMethod.invoke(edge, graph, 0.0);
                        for (int i = 1; i <= count; i++) {
                            Vec3 current = (Vec3) this.edgePositionMethod.invoke(edge, graph, i / (double) count);
                            list.add(new Segment(previous, current));
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

    private static String dimensionName(Object key) throws ReflectiveOperationException {
        try {
            return String.valueOf(key.getClass().getMethod("identifier").invoke(key));
        } catch (NoSuchMethodException ignored) {
            return String.valueOf(key.getClass().getMethod("location").invoke(key));
        }
    }

    @Override
    public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        if (view.layer() != TileKey.SURFACE || view.zoom() < 0.22) {
            return;
        }
        List<Segment> lines = this.segments.get(dimension);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Circle circle = view.round() ? Circle.in(view) : null;
        MapCanvas canvas = MapCanvas.of(graphics);
        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        int shown = 0;
        for (Segment line : lines) {
            float x0 = view.screenX(Projection.u(line.from.x, line.from.z));
            float y0 = view.screenY(Projection.v(line.from.x, line.from.y, line.from.z));
            float x1 = view.screenX(Projection.u(line.to.x, line.to.z));
            float y1 = view.screenY(Projection.v(line.to.x, line.to.y, line.to.z));
            if (Math.max(x0, x1) < view.x() - 3 || Math.min(x0, x1) > view.x() + view.width() + 3
                || Math.max(y0, y1) < view.y() - 3 || Math.min(y0, y1) > view.y() + view.height() + 3) {
                continue;
            }
            if (++shown > MAX_RENDER_SEGMENTS) {
                break;
            }
            float width = (float) Math.max(1.1, Math.min(4, view.zoom() * 1.5));
            stroke(canvas, circle, x0, y0, x1, y1, width + 0.9f, DARK);
            stroke(canvas, circle, x0, y0, x1, y1, width * 0.55f, RAIL);
        }
        graphics.disableScissor();
    }

    private static void stroke(MapCanvas canvas, Circle circle, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.hypot(dx, dy);
        if (length < 0.0001f) {
            return;
        }
        float nx = -dy / length * width / 2;
        float ny = dx / length * width / 2;
        float[] corners = {x0 + nx, y0 + ny, x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny};
        if (circle == null) {
            canvas.solid(corners, color);
        } else {
            float[] clipped = circle.clip(corners, 4);
            if (clipped != null) {
                canvas.solid(clipped, color);
            }
        }
    }
}
