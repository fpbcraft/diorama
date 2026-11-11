package dev.diorama.compat;

import dev.diorama.Facing;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CurveStyle;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.SurfaceArea;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.NightLook;
import dev.diorama.render.Circle;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Renders Create's non-block Bezier geometry on Diorama's existing 4-pixels-per-block grid.
 * The previous GUI-layer quad rendering displayed original Minecraft textures at arbitrarily
 * high resolution; straight tracks are instead ray-traced into softly graded map pixels.
 *
 * <p>Curves are rasterized to cached 512px map tiles and use exactly the tile sampler,
 * colour grading and lighting as Diorama's ordinary terrain. The girder is an optional
 * additional 3D support below the same rails and ties, controlled by BezierConnection.hasGirder.
 * Ground-near depth maps hide the track where terrain stands in front of it.
 */
final class CurveRaster {
    private static final int SIZE = Projection.TILE_PIXELS;
    private static final int PPU = Projection.PIXELS_PER_UNIT;
    private static final int MAX_CACHED_TILES = 28;
    private static final int MAX_NEW_TILES_PER_DRAW = 2;
    private static final double GROUND_HIDES = .3;

    private record Key(String dimension, int tu, int tv) {
    }

    private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(32, .75f, true);
    private Map<String, List<CreateCurves.Segment>> current = Map.of();
    private Map<Key, List<CreateCurves.Segment>> indexed = Map.of();
    private Facing facing;
    private double pitchSin = Double.NaN;
    private MapStyle style;
    private BlockLooks looks;
    private int girderTop = 0x686B70;
    private int girderSide = 0x424850;

    private static final class Tile {
        final int[] pixels = new int[SIZE * SIZE];
        final float[] depth = new float[SIZE * SIZE];
        final Key key;
        int x0 = SIZE, y0 = SIZE, x1, y1;
        MapTexture texture;
        SurfaceArea surface;

        Tile(Key key) {
            this.key = key;
            Arrays.fill(depth, Float.POSITIVE_INFINITY);
        }

        void touched(int x, int y) {
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x + 1);
            y1 = Math.max(y1, y + 1);
        }

        boolean hasPixels() {
            return x0 < x1 && y0 < y1;
        }

