package dev.diorama.compat;

import dev.diorama.Diorama;
import dev.diorama.bake.Looks;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.craft.Body;
import dev.diorama.data.Copycats;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Reads the ships of Create Aeronautics, and anything else built on the Sable physics mod, which
 * calls them sub-levels. A ship's blocks are ordinary blocks in ordinary chunks of the same level,
 * kept in a plot of its own some twenty million blocks from anywhere, and a pose says where in the
 * world that plot's blocks are shown, moved, turned and tilted.
 *
 * <p>Diorama doesn't build against Sable. If a name below isn't where this class expects it, it
 * logs once and stays off.
 */
public final class SubLevelBodies {
	private static boolean started;
	private static boolean failed;
	private static boolean warned;
	private static OptionalCall getContainer;
	private static OptionalCall inBounds;
	private static OptionalCall all;
	private static OptionalCall uniqueId;
	private static OptionalCall shipName;
	private static OptionalCall removed;
	private static OptionalCall plot;
	private static OptionalCall logicalPose;
	private static Method renderPose;
	private static @Nullable Method finalized;
	private static OptionalCall plotSize;
	private static OptionalCall bounds;
	private static final OptionalCall[] CORNER = new OptionalCall[6];
	private static OptionalCall transform;
	/**
	 * The last level asked about and its container, as the map asks for every chunk it's told of.
	 * Both held weakly: the container holds its level, and neither should outlive the world.
	 * {@code plotBits} turns a chunk coordinate into a plot's.
	 */
	private record Held(WeakReference<Level> level, WeakReference<Object> container, int plotBits) {
	}

	private static @Nullable Held held;
	/** By plot, how many times a ship's blocks may have changed, so that ship is read again. Client thread. */
	private static final Long2IntMap EDITS = new Long2IntOpenHashMap();

	private SubLevelBodies() {
	}

