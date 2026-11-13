package dev.diorama.compat;

import dev.diorama.Diorama;
import dev.diorama.bake.Looks;
import dev.diorama.craft.Body;
import dev.diorama.data.Nbt;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Create's registry keeps carriage anchors current even without a ticking entity. Server only. */
public final class TrainBodies {
	private static boolean started, failed, warned, registryAvailable;
	private static Class<?> create, carriageEntity, palette, contraptionType;
	private static final Map<ServerLevel,Map<UUID,Float>> orientations = new WeakHashMap<>();
	private static OptionalCall dimensional, getContraption, initialYaw, fromNbt, write;
	private TrainBodies() {}
	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		return owner.getClass().getField(name).get(owner);
	}
	private static boolean ready() {
		if (!started) {
			started = true;
			try {
				create = Class.forName("com.simibubi.create.Create");
				Class<?> carriage = Class.forName("com.simibubi.create.content.trains.entity.Carriage");
				Class<?> dimensionalType = Class.forName(carriage.getName() + "$DimensionalCarriageEntity");
				carriageEntity = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
				contraptionType = Class.forName("com.simibubi.create.content.contraptions.Contraption");
				palette = Class.forName("com.simibubi.create.content.trains.graph.DimensionPalette");
				dimensional = OptionalCall.virtual(carriage,"getDimensionalIfPresent",dimensionalType,net.minecraft.resources.ResourceKey.class);
				getContraption = OptionalCall.virtual(carriageEntity,"getContraption",contraptionType);
				initialYaw = OptionalCall.virtual(carriageEntity,"getInitialYaw",float.class);
				fromNbt = OptionalCall.statik(contraptionType,"fromNBT",contraptionType,Level.class,CompoundTag.class,boolean.class);
				//? if >=1.20.5 {
				write = OptionalCall.virtual(carriage,"write",CompoundTag.class,palette,net.minecraft.core.HolderLookup.Provider.class);
				//?} else {
				/*write = OptionalCall.virtual(carriage,"write",CompoundTag.class,palette);
				*///?}
			} catch (ClassNotFoundException absent) { failed = true; }
			catch (ReflectiveOperationException | RuntimeException | LinkageError error) { failed = true; warn(error); }
		}
		return !failed;
	}
	private static void warn(Throwable error) {
		if (!warned) { warned = true; Diorama.LOGGER.warn("Couldn't read Create railway carriages",error); }
	}
	public static boolean isCarriage(Object entity) { return ready() && registryAvailable && carriageEntity.isInstance(entity); }
	public static List<Body> bodies(ServerLevel level) {
		if (!ready()) return List.of();
		List<Body> out = new ArrayList<>();
		Set<UUID> present = new HashSet<>();
		try {
			Object manager = create.getField("RAILWAYS").get(null);
			for (Object train : new ArrayList<>(((Map<?,?>)field(manager,"trains")).values())) {
				List<?> cars = (List<?>)field(train,"carriages");
				for (int index=0; index<cars.size(); index++) {
					Object car = cars.get(index), dim = dimensional.invoke(car,level.dimension());
					if (dim == null) continue;
					Object position = field(dim,"positionAnchor"), anchors = field(dim,"rotationAnchors");
					if (!(position instanceof Vec3 anchor) || anchors == null) continue;
					Vec3 leading = (Vec3)anchors.getClass().getMethod("getFirst").invoke(anchors);
					Vec3 trailing = (Vec3)anchors.getClass().getMethod("getSecond").invoke(anchors);
					if (leading == null || trailing == null) continue;
					String key = level.dimension().identifier() + "/" + field(train,"id") + "/" + index;
					UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
					present.add(id);
					out.add(new Car(id,car,dim,level,anchor,leading,trailing));
				}
			}
			registryAvailable=true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) { registryAvailable=false;warn(error);return List.of(); }
		orientations.computeIfAbsent(level,l -> new HashMap<>()).keySet().retainAll(present);
		return out;
	}
	private record Car(UUID id,Object carriage,Object dimensional,ServerLevel level,Vec3 anchor,Vec3 leading,Vec3 trailing) implements Body {
		private Object live() throws ReflectiveOperationException {
			Object held = field(dimensional,"entity");
			return held instanceof Reference<?> ref ? ref.get() : null;
		}
		@Override public Blocks read(Looks looks) {
			try {
				Object entity = live();
				if (entity instanceof net.minecraft.world.entity.Entity e && !e.isRemoved())
					return ContraptionBodies.readContraption(getContraption.invoke(entity),level,looks);
				CompoundTag saved = saved();
				return ContraptionBodies.readContraption(fromNbt.invoke(null,level,Nbt.compound(saved,"Contraption"),false),level,looks);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return null; }
		}
		private CompoundTag saved() throws ReflectiveOperationException {
			Object colours = palette.getConstructor().newInstance();
			//? if >=1.20.5 {
			return Nbt.compound((CompoundTag)write.invoke(carriage,colours,level.registryAccess()),"Entity");
			//?} else {
			/*return Nbt.compound((CompoundTag)write.invoke(carriage,colours),"Entity");
			*///?}
		}
		@Override public boolean pose(int[] min,float partialTick,double[] out) {
			try {
				Object entity = live();
				Map<UUID,Float> cache = orientations.computeIfAbsent(level,l -> new HashMap<>());
				Float cached = cache.get(id);
				float yaw = entity != null ? (float)initialYaw.invoke(entity) : cached != null ? cached : orientation();
				cache.put(id,yaw);
				double dx=leading.x-trailing.x,dy=leading.y-trailing.y,dz=leading.z-trailing.z;
				double turn=Math.toDegrees(net.minecraft.util.Mth.atan2(dz,dx))+180;
				double pitch=-Math.atan2(dy,Math.hypot(dx,dz));
				Quaterniond q=new Quaterniond().rotateY(Math.toRadians(-turn)).rotateZ(pitch).rotateY(Math.toRadians(yaw));
				for(int i=0;i<4;i++) {
					// toGlobalVector rotates around the block centre, then adds the carriage anchor.
					Vector3d v=new Vector3d(i==0?min[0]-.5:i==1?1:0,i==0?min[1]-.5:i==2?1:0,i==0?min[2]-.5:i==3?1:0).rotate(q);
					out[i*3]=v.x+(i==0?anchor.x:0);
					out[i*3+1]=v.y+(i==0?anchor.y+1:0);
					out[i*3+2]=v.z+(i==0?anchor.z:0);
				}
				return true;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn(error); return false; }
		}
		private float orientation() throws ReflectiveOperationException {
			try { return Direction.valueOf(Nbt.string(saved(),"InitialOrientation")).toYRot(); }
			catch (IllegalArgumentException ignored) { return Direction.SOUTH.toYRot(); }
		}
	}
}