        void close() {
            if (texture != null) {
                texture.close();
                texture = null;
            }
        }
    }

    void clear() {
        tiles.values().forEach(Tile::close);
        tiles.clear();
        indexed = Map.of();
        current = Map.of();
        facing = null;
        style = null;
        looks = null;
        pitchSin = Double.NaN;
    }

    void update(Map<String, List<CreateCurves.Segment>> next) {
        if (current.equals(next)) return;
        tiles.values().forEach(Tile::close);
        tiles.clear();
        indexed = Map.of();
        current = next;
    }

    private void ensureIndex() {
        BlockLooks blockLooks = BlockLooks.get();
        if (!indexed.isEmpty() && facing == Projection.facing()
            && pitchSin == Projection.sin() && style == MapStyle.get() && looks == blockLooks) {
            return;
        }
        tiles.values().forEach(Tile::close);
        tiles.clear();
        indexed = Map.of();
        facing = Projection.facing();
        pitchSin = Projection.sin();
        style = MapStyle.get();
        looks = blockLooks;
        resolveGirderColours(blockLooks);
        Map<Key, List<CreateCurves.Segment>> built = new HashMap<>();
        for (var entry : current.entrySet()) {
            for (CreateCurves.Segment segment : entry.getValue()) {
                Vec3 a = segment.from(), b = segment.to();
                // Includes top and side of the optional girder, below the track bed.
                double left = Math.min(Projection.u(a.x, a.z), Projection.u(b.x, b.z)) - 4;
                double right = Math.max(Projection.u(a.x, a.z), Projection.u(b.x, b.z)) + 4;
                double top = Math.min(Projection.v(a.x, a.y, a.z), Projection.v(b.x, b.y, b.z)) - 4;
                double bottom = Math.max(Projection.v(a.x, a.y, a.z), Projection.v(b.x, b.y, b.z)) + 5;
                int u0 = (int) Math.floor(left / Projection.TILE_UNITS);
                int u1 = (int) Math.floor(right / Projection.TILE_UNITS);
                int v0 = (int) Math.floor(top / Projection.TILE_UNITS);
                int v1 = (int) Math.floor(bottom / Projection.TILE_UNITS);
                if ((long) (u1 - u0 + 1) * (v1 - v0 + 1) > 16) continue;
                for (int u = u0; u <= u1; u++) {
                    for (int v = v0; v <= v1; v++) {
                        built.computeIfAbsent(new Key(entry.getKey(), u, v), k -> new ArrayList<>()).add(segment);
                    }
                }
            }
        }
        indexed = built;
    }

    private void resolveGirderColours(BlockLooks looks) {
        girderTop = 0x686B70;
        girderSide = 0x424850;
        if (looks == null) return;
        try {
            Object holder = Class.forName("com.simibubi.create.AllBlocks").getField("METAL_GIRDER").get(null);
            Object block = holder.getClass().getMethod("get").invoke(holder);
            if (block instanceof Block girder) {
                int id = looks.lookId(girder.defaultBlockState());
                int top = looks.top[id];
                int side = looks.side[id];
                if (top != 0) girderTop = top;
                if (side != 0) girderSide = side;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The source mod provides its own girder registry on some versions.
        }
    }

    void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension) {
        if (current.isEmpty() || view.zoom() <= 0) return;
        ensureIndex();
        if (indexed.isEmpty()) return;
        MapSession session = MapSession.get();
        MapLevel map = session == null ? null : session.map();
        MapCanvas canvas = MapCanvas.of(graphics);
        Sampling sampling = MapViewport.sampling(view);
        int tint = NightLook.dim(map == null ? 0 : NightLook.forView(map, TileKey.SURFACE), 255);
        double left = view.minU() - 2 / view.zoom();
        double top = view.minV() - 2 / view.zoom();
        double right = left + (view.width() + 4) / view.zoom();
        double bottom = top + (view.height() + 4) / view.zoom();
        Circle circle = view.round() ? Circle.in(view) : null;

        graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
        int newlyBaked = 0;
        for (var entry : indexed.entrySet()) {
            Key key = entry.getKey();
            if (!key.dimension().equals(dimension)) continue;
            double u = key.tu() * Projection.TILE_UNITS;
            double v = key.tv() * Projection.TILE_UNITS;
            if (u > right || v > bottom || u + Projection.TILE_UNITS < left
                || v + Projection.TILE_UNITS < top) continue;
            Tile tile = tiles.get(key);
            if (tile == null) {
                if (newlyBaked++ >= MAX_NEW_TILES_PER_DRAW) continue;
                tile = new Tile(key);
                for (CreateCurves.Segment segment : entry.getValue()) {
                    rasterize(tile, segment);
                }
                tiles.put(key, tile);
                while (tiles.size() > MAX_CACHED_TILES) {
                    Iterator<Tile> iter = tiles.values().iterator();
                    Tile discarded = iter.next();
                    iter.remove();
                    discarded.close();
                }
            }
            if (!tile.hasPixels()) continue;
            updateTexture(tile, map);

            float x0 = view.screenX(u);
            float y0 = view.screenY(v);
            float x1 = view.screenX(u + Projection.TILE_UNITS);
            float y1 = view.screenY(v + Projection.TILE_UNITS);
            if (circle == null) {
                canvas.rect(tile.texture, sampling, x0, y0, x1, y1, 0, 0, 1, 1, tint);
            } else {
                Circle.Quads clipped = circle.clip(x0, y0, x1, y1, 0, 0, 1, 1);
                if (clipped != null) {
                    canvas.quad(tile.texture, sampling, clipped.corners(), clipped.uvs(), tint);
                }
            }
        }
        graphics.disableScissor();
    }

    /**
     * The depth field stores the same absolute camera distance as TileSurface.near.
     * Recut only when the terrain surface images change, rather than uploading a new
     * curve texture every frame. Unknown terrain is treated as occluding the
     * railway overlay, even without fog of war: the Create graph contains tunnel
     * tracks that must never be displayed through the surface.
     */
    private static void updateTexture(Tile tile, MapLevel map) {
        SurfaceArea surface = null;
        if (map != null && tile.hasPixels()) {
            surface = map.surfaces(TileKey.SURFACE, tile.key.tu() * SIZE + tile.x0,
                tile.key.tv() * SIZE + tile.y0, tile.key.tu() * SIZE + tile.x1,
                tile.key.tv() * SIZE + tile.y1);
        }
        // A SurfaceArea can exist while its TileSurface jobs are still pending.
        // The identity comparison notices when those asynchronous traces arrive.
        // When both are null, keep the already-hidden texture until terrain exists.
        boolean unchanged = surface == null ? tile.surface == null
            : tile.surface != null && surface.same(tile.surface);
        if (tile.texture != null && unchanged) {
            return;
        }
        int[] output = tile.pixels.clone();
        // The Create graph includes rails hidden underground. Unlike transient moving
        // crafts, these are static world features: when the terrain's asynchronous
        // surface trace has not arrived, rendering their raw pixels would reveal tracks
        // through hills, buildings and undiscovered areas. Hide until the map can
        // perform a depth test, whether fog of war is enabled or not.
        if (surface == null) {
            Arrays.fill(output, 0);
        } else {
            int originX = tile.key.tu() * SIZE;
            int originY = tile.key.tv() * SIZE;
            for (int y = tile.y0; y < tile.y1; y++) {
                for (int x = tile.x0; x < tile.x1; x++) {
                    int index = y * SIZE + x;
                    if (output[index] == 0) continue;
                    float ground = surface.near(originX + x, originY + y);
                    float rail = tile.depth[index];
                    if (!Float.isFinite(ground) || !Float.isFinite(rail)
                        || ground < rail - GROUND_HIDES) {
                        output[index] = 0;
                    }
                }
            }
        }
        if (tile.texture == null) {
            tile.texture = MapTexture.create("diorama curved tracks", SIZE, Mipmaps.LEVELS);
        }
        int[][] levels = Mipmaps.build(output, SIZE);
        for (int i = 0; i < Mipmaps.LEVELS; i++) {
            tile.texture.write(i, 0, 0, SIZE >> i, levels[i]);
        }
        tile.surface = surface;
    }

    private void rasterize(Tile tile, CreateCurves.Segment segment) {
        CreateCurves.Palette p = segment.palette();
        double gauge = p.gauge();
        Vec3 from = segment.from();
        Vec3 to = segment.to();
        Vec3 across = segment.across();
        Vec3 up = segment.up();

        // Real Create hasGirder beams sit under the rail centre. No beams for curves
        // without that flag, including Steam 'n' Rails curves that don't use supports.
        if (segment.girder()) {
            Vec3 offset = up.scale(-.55);
            Vec3 a = from.add(offset);
            Vec3 b = to.add(offset);
            paint(tile, strip(a, b, across, 0, gauge * 2.15),
                CurveStyle.shade(girderTop, (from.y + to.y) / 2 - .55, true));
            Vec3 lower = up.scale(-.65);
            for (int side = -1; side <= 1; side += 2) {
                Vec3 sideOffset = across.scale(side * gauge * 1.075);
                Vec3 a0 = from.add(offset).add(sideOffset);
                Vec3 b0 = to.add(offset).add(sideOffset);
                paint(tile, new Vec3[]{a0, b0, b0.add(lower), a0.add(lower)},
                    CurveStyle.shade(girderSide, from.y - 1, false));
            }
        }

        // Create draws one transverse tie at each Bezier sample. In Diorama these are
        // map pixels with the same sunlight/grade as straight track blocks, not sprites.
        Vec3 direction = to.subtract(from).normalize();
        if (direction.lengthSqr() < 1e-7) return;
        double halfTie = Math.min(.20, to.distanceTo(from) * .32);
        Vec3 halfAlong = direction.scale(halfTie);
        Vec3 halfAcross = across.scale(gauge * 1.28);
        Vec3 center = to.add(up.scale(-.085));
        paint(tile, new Vec3[] {
            center.subtract(halfAlong).add(halfAcross),
            center.subtract(halfAlong).subtract(halfAcross),
            center.add(halfAlong).subtract(halfAcross),
            center.add(halfAlong).add(halfAcross)
        }, CurveStyle.shade(p.sleeper() & 0xFFFFFF, center.y, true));

        // Two parallel rails (one for monorail), continuous between Bezier samples.
        // Narrow/standard/wide gauge remains defined by each TrackMaterial.
        for (int side = -1; side <= 1; side += 2) {
            if (p.monorail() && side == 1) break;
            double position = p.monorail() ? 0 : side * gauge * .965;
            Vec3 lift = up.scale(.065);
            paint(tile, strip(from.add(lift), to.add(lift), across, position, gauge * .20),
                CurveStyle.shade(p.metal() & 0xFFFFFF, center.y, true));
        }
    }

    private static Vec3[] strip(Vec3 a, Vec3 b, Vec3 cross, double offset, double width) {
        Vec3 far = cross.scale(offset + width / 2);
        Vec3 near = cross.scale(offset - width / 2);
        return new Vec3[] {a.add(far), a.add(near), b.add(near), b.add(far)};
    }

    /** Raster a convex quadrilateral with Diorama's four subpixel samples per map pixel. */
    private static void paint(Tile tile, Vec3[] quad, int abgr) {
        double[] px = new double[4], py = new double[4], depth = new double[4];
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            Vec3 w = quad[i];
            px[i] = Projection.u(w.x, w.z) * PPU - tile.key.tu() * SIZE;
            py[i] = Projection.v(w.x, w.y, w.z) * PPU - tile.key.tv() * SIZE;
            depth[i] = -(Projection.sin() * w.y + Projection.cos() * Projection.facing().z(w.x, w.z));
            minX = Math.min(minX, px[i]);
            minY = Math.min(minY, py[i]);
            maxX = Math.max(maxX, px[i]);
            maxY = Math.max(maxY, py[i]);
        }
        int x0 = Math.max(0, (int) Math.floor(minX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int x1 = Math.min(SIZE, (int) Math.ceil(maxX));
        int y1 = Math.min(SIZE, (int) Math.ceil(maxY));
        if (x0 >= x1 || y0 >= y1) return;

        float approxDepth = (float) ((depth[0] + depth[1] + depth[2] + depth[3]) * .25);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int coverage = 0;
                if (inside(px, py, x + .375, y + .125)) coverage++;
                if (inside(px, py, x + .875, y + .375)) coverage++;
                if (inside(px, py, x + .125, y + .625)) coverage++;
                if (inside(px, py, x + .625, y + .875)) coverage++;
                if (coverage == 0) continue;
                int index = y * SIZE + x;
                // The nearer face owns the map pixel. A later rail face can still
                // cover its sleeper where they are almost coplanar.
                if (approxDepth > tile.depth[index] + .14f) continue;
                int alpha = coverage * 255 / 4;
                tile.pixels[index] = over(abgr, alpha, tile.pixels[index]);
                tile.depth[index] = Math.min(tile.depth[index], approxDepth);
                tile.touched(x, y);
            }
        }
    }

    private static boolean inside(double[] x, double[] y, double px, double py) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double cross = (x[j] - x[i]) * (py - y[i]) - (y[j] - y[i]) * (px - x[i]);
            if (Math.abs(cross) <= 1e-8) continue;
            int next = cross > 0 ? 1 : -1;
            if (sign != 0 && sign != next) return false;
            sign = next;
        }
        return sign != 0;
    }

    /** Source and destination are ABGR, matching Diorama's tile pixels. */
    private static int over(int source, int coverage, int destination) {
        int destAlpha = destination >>> 24;
        int outAlpha = coverage + destAlpha * (255 - coverage) / 255;
        if (outAlpha == 0) return 0;
        int r = ((source & 255) * coverage
            + (destination & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        int g = (((source >>> 8) & 255) * coverage
            + ((destination >>> 8) & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        int b = (((source >>> 16) & 255) * coverage
            + ((destination >>> 16) & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        return outAlpha << 24 | b << 16 | g << 8 | r;
    }
}
