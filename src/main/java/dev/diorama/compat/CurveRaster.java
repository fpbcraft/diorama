package dev.diorama.compat;

import dev.diorama.Facing;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.compat.TrackRaster.Key;
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
    private static final int MAX_CACHED_TILES = 28;
    private static final int MAX_NEW_TILES_PER_DRAW = 2;
    private static final double GROUND_HIDES = .3;

    private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(32, .75f, true);
    private Map<String, List<TrackGraph.Segment>> current = Map.of();
    private Map<Key, List<TrackGraph.Segment>> indexed = Map.of();
    private Facing facing;
    private double pitchSin = Double.NaN;
    private MapStyle style;
    private BlockLooks looks;

    private TrackRaster raster;
    private static final class Tile extends TrackRaster.Tile {
        final Key key;
        MapTexture texture;
        SurfaceArea surface;
        Tile(Key key){super(key.tu(),key.tv());this.key=key;}

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

    void update(Map<String, List<TrackGraph.Segment>> next) {
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
        raster = new TrackRaster(blockLooks);
        indexed = TrackRaster.index(current);
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
                for (TrackGraph.Segment segment : entry.getValue()) {
                    raster.rasterize(tile, segment);
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

}
