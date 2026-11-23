package dev.diorama.frontier;

import dev.diorama.Claims;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Connected OPAC chunks with matching ownership and sub-claim settings form a named area. */
public final class ClaimTerritories {
    public record Region(String name, int color, double x, double z, int chunks, double clearance) {}
    private record Chunk(int x, int z) {
        Chunk offset(int dx, int dz) { return new Chunk(x + dx, z + dz); }
    }
    private record Cell(Object key, Claims.Claim claim) {}
    private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static Claims.Snapshot previous;
    private static List<Region> regions = List.of();

    private ClaimTerritories() {}

    /** The overlay and directory read the same client snapshot. */
    public static List<Region> regions(Claims.Snapshot snapshot) {
        if (snapshot != previous) {
            regions = group(snapshot);
            previous = snapshot;
        }
        return regions;
    }

    private static List<Region> group(Claims.Snapshot snapshot) {
        Map<Chunk, Cell> cells = new HashMap<>();
        snapshot.forEach((x, z, claim) -> {
            Object key = claim.owner().territoryKey();
            if (key != null && claim.territoryName() != null && !claim.territoryName().isBlank()) {
                cells.put(new Chunk(x, z), new Cell(key, claim));
            }
        });
        Set<Chunk> remaining = new HashSet<>(cells.keySet());
        List<Region> result = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            Chunk start = entry.getKey();
            if (!remaining.remove(start)) continue;
            Cell cell = entry.getValue();
            List<Chunk> component = new ArrayList<>();
            ArrayDeque<Chunk> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Chunk chunk = queue.remove();
                component.add(chunk);
                for (int[] step : NEIGHBORS) {
                    Chunk neighbor = chunk.offset(step[0], step[1]);
                    if (same(cells, cell, neighbor) && remaining.remove(neighbor)) queue.add(neighbor);
                }
            }
            double cx = component.stream().mapToInt(Chunk::x).average().orElse(start.x);
            double cz = component.stream().mapToInt(Chunk::z).average().orElse(start.z);
            Chunk anchor = start;
            int interior = -1;
            double centerDistance = Double.POSITIVE_INFINITY;
            for (Chunk chunk : component) {
                int neighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) if (same(cells, cell, chunk.offset(dx, dz))) neighbors++;
                }
                double distance = Math.hypot(chunk.x - cx, chunk.z - cz);
                if (neighbors > interior || (neighbors == interior && distance < centerDistance)) {
                    anchor = chunk;
                    interior = neighbors;
                    centerDistance = distance;
                }
            }
            double clearance = interior == 9 ? 24 : 8;
            // Large labels can use the room in the next ring of a solid claim.
            for (int radius = 2; interior == 9 && radius <= 6; radius++) {
                boolean filled = true;
                for (int dx = -radius; filled && dx <= radius; dx++) {
                    for (int dz = -radius; filled && dz <= radius; dz++) {
                        if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                            filled = same(cells, cell, anchor.offset(dx, dz));
                        }
                    }
                }
                if (!filled) break;
                clearance = 16 * radius + 8;
            }
            result.add(new Region(cell.claim.territoryName(), cell.claim.rgb(), anchor.x * 16.0 + 8,
                anchor.z * 16.0 + 8, component.size(), clearance));
        }
        return List.copyOf(result);
    }

    private static boolean same(Map<Chunk, Cell> cells, Cell owner, Chunk chunk) {
        Cell next = cells.get(chunk);
        return next != null && next.key.equals(owner.key);
    }
}