	private static boolean ready() {
		if (!started) {
			started = true;
			try {
				Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
				Class<?> ship = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
				Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
				Class<?> box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
				getContainer = OptionalCall.statik(containers, "getContainer", containers, Level.class);
				inBounds = OptionalCall.virtual(containers,"inBounds",boolean.class,int.class,int.class);
				all = OptionalCall.virtual(containers,"getAllSubLevels",List.class);
				uniqueId = OptionalCall.virtual(ship,"getUniqueId",UUID.class);
				try { shipName=OptionalCall.virtual(ship,"getName",String.class); } catch(NoSuchMethodException ignored) {}
				removed = OptionalCall.virtual(ship,"isRemoved",boolean.class);
				plot = OptionalCall.virtual(ship,"getPlot",Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot"));
				logicalPose = OptionalCall.virtual(ship,"logicalPose",Class.forName("dev.ryanhcode.sable.companion.math.Pose3d"));

				plotSize = OptionalCall.virtual(containers,"getLogPlotSize",int.class);
				bounds = OptionalCall.virtual(Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot"),"getBoundingBox",box);
				String[] corners = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
				for (int i = 0; i < 6; i++) {
					CORNER[i] = OptionalCall.virtual(box,corners[i],int.class);
				}
				transform = OptionalCall.virtual(pose,"transformPosition",Vec3.class,Vec3.class);
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
			Diorama.LOGGER.warn("Couldn't read Sable's sub-levels; Create Aeronautics ships won't show on the map", e);
		}
	}

	/** A failure that isn't Sable having changed under this class is logged once, and asked again next time. */
	private static void skip(Exception e) {
		if (!warned) {
			warned = true;
			Diorama.LOGGER.warn("Couldn't read a Sable sub-level; skipping it", e);
		}
	}

	private static @Nullable Object container(Level level) throws ReflectiveOperationException {
		Held last = held;
		if (last != null && last.level.get() == level) {
			Object container = last.container.get();
			if (container != null) {
				return container;
			}
		}
		Object container = getContainer.invoke(null, level);
		if (container != null && !(level instanceof ServerLevel)) {
			held = new Held(new WeakReference<>(level), new WeakReference<>(container), (int) plotSize.invoke(container));
			EDITS.clear();
		}
		return container;
	}

	/**
	 * Whether a chunk is one of those ships keep their blocks in. Those aren't a place in the
	 * world, so the map leaves them alone: the ships are drawn where they fly instead.
	 */
	public static boolean holds(Level level, int chunkX, int chunkZ) {
		if (!ready()) {
			return false;
		}
		try {
			Object container = container(level);
			return container != null && (boolean) inBounds.invoke(container, chunkX, chunkZ);
		} catch (LinkageError e) {
			fail(e);
			return false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			skip(e);
			return false;
		}
	}

	/**
	 * Something a ship shows may have changed in a chunk that {@link #holds} one: a block, a
	 * copycat's material, or the chunk arriving. That ship is read again. Client thread.
	 */
	public static void edited(int chunkX, int chunkZ) {
		Held last = held;
		if (last != null) {
			long key = plotKey(chunkX >> last.plotBits, chunkZ >> last.plotBits);
			EDITS.put(key, EDITS.get(key) + 1);
		}
	}

	private static long plotKey(int plotX, int plotZ) {
		return (long) plotX << 32 | plotZ & 0xFFFFFFFFL;
	}

	/** The ships the client has in this level. */
	public static List<Body> bodies(Level level) {
		if (!ready()) {
			return List.of();
		}
		try {
			if (!(level instanceof ServerLevel) && renderPose == null) {
				renderPose = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel").getMethod("renderPose", float.class);
				try { finalized = renderPose.getDeclaringClass().getMethod("isFinalized"); }
				catch (NoSuchMethodException ignored) { finalized = null; }
			}
			Object container = container(level);
			if (container == null || !(all.invoke(container) instanceof List<?> ships) || ships.isEmpty()) {
				return List.of();
			}
			List<Body> bodies = new ArrayList<>();
			for (Object ship : ships) {
				// One ship failing doesn't take the others off the map.
				try {
					if (ship == null || (!(level instanceof ServerLevel) && (renderPose == null || !renderPose.getDeclaringClass().isInstance(ship))) || (boolean) removed.invoke(ship)) {
						continue;
					}
					// Its chunks are still arriving: read now, it would show part built.
					if (!(level instanceof ServerLevel) && finalized != null && !(boolean) finalized.invoke(ship)) {
						continue;
					}
					Object box = bounds.invoke(plot.invoke(ship));
					int[] corners = new int[6];
					if (box == null && level instanceof ServerLevel && uniqueId.invoke(ship) instanceof UUID id) {
						bodies.add(new Ship(id, ship, level, new int[]{0,0,0,-1,-1,-1}, 0));
						continue;
					}
					for (int i = 0; i < 6; i++) {
						corners[i] = (int) CORNER[i].invoke(box);
					}
					// Sable's empty box has its corners the wrong way round: a ship whose blocks haven't arrived.
					if (!(uniqueId.invoke(ship) instanceof UUID id)) continue;
					if (corners[0] > corners[3] || corners[1] > corners[4] || corners[2] > corners[5]) {
						if (level instanceof ServerLevel) bodies.add(new Ship(id,ship,level,new int[]{0,0,0,-1,-1,-1},0));
						continue;
					}
					Held last = held;
					int bits = last == null ? 0 : last.plotBits;
					bodies.add(new Ship(id, ship, level, corners, level instanceof ServerLevel ? 0 : EDITS.get(plotKey(corners[0] >> 4 >> bits, corners[2] >> 4 >> bits))));
				} catch (ReflectiveOperationException | RuntimeException e) {
					skip(e);
				}
			}
			return bodies;
		} catch (LinkageError e) {
			fail(e);
			return List.of();
		} catch (ReflectiveOperationException | RuntimeException e) {
			skip(e);
			return List.of();
		}
	}

	/** {@code box} is the ship's blocks in its plot, min x y z then max x y z; {@code edits} its plot's count when it was listed. */
	private record Ship(UUID id, Object ship, Level level, int[] box, int edits) implements Body {
		@Override public String label() {
			try { Object name=shipName==null?null:shipName.invoke(ship);return name instanceof String text?text:""; }
			catch(ReflectiveOperationException|RuntimeException ignored){return "";}
		}
		@Override public boolean geometryAvailable() { return box[0]<=box[3] && box[1]<=box[4] && box[2]<=box[5]; }
		@Override
		public boolean persistWhenUnloaded() {
			return false;
		}

		@Override
		public long version() {
			return 31L * Arrays.hashCode(this.box) + this.edits;
		}

		@Override
		public Body.@Nullable Blocks read(Looks looks) {
			if (failed || this.box[0] > this.box[3] || this.box[1] > this.box[4] || this.box[2] > this.box[5]) {
				return null;
			}
			int x0 = this.box[0];
			int y0 = this.box[1];
			int z0 = this.box[2];
			int sx = this.box[3] - x0 + 1;
			int sy = this.box[4] - y0 + 1;
			int sz = this.box[5] - z0 + 1;
			if ((long) sx * sy * sz > (this.level instanceof ServerLevel ? 262144 : Body.MAX_CELLS)) {
				return null;
			}
			try {
				int[] states = CraftBlocks.empty(sx, sy, sz);
				Int2IntMap copycats = new Int2IntOpenHashMap();
				var materials=new java.util.HashMap<Integer,java.util.Map<String,BlockState>>();
				BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
				for (int cx = x0 >> 4; cx <= this.box[3] >> 4; cx++) {
					for (int cz = z0 >> 4; cz <= this.box[5] >> 4; cz++) {
						LevelChunk chunk = this.level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(cx, cz) : this.level.getChunk(cx, cz);
						if (chunk == null) return null;
						LevelChunkSection[] sections = chunk.getSections();
						for (int s = 0; s < sections.length; s++) {
							LevelChunkSection section = sections[s];
							int bottom = chunk.getSectionYFromSectionIndex(s) << 4;
							if (section == null || section.hasOnlyAir() || bottom > this.box[4] || bottom + 15 < y0) {
								continue;
							}
							for (int y = Math.max(y0, bottom); y <= Math.min(this.box[4], bottom + 15); y++) {
								for (int z = Math.max(z0, cz << 4); z <= Math.min(this.box[5], (cz << 4) + 15); z++) {
									for (int x = Math.max(x0, cx << 4); x <= Math.min(this.box[3], (cx << 4) + 15); x++) {
										BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
										if (state.isAir()) {
											continue;
										}
										int index = CraftBlocks.index(sx, sz, x - x0, y - y0, z - z0);
										states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
										if ((Copycats.INSTALLED || dev.diorama.data.DomumMaterials.INSTALLED) && state.hasBlockEntity()) {
											var entity=chunk.getBlockEntity(pos.set(x,y,z));
											var slots=dev.diorama.data.DomumMaterials.read(entity);if(!slots.isEmpty())materials.put(index,slots);
											BlockState material = Copycats.material(entity);
											if (material != null) {
												copycats.put(index, Block.BLOCK_STATE_REGISTRY.getId(material));
											}
										}
									}
								}
							}
						}
					}
				}
				return new Body.Blocks(new CraftBlocks(sx, sy, sz, states, copycats,materials), new int[] {x0, y0, z0});
			} catch (RuntimeException e) {
				skip(e);
				return null;
			}
		}

		@Override
		public boolean pose(int[] min, float partialTick, double[] out) {
			if (failed) {
				return false;
			}
			try {
				// Between ticks Sable draws a ship part way from its last pose to this one; so does the map.
				Object pose = partialTick >= 1 ? logicalPose.invoke(this.ship) : renderPose.invoke(this.ship, partialTick);
				double[] points = new double[12];
				for (int i = 0; i < 4; i++) {
					Vec3 p = (Vec3) transform.invoke(pose, new Vec3(min[0] + (i == 1 ? 1 : 0), min[1] + (i == 2 ? 1 : 0), min[2] + (i == 3 ? 1 : 0)));
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
