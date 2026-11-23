package dev.diorama.test;

import dev.diorama.Projection;
import dev.diorama.drawing.Drawing;
import dev.diorama.frontier.Frontier;
import dev.diorama.frontier.FrontierStore;
import dev.diorama.frontier.FrontierProjection;
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
                if (loaded == null || !loaded.contains(x, z) || loaded.color != frontier.color) {
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
}
