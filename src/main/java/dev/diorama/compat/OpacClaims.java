package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.MapScreen;
import dev.diorama.Diorama;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.chat.Component;
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
	private static Object subscribedResultsManager;
	/** Only report results for requests made through Diorama, never OPAC's own GUI. */
	private static final List<PendingAction> pending = new ArrayList<>();
	private record PendingAction(Object dimension, int left, int top, int right, int bottom, Action action) {}
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
				pending.clear();
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
		pending.clear();
	}

	/** Called by Claims.tick, after the world and provider are selected. */
	/** Listen for authoritative claim action results; upstream's native OPAC adapter draws claims. */
	public static void tick(Minecraft mc) {
		if (!available(mc)) return;
		try {
			if (subscribedResultsManager != manager) {
				// Unlike requestAreaClaim (which only sends a packet), this callback carries
				// the authoritative server result, including failures and partial successes.
				Class<?> type = Class.forName("xaero.pac.client.claims.tracker.result.api.IClaimsManagerClaimResultListenerAPI");
				Object resultListener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
					(proxy, method, args) -> {
						if (method.getDeclaringClass() == Object.class) {
							return switch (method.getName()) {
								case "hashCode" -> System.identityHashCode(proxy);
								case "equals" -> proxy == args[0];
								default -> "Diorama OPAC result listener";
							};
						}
						if ("onClaimResult".equals(method.getName()) && args != null && args.length == 1) {
							Object result = args[0];
							mc.execute(() -> showResult(mc, result));
						}
						return null;
					});
				call(call(manager, "getClaimResultTracker"), "register", resultListener);
				subscribedResultsManager = manager;
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

	/**
	 * Maximum claim distance in chunks (a square around the player). -1 means that
	 * the server has not synced a limit or the player is in admin/moderator mode.
	 * The server also enforces claim limits, anchor rules and dimension permissions.
	 */
	public static int maxClaimDistance(Minecraft mc) {
		if (!available(mc)) {
			return -1;
		}
		try {
			if (Boolean.TRUE.equals(call(manager, "isModeratorMode"))) {
				return -1;
			}
			int distance = (int) call(manager, "getMaxClaimDistance");
			return distance >= 0 ? distance : -1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return -1; // Unknown is not the same thing as out of range.
		}
	}

	/** Local distance preflight; only the server can make the final permission decision. */
	public static boolean outsideClaimDistance(Minecraft mc, int chunkX, int chunkZ) {
		int distance = maxClaimDistance(mc);
		if (distance < 0 || mc.player == null) {
			return false;
		}
		return Math.abs((long) chunkX - mc.player.chunkPosition().x) > distance
			|| Math.abs((long) chunkZ - mc.player.chunkPosition().z) > distance;
	}

	/** A claim response is only shown if its dimension and exact area match our pending request. */
	private static void showResult(Minecraft mc, Object result) {
		if (mc.player == null || pending.isEmpty()) {
			return;
		}
		try {
			Object dimension = call(result, "getDimension");
			int left = (int) call(result, "getLeft");
			int top = (int) call(result, "getTop");
			int right = (int) call(result, "getRight");
			int bottom = (int) call(result, "getBottom");
			PendingAction match = null;
			for (PendingAction request : pending) {
				if (request.dimension().equals(dimension) && request.left() == left
					&& request.top() == top && request.right() == right && request.bottom() == bottom) {
					match = request;
					break;
				}
			}
			if (match == null) {
				return;
			}
			pending.remove(match);
			Component message = Component.literal("OPAC " + match.action().name().toLowerCase(java.util.Locale.ROOT) + ": ");
			int visible = 0;
			boolean failure = false;
			Object types = call(result, "getResultTypesIterable");
			if (types instanceof Iterable<?> results) {
				for (Object type : results) {
					failure |= Boolean.TRUE.equals(type.getClass().getField("fail").get(type));
					if (Boolean.TRUE.equals(type.getClass().getField("hidden").get(type))) {
						continue;
					}
					Object text = type.getClass().getField("message").get(type);
					if (text instanceof Component detail) {
						if (visible++ > 0) {
							message = message.copy().append(Component.literal("; "));
						}
						message = message.copy().append(detail);
					}
				}
			}
			Object reasons = call(result, "getCustomReasons");
			if (reasons instanceof Iterable<?> custom) {
				for (Object reason : custom) {
					if (reason instanceof Component detail) {
						message = message.copy().append(Component.literal("; ")).append(detail);
						visible++;
					}
				}
			}
			if (visible == 0) {
				message = message.copy().append(Component.literal("Request processed; no status returned"));
			}
			feedback(mc, message, failure, false);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.warn("Could not display OPAC's claim result", e);
		}
	}

	/** Map-local result panel; actionbar fallback when the map was closed meanwhile. */
	private static void feedback(Minecraft mc, Component message, boolean error, boolean pending) {
		if (mc.screen instanceof MapScreen map) {
			map.showClaimFeedback(message, error, pending);
		} else if (mc.player != null) {
			mc.player.displayClientMessage(message, true);
		}
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
			PendingAction request = new PendingAction(dim, left, top, right, bottom, action);
			pending.add(request);
			if (pending.size() > 16) {
				pending.remove(0);
			}
			switch (action) {
				case CLAIM -> call(manager, "requestAreaClaim", dim, left, top, right, bottom, null);
				case UNCLAIM -> call(manager, "requestAreaUnclaim", dim, left, top, right, bottom, null);
				case FORCELOAD -> call(manager, "requestAreaForceload", dim, left, top, right, bottom, true, null);
				case UNFORCELOAD -> call(manager, "requestAreaForceload", dim, left, top, right, bottom, false, null);
			}
			boolean outside = outsideClaimDistance(mc, left, top) || outsideClaimDistance(mc, right, bottom);
			feedback(mc, Component.literal(action.name().toLowerCase(java.util.Locale.ROOT)
				+ " request sent; waiting for OPAC" + (outside ? " (part of the area is outside range)" : "")),
				outside, true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			pending.clear();
			warn(e);
			feedback(mc, Component.literal("Could not submit OPAC claim request"), true, false);
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
