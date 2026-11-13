package dev.diorama.compat;

import dev.diorama.Diorama;
import dev.diorama.bake.Looks;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.craft.Body;
import dev.diorama.data.Copycats;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Reads Create's moving contraptions: trains, windmills, elevators, drills on pistons and the
 * rest. Each is an entity carrying a set of blocks in its own frame, turned and moved by the
 * entity.
 *
 * <p>Diorama doesn't build against Create. The names below are Create's own, the same on Fabric
 * and NeoForge since well before 1.20.1. If one isn't where this class expects it, it logs once
 * and stays off.
 */
public final class ContraptionBodies {
	private static boolean started;
	private static boolean failed;
	private static Class<?> entityType;
	private static boolean warned;
	private static OptionalCall contraption;
	private static OptionalCall blocks;
	private static OptionalCall toGlobal;
	private static @Nullable OptionalCall blockEntity;

	private ContraptionBodies() {
	}

	private static boolean ready() {
		if (!started) {
			started = true;
			try {
				entityType = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
				Class<?> type = Class.forName("com.simibubi.create.content.contraptions.Contraption");
				contraption = OptionalCall.virtual(entityType,"getContraption",type);
				blocks = OptionalCall.virtual(type,"getBlocks",Map.class);
				toGlobal = OptionalCall.virtual(entityType,"toGlobalVector",Vec3.class,Vec3.class,float.class);
				try {
					blockEntity = OptionalCall.virtual(type,"getBlockEntityClientSide",BlockEntity.class,BlockPos.class);
				} catch (NoSuchMethodException e) {
					// Filled copycats then draw as they do empty.
					blockEntity = null;
				}
			} catch (ClassNotFoundException e) {
				failed = true;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				fail(e);
			}
		}
		return !failed;
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read Create's contraptions; they won't show on the map", e);
		}
	}

	/** One contraption failing doesn't turn off the rest. */
	private static void skip(Exception e) {
		if (!warned) {
			warned = true;
			Diorama.LOGGER.warn("Couldn't read a Create contraption; skipping it", e);
		}
	}

	/**
	 * The contraptions the client is showing.
	 */
	public static List<Body> bodies(Iterable<? extends Entity> entities) {
		if (!ready()) {
			return List.of();
		}
		List<Body> bodies = new ArrayList<>();
		for (Entity entity : entities) {
			if (!entityType.isInstance(entity) || entity.isRemoved()) {
				continue;
			}
			bodies.add(new Moving(entity.getUUID(), entity));
		}
		return bodies;
	}

	static Body.@Nullable Blocks readContraption(Object held, net.minecraft.world.level.Level level, Looks looks) {
		try {
			if (held == null || !(blocks.invoke(held) instanceof Map<?, ?> map) || map.isEmpty()) {
				return null;
			}
			int x0 = Integer.MAX_VALUE;
			int y0 = Integer.MAX_VALUE;
			int z0 = Integer.MAX_VALUE;
			int x1 = Integer.MIN_VALUE;
			int y1 = Integer.MIN_VALUE;
			int z1 = Integer.MIN_VALUE;
			for (Object key : map.keySet()) {
				BlockPos pos = (BlockPos) key;
				x0 = Math.min(x0, pos.getX());
				y0 = Math.min(y0, pos.getY());
				z0 = Math.min(z0, pos.getZ());
				x1 = Math.max(x1, pos.getX());
				y1 = Math.max(y1, pos.getY());
				z1 = Math.max(z1, pos.getZ());
			}
			int sx = x1 - x0 + 1;
			int sy = y1 - y0 + 1;
			int sz = z1 - z0 + 1;
			if ((long) sx * sy * sz > (level instanceof net.minecraft.server.level.ServerLevel ? 262144 : Body.MAX_CELLS)) {
				return null;
			}
			int[] states = CraftBlocks.empty(sx, sy, sz);
			Int2IntMap copycats = new Int2IntOpenHashMap();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				BlockPos pos = (BlockPos) entry.getKey();
				BlockState state = ((StructureTemplate.StructureBlockInfo) entry.getValue()).state();
				if (state.isAir()) {
					continue;
				}
				int index = CraftBlocks.index(sx, sz, pos.getX() - x0, pos.getY() - y0, pos.getZ() - z0);
				states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
				if (Copycats.INSTALLED && state.hasBlockEntity()) {
					BlockEntity entity = null;
					if (level instanceof net.minecraft.server.level.ServerLevel) {
						var tag = ((StructureTemplate.StructureBlockInfo) entry.getValue()).nbt();
						if (tag != null) {
							//? if >=1.20.5 {
							entity = BlockEntity.loadStatic(pos, state, tag.copy(), level.registryAccess());
							//?} else {
							/*entity = BlockEntity.loadStatic(pos, state, tag.copy());
							*///?}
						}
					} else if (blockEntity != null && blockEntity.invoke(held, pos) instanceof BlockEntity clientEntity) entity = clientEntity;
					BlockState material = Copycats.material(entity);
					if (material != null) copycats.put(index, Block.BLOCK_STATE_REGISTRY.getId(material));
				}
			}
			return new Body.Blocks(new CraftBlocks(sx, sy, sz, states, copycats), new int[] {x0, y0, z0});
		} catch (LinkageError e) {
			fail(e);
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			skip(e);
			return null;
		}
	}

	private record Moving(UUID id, Entity entity) implements Body {
		@Override
		public Body.@Nullable Blocks read(Looks looks) {
			if (failed) return null;
			try { return readContraption(contraption.invoke(entity), entity.level(), looks); }
			catch (ReflectiveOperationException | RuntimeException e) { skip(e); return null; }
		}

		@Override
		public boolean pose(int[] min, float partialTick, double[] out) {
			if (failed) {
				return false;
			}
			try {
				double[] points = new double[12];
				for (int i = 0; i < 4; i++) {
					Vec3 p = (Vec3) toGlobal.invoke(this.entity, new Vec3(min[0] + (i == 1 ? 1 : 0), min[1] + (i == 2 ? 1 : 0), min[2] + (i == 3 ? 1 : 0)), partialTick);
					points[i * 3] = p.x;
					points[i * 3 + 1] = p.y;
					points[i * 3 + 2] = p.z;
				}
				System.arraycopy(points, 0, out, 0, 3);
				for (int i = 3; i < 12; i++) {
					out[i] = points[i] - points[i % 3];
				}
				return true;
			} catch (LinkageError e) {
				fail(e);
				return false;
			} catch (ReflectiveOperationException | RuntimeException e) {
				skip(e);
				return false;
			}
		}
	}
}
