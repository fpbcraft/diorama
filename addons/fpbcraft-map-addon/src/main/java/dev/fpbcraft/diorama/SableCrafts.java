package dev.fpbcraft.diorama;

import dev.diorama.Settings;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.craft.Body;
import dev.diorama.craft.Crafts;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Actual Sable sub-level blocks, using Diorama's existing moving craft baker instead
 * of fake waypoint icons. This preserves materials, 3-D rotation, shadows and ground
 * occlusion, and shares its existing asynchronous image pipeline.
 *
 * <p>Reads only already-synced client plot chunks; never requests server-only data.
 */
final class SableCrafts implements Crafts.Source {
    private static boolean failed;
    private static boolean warnedPose;
    private static boolean warnedBlocks;

    @Override
    public List<Body> bodies(ClientLevel level) {
        if (!Settings.get().showSableShipShapes || failed) {
            return List.of();
        }
        try {
            Object container = ReflectSupport.staticCall(
                "dev.ryanhcode.sable.api.sublevel.SubLevelContainer", "getContainer", level);
            if (container == null) {
                return List.of();
            }
            Object all = ReflectSupport.call(container, "getAllSubLevels");
            if (!(all instanceof Iterable<?> levels)) {
                return List.of();
            }
            List<Body> out = new ArrayList<>();
            for (Object sublevel : levels) {
                if (!Boolean.TRUE.equals(ReflectSupport.call(sublevel, "isFinalized"))) {
                    continue;
                }
                Object id = ReflectSupport.call(sublevel, "getUniqueId");
                if (id instanceof UUID uuid) {
                    out.add(new SableBody(uuid, sublevel));
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger("fpbcraftdiorama")
                .warn("Sable craft source disabled: could not enumerate client sub-levels", exception);
            return List.of();
        }
    }

    private record SableBody(UUID id, Object sublevel) implements Body {
        /** A temporarily unloaded moving vessel must not become a saved parked craft. */
        @Override
        public boolean persistWhenUnloaded() {
            return false;
        }

        @Override
        public Blocks read(BlockLooks looks) {
            try {
                Object plot = ReflectSupport.call(sublevel, "getPlot");
                Object bb = ReflectSupport.call(plot, "getBoundingBox");
                if (bb == null) return null;
                int x0 = ((Number) ReflectSupport.call(bb, "minX")).intValue();
                int y0 = ((Number) ReflectSupport.call(bb, "minY")).intValue();
                int z0 = ((Number) ReflectSupport.call(bb, "minZ")).intValue();
                int x1 = ((Number) ReflectSupport.call(bb, "maxX")).intValue();
                int y1 = ((Number) ReflectSupport.call(bb, "maxY")).intValue();
                int z1 = ((Number) ReflectSupport.call(bb, "maxZ")).intValue();
                long sx = (long) x1 - x0 + 1;
                long sy = (long) y1 - y0 + 1;
                long sz = (long) z1 - z0 + 1;
                if (sx <= 0 || sy <= 0 || sz <= 0 || sx * sy * sz > Body.MAX_CELLS) {
                    return null;
                }
                int dx = (int) sx, dy = (int) sy, dz = (int) sz;
                int[] states = CraftBlocks.empty(dx, dy, dz);
                Object chunks = ReflectSupport.call(plot, "getLoadedChunks");
                if (!(chunks instanceof Collection<?> loaded)) {
                    return null;
                }
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                int nonAir = 0;
                for (Object holder : loaded) {
                    Object result = ReflectSupport.call(holder, "getChunk");
                    if (!(result instanceof LevelChunk chunk)) continue;
                    ChunkPos chunkPos = chunk.getPos();
                    int ax = Math.max(x0, chunkPos.getMinBlockX());
                    int bx = Math.min(x1, chunkPos.getMaxBlockX());
                    int az = Math.max(z0, chunkPos.getMinBlockZ());
                    int bz = Math.min(z1, chunkPos.getMaxBlockZ());
                    if (ax > bx || az > bz) continue;
                    for (int y = y0; y <= y1; y++) {
                        for (int z = az; z <= bz; z++) {
                            for (int x = ax; x <= bx; x++) {
                                pos.set(x, y, z);
                                BlockState state = chunk.getBlockState(pos);
                                if (state.isAir()) continue;
                                int index = CraftBlocks.index(dx, dz, x - x0, y - y0, z - z0);
                                states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
                                nonAir++;
                            }
                        }
                    }
                }
                if (nonAir == 0) {
                    return null;
                }
                return new Blocks(new CraftBlocks(dx, dy, dz, states, new Int2IntOpenHashMap()),
                    new int[] {x0, y0, z0});
            } catch (ReflectiveOperationException | RuntimeException exception) {
                if (!warnedBlocks) {
                    warnedBlocks = true;
                    org.slf4j.LoggerFactory.getLogger("fpbcraftdiorama")
                        .warn("Unable to read Sable sub-level blocks {}", id, exception);
                }
                return null;
            }
        }

        @Override
        public boolean pose(int[] min, float partialTick, double[] out) {
            try {
                Object pose = ReflectSupport.call(sublevel, "renderPose", partialTick);
                Vector3dc origin = (Vector3dc) ReflectSupport.call(pose, "position");
                Vector3dc center = (Vector3dc) ReflectSupport.call(pose, "rotationPoint");
                Vector3dc scale = (Vector3dc) ReflectSupport.call(pose, "scale");
                Quaterniondc rotation = (Quaterniondc) ReflectSupport.call(pose, "orientation");

                Vector3d base = toWorld(min[0], min[1], min[2], origin, center, rotation, scale);
                Vector3d x = toWorld(min[0] + 1, min[1], min[2], origin, center, rotation, scale).sub(base);
                Vector3d y = toWorld(min[0], min[1] + 1, min[2], origin, center, rotation, scale).sub(base);
                Vector3d z = toWorld(min[0], min[1], min[2] + 1, origin, center, rotation, scale).sub(base);
                out[0] = base.x;
                out[1] = base.y;
                out[2] = base.z;
                out[3] = x.x;
                out[4] = x.y;
                out[5] = x.z;
                out[6] = y.x;
                out[7] = y.y;
                out[8] = y.z;
                out[9] = z.x;
                out[10] = z.y;
                out[11] = z.z;
                return true;
            } catch (ReflectiveOperationException | ClassCastException | LinkageError exception) {
                if (!warnedPose) {
                    warnedPose = true;
                    org.slf4j.LoggerFactory.getLogger("fpbcraftdiorama")
                        .warn("Unable to transform Sable sub-level {} onto Diorama's map", id, exception);
                }
                return false;
            }
        }

        private static Vector3d toWorld(double x, double y, double z, Vector3dc origin,
            Vector3dc center, Quaterniondc orientation, Vector3dc scale) {
            // Sable's transform: translate to rotation point, scale/rotate, then world origin.
            Vector3d transformed = new Vector3d(x, y, z).sub(center).mul(scale);
            orientation.transform(transformed);
            return transformed.add(origin);
        }
    }
}
