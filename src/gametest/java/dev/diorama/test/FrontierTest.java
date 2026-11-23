package dev.diorama.test;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.drawing.Drawing;
import dev.diorama.frontier.Frontier;
import dev.diorama.frontier.FrontierBanner;
import dev.diorama.frontier.FrontierWire;
import dev.diorama.frontier.LabelAnchor;
import java.util.List;
import dev.diorama.frontier.FrontierStore;
import dev.diorama.frontier.FrontierProjection;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Geometry, persistence, defensive copies and world-anchored coordinates of native frontiers. */
final class FrontierTest {
    private FrontierTest() {
    }

    static void run(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            String dimension = mc.level.dimension().identifier().toString();
            double x = mc.player.getX();
            double z = mc.player.getZ();
            double[] bounds = {
                x - 50, z - 50,
                x + 50, z - 50,
                x + 50, z + 50,
                x - 50, z + 50
            };
            Frontier frontier = new Frontier();
            frontier.dimension = dimension;
            frontier.name = "Native frontier test";
            frontier.color = 0x2244BB;
            frontier.vertices = bounds;
            if (!frontier.valid() || !frontier.contains(x, z)
                || frontier.contains(x + 100, z) || frontier.contains(x, z + 100)) {
                throw new AssertionError("Basic polygon inclusion failed");
            }
            checkEdgeInsertion(frontier, x, z);
            checkAppearance();
            frontier.banner = new FrontierBanner(11, List.of(new FrontierBanner.Layer("minecraft:stripe_center", 0)));
            Frontier copy = frontier.copy();
            copy.vertices[0] += 20;
            if (frontier.vertices[0] == copy.vertices[0]) {
                throw new AssertionError("Frontier.copy shared mutable vertices");
            }
            Frontier invalid = frontier.copy();
            invalid.vertices = new double[] {1, 1, 2, 2};
            if (invalid.valid()) {
                throw new AssertionError("Accepted a two-point frontier");
            }

            Drawing area = new Drawing(Drawing.Kind.AREA, dimension, frontier.color,
                new double[] {
                    Projection.u(x - 50, z - 50), Projection.v(x - 50, 64, z - 50),
                    Projection.u(x + 50, z - 50), Projection.v(x + 50, 64, z - 50),
                    Projection.u(x + 50, z + 50), Projection.v(x + 50, 64, z + 50),
                    Projection.u(x - 50, z + 50), Projection.v(x - 50, 64, z + 50)
                }, "");
            Frontier converted = FrontierProjection.fromDrawing(area);
            if (!converted.contains(x, z)) {
                throw new AssertionError("Area conversion lost world geometry");
            }
            for (int i = 0; i < bounds.length; i++) {
                if (Math.abs(bounds[i] - converted.vertices[i]) > 0.001) {
                    throw new AssertionError("Projected area did not round-trip: vertex " + i);
                }
            }

            java.nio.file.Path dir;
            try {
                dir = java.nio.file.Files.createTempDirectory("diorama-frontiers-test-");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            java.nio.file.Path file = dir.resolve("frontiers.json");
            FrontierStore store = FrontierStore.open(file);
            if (!store.put(frontier)) {
                throw new AssertionError("Could not store test frontier");
            }
            try {
                Frontier loaded = FrontierStore.open(file)
                    .in(dimension).stream().filter(f -> f.id.equals(frontier.id)).findFirst().orElse(null);
                if (loaded == null || !loaded.contains(x, z) || loaded.color != frontier.color || !frontier.banner.equals(loaded.banner)) {
                    throw new AssertionError("Frontier did not persist and reload");
                }
                Frontier found = store.at(dimension, x, z);
                if (found == null || !found.id.equals(frontier.id)) {
                    throw new AssertionError("Frontier hit-testing failed");
                }
            } finally {
                if (!store.remove(frontier.id)) {
                    throw new AssertionError("Could not remove test frontier");
                }
                try {
                    java.nio.file.Files.deleteIfExists(file);
                    java.nio.file.Files.deleteIfExists(dir);
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    private static void checkAppearance() {
        Frontier concave = new Frontier();
        concave.name = "Horseshoe";
        concave.dimension = "minecraft:overworld";
        concave.vertices = new double[] {0, 0, 80, 0, 80, 80, 64, 80, 64, 16, 16, 16, 16, 80, 0, 80};
        LabelAnchor anchor = LabelAnchor.inside(concave.vertices);
        if (!concave.contains(anchor.x(), anchor.z()) || anchor.clearance() <= 0) {
            throw new AssertionError("Label anchor must be inside the concave frontier");
        }
        concave.banner = new FrontierBanner(11, List.of(new FrontierBanner.Layer("minecraft:stripe_center", 0)));
        try (var entry = FrontierWire.input(FrontierWire.entry(concave));
             var banner = FrontierWire.input(FrontierWire.banner(concave));
             var put = FrontierWire.input(FrontierWire.put(1, concave, true))) {
            FrontierWire.readFrontier(entry);
            FrontierWire.complete(entry);
            if (!banner.readUTF().isEmpty() || !FrontierWire.readUuid(banner).equals(concave.id)
                || !concave.banner.equals(FrontierWire.readBanner(banner))) {
                throw new AssertionError("Banner update did not round-trip");
            }
            FrontierWire.complete(banner);
            put.readInt();
            FrontierWire.readFrontier(put);
            if (!concave.banner.equals(FrontierWire.readBanner(put))) {
                throw new AssertionError("Banner edit did not round-trip in the normal save");
            }
            FrontierWire.complete(put);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void checkEdgeInsertion(Frontier frontier, double x, double z) {
        Facing original = Projection.facing();
        try {
            for (Facing facing : Facing.values()) {
                Projection.setFacing(facing);
                for (double zoom : new double[] {0.8, 2.5}) {
                    MapViewport.View view = new MapViewport.View(0, 0, 300, 300,
                        Projection.u(x, z), Projection.v(x, 64, z), zoom, TileKey.SURFACE);
                    assertEdge(frontier, view, x, z - 50, 1);
                    assertEdge(frontier, view, x - 50, z, 4);
                    if (FrontierProjection.edgeAt(frontier, view, 150, 150, 5) != null) {
                        throw new AssertionError("The center of the frontier is outside the edge snap radius");
                    }
                }
            }
        } finally {
            Projection.setFacing(original);
        }
    }

    private static void assertEdge(Frontier frontier, MapViewport.View view, double x, double z, int index) {
        float px = view.screenX(Projection.u(x, z));
        float py = view.screenY(Projection.v(x, 64, z));
        // Offset perpendicular to the screen edge to exercise snapping as well as vertex order.
        int next = index % (frontier.vertices.length / 2) * 2;
        float endpointX = view.screenX(Projection.u(frontier.vertices[next], frontier.vertices[next + 1]));
        double clickX = px + (Math.abs(px - endpointX) < 0.01 ? 3 : 0);
        double clickY = py + (Math.abs(px - endpointX) < 0.01 ? 0 : 3);
        FrontierProjection.EdgePoint hit = FrontierProjection.edgeAt(frontier, view, clickX, clickY, 5);
        if (hit == null || hit.insertIndex() != index || Math.hypot(hit.x() - x, hit.z() - z) > 0.001) {
            throw new AssertionError("Wrong edge snap at vertex index " + index + ": " + hit);
        }
    }
}
