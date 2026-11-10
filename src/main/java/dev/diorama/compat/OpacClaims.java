package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}

/**
 * Optional Open Parties and Claims bridge. Only uses OPAC's documented public client API.
 * Reflection keeps OPAC optional (including at class-load time) for all Diorama variants.
 * All snapshots are written on the client thread, not the rendering or map-baking threads.
 */
public final class OpacClaims {
	private static boolean checked;
	private static boolean installed;
	private static boolean warned;
	private static boolean broken;
	private static Object api;
	private static Object manager;
	private static Object listener;
	private static Object subscribedManager;
	private static Object lastWorld;
	private static boolean reload = true;
	private static final Set<Long> dirtyChunks = new HashSet<>();
	private static final Set<Long> dirtyRegions = new HashSet<>();

	private OpacClaims() {
	}

	private static boolean installed() {
		if (!checked) {
			checked = true;
			//? if fabric {
			installed = FabricLoader.getInstance().isModLoaded("openpartiesandclaims");
			//?} else {
			/*installed = ModList.get().isLoaded("openpartiesandclaims");
			*///?}
		}
		return installed;
	}

	/** Whether the client has OPAC installed, for Diorama's settings UI. */
	public static boolean installedOnClient() {
		return installed();
	}

	/** True only after the current server's OPAC handshake; a client-only install is not enough. */
	public static boolean available(Minecraft mc) {
		if (mc.level == null || broken || !installed()) {
			return false;
		}
		if (lastWorld == mc.level && manager != null) {
			return true;
		}
		try {
			if (api == null) {
				api = Class.forName("xaero.pac.client.api.OpenPACClientAPI").getMethod("get").invoke(null);
			}
			Object helper = call(api, "getCapabilityHelper");
			Object kind = Class.forName("xaero.pac.client.world.capability.api.ClientWorldCapabilityTypes").getField("MAIN_CAP").get(null);
			Object cap = call(helper, "getCapability", mc.level, kind);
			if (cap == null) {
				return false;
			}
			Object data = call(cap, "getClientWorldData");
			if (!Boolean.TRUE.equals(call(data, "serverHasMod"))
				|| !Boolean.TRUE.equals(call(data, "serverHasClaimsEnabled"))) {
				return false;
			}
			Object next = call(api, "getClaimsManager");
			if (manager != next || lastWorld != mc.level) {
				manager = next;
				lastWorld = mc.level;
				reload = true;
				dirtyChunks.clear();
				dirtyRegions.clear();
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
			return false;
		}
	}

	public static void reset() {
		lastWorld = null;
		reload = true;
		dirtyChunks.clear();
		dirtyRegions.clear();
	}

	/** Called by Claims.tick, after the world and provider are selected. */
	public static void tick(Minecraft mc) {
		if (!available(mc)) {
			return;
		}
		try {
			if (subscribedManager != manager) {
				Class<?> type = Class.forName("xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI");
				InvocationHandler handler = (proxy, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						return switch (method.getName()) {
							case "hashCode" -> System.identityHashCode(proxy);
							case "equals" -> proxy == args[0];
							default -> "Diorama OPAC claims listener";
						};
					}
					if (args != null && args.length > 0 && dimensionMatches(mc, args[0])) {
						switch (method.getName()) {
							case "onChunkChange" -> dirtyChunks.add(key((int) args[1], (int) args[2]));
							case "onWholeRegionChange" -> dirtyRegions.add(key((int) args[1], (int) args[2]));
							case "onDimensionChange" -> reload = true;
							default -> { }
						}
					}
					return null;
				};
				listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
				call(call(manager, "getTracker"), "register", listener);
				subscribedManager = manager;
			}
			// OPAC loads its initial sync asynchronously. Never treat an incomplete list as final.
			if (reload && !Boolean.TRUE.equals(call(manager, "isLoading"))) {
				Claims.resetOpac();
				Object dimension = currentDimension(mc);
				try (Stream<?> dimensions = (Stream<?>) call(manager, "getDimensionStream")) {
					dimensions.filter(d -> {
						try {
							return dimension.equals(call(d, "getDimension"));
						} catch (ReflectiveOperationException e) {
							return false;
						}
					}).forEach(d -> {
						try (Stream<?> regions = (Stream<?>) call(d, "getRegionStream")) {
							regions.forEach(r -> readRegion(mc.level.dimension(), dimension, r));
						} catch (ReflectiveOperationException e) {
							throw new IllegalStateException(e);
						}
					});
				}
				reload = false;
				dirtyChunks.clear();
				dirtyRegions.clear();
			}
			if (!reload) {
				Object dim = currentDimension(mc);
				for (long packed : dirtyRegions) {
					Object dimensionClaims = call(manager, "getDimension", dim);
					Object region = dimensionClaims == null ? null : call(dimensionClaims, "getRegion", high(packed), low(packed));
					if (region != null) {
						readRegion(mc.level.dimension(), dim, region);
					} else {
						clearRegion(mc.level.dimension(), high(packed), low(packed));
					}
				}
				dirtyRegions.clear();
				for (long packed : dirtyChunks) {
					int x = high(packed), z = low(packed);
					Object claim = call(manager, "get", dim, x, z);
					Claims.updatedOpac(mc.level.dimension(), x, z, describe(claim, dim));
				}
				dirtyChunks.clear();
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
		}
	}

	private static void clearRegion(ResourceKey<Level> dimension, int x, int z) {
		for (int dz = 0; dz < 32; dz++) {
			for (int dx = 0; dx < 32; dx++) {
				Claims.updatedOpac(dimension, x * 32 + dx, z * 32 + dz, null);
			}
		}
	}

	private static void readRegion(ResourceKey<Level> dimension, Object dim, Object region) {
		try {
			int x = (int) call(region, "getX"), z = (int) call(region, "getZ");
			for (int dz = 0; dz < 32; dz++) {
				for (int dx = 0; dx < 32; dx++) {
					Object claim = call(region, "get", dx, dz);
					Claims.updatedOpac(dimension, x * 32 + dx, z * 32 + dz, describe(claim, dim));
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Claims.Claim describe(Object claim, Object dimension) throws ReflectiveOperationException {
		if (claim == null) {
			return null;
		}
		UUID owner = (UUID) call(claim, "getPlayerId");
		int color = (int) call(manager, "getColor", claim, dimension) & 0xFFFFFF;
		Object name = call(manager, "getFullName", claim, dimension, true);
		String label = (String) call(name, "getString");
		return new Claims.Claim(owner, color, label, Boolean.TRUE.equals(call(claim, "isForceloadable")));
	}

	/** Requests are validated by OPAC on the server; null uses the current player/party/server mode. */
	public static boolean requestArea(Minecraft mc, int x0, int z0, int x1, int z1, Action action) {
		if (!available(mc)) {
			return false;
		}
		int left = Math.min(x0, x1), top = Math.min(z0, z1);
		int right = Math.max(x0, x1), bottom = Math.max(z0, z1);
		try {
			Object dim = currentDimension(mc);
			switch (action) {
				case CLAIM -> call(manager, "requestAreaClaim", dim, left, top, right, bottom, null);
				case UNCLAIM -> call(manager, "requestAreaUnclaim", dim, left, top, right, bottom, null);
				case FORCELOAD -> call(manager, "requestAreaForceload", dim, left, top, right, bottom, true, null);
				case UNFORCELOAD -> call(manager, "requestAreaForceload", dim, left, top, right, bottom, false, null);
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
			return false;
		}
	}

	public enum Action { CLAIM, UNCLAIM, FORCELOAD, UNFORCELOAD }

	private static boolean dimensionMatches(Minecraft mc, Object dimension) {
		return mc.level != null && currentDimension(mc).equals(dimension);
	}

	private static Object currentDimension(Minecraft mc) {
		//? if >=1.21.11 {
		return mc.level.dimension().identifier();
		//?} else {
		/*return mc.level.dimension().location();
		*///?}
	}

	private static long key(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	private static int high(long value) { return (int) (value >> 32); }
	private static int low(long value) { return (int) value; }

	private static Object call(Object target, String method, Object... args) throws ReflectiveOperationException {
		if (target == null) {
			throw new NoSuchMethodException(method + " on null");
		}
		for (Method candidate : target.getClass().getMethods()) {
			if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
				boolean matches = true;
				Class<?>[] parameters = candidate.getParameterTypes();
				for (int i = 0; i < args.length; i++) {
					if (args[i] != null && !parameters[i].isPrimitive() && !parameters[i].isInstance(args[i])) {
						matches = false;
						break;
					}
				}
				if (matches) {
					return candidate.invoke(target, args);
				}
			}
		}
		throw new NoSuchMethodException(target.getClass().getName() + "." + method + "/" + args.length);
	}

	private static void warn(Throwable e) {
		broken = true;
		if (!warned) {
			warned = true;
			Diorama.LOGGER.warn("Could not access OPAC client claims; Diorama will fall back when possible", e);
		}
	}
}
