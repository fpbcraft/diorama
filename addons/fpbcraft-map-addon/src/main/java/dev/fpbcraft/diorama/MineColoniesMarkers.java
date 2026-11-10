package dev.fpbcraft.diorama;

import dev.diorama.api.MapAnnotations;
import dev.diorama.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * A read-only view of MineColonies' already-synchronized client colony data. Mirrors the
 * Clipboard's player-resolver request selection rather than treating every internal order as
 * something the player must supply. Requests are grouped at their requesting building and go
 * away when the request manager no longer reports them.
 */
final class MineColoniesMarkers implements MapAnnotations.Source {
    private static final int MAX_MARKERS = 600;
    private List<Waypoint> known = List.of();
    private String dimension = "";
    private int refreshTicks;

    @Override
    public void tick(Minecraft mc) {
        if (mc.level == null) {
            known = List.of();
            dimension = "";
            return;
        }
        if (--refreshTicks > 0) return;
        refreshTicks = 40;
        dimension = mc.level.dimension().location().toString();
        try {
            Object manager = ReflectSupport.staticCall(
                "com.minecolonies.api.colony.IColonyManager", "getInstance");
            Object response = ReflectSupport.call(manager, "getColonyViews", mc.level);
            if (!(response instanceof Iterable<?> colonies)) {
                known = List.of();
                return;
            }
            List<Waypoint> next = new ArrayList<>();
            for (Object colony : colonies) {
                int id = ((Number) ReflectSupport.call(colony, "getID")).intValue();
                String name = String.valueOf(ReflectSupport.call(colony, "getName"));
                String prefix = "minecolonies:" + id + ":";
                Object origin = ReflectSupport.call(colony, "getCenter");
                if (origin instanceof BlockPos center) {
                    next.add(ReflectSupport.marker(prefix + "center", "Colony: " + name,
                        center, dimension, 0xFFF76B15, "MC"));
                }

                Object buildingManager = ReflectSupport.call(colony, "getClientBuildingManager");
                Object buildings = ReflectSupport.call(buildingManager, "getBuildings");
                if (buildings instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (next.size() >= MAX_MARKERS) break;
                        if (!(entry.getKey() instanceof BlockPos pos)) continue;
                        Object building = entry.getValue();
                        int level = ((Number) ReflectSupport.call(building, "getBuildingLevel")).intValue();
                        String buildingName = String.valueOf(ReflectSupport.call(building, "getBuildingDisplayName"));
                        next.add(ReflectSupport.marker(prefix + "building:" + pos.asLong(),
                            buildingName + " · level " + level, pos, dimension, 0xFF8D6B4B, "HUT"));
                    }
                }

                Object orders = ReflectSupport.call(colony, "getWorkOrders");
                if (orders instanceof Collection<?> workOrders) {
                    for (Object order : workOrders) {
                        if (next.size() >= MAX_MARKERS) break;
                        Object location = ReflectSupport.call(order, "getLocation");
                        if (!(location instanceof BlockPos pos)) continue;
                        String description = ReflectSupport.text(ReflectSupport.call(order, "getDisplayName"));
                        Object target = ReflectSupport.call(order, "getTargetLevel");
                        String label = "Construction: " + description + " · level " + target;
                        next.add(ReflectSupport.marker(prefix + "work:" + pos.asLong() + ":" + label,
                            label, pos, dimension, 0xFFE5484D, "JOB"));
                    }
                }

                Object requestManager = ReflectSupport.call(colony, "getRequestManager");
                if (requestManager == null) continue;
                Map<BlockPos, List<String>> requests = new HashMap<>();
                Set<Object> seenTokens = new HashSet<>();
                // Same resolvers used by MineColonies' own Clipboard to find player-facing needs.
                for (String resolverGetter : List.of("getPlayerResolver", "getRetryingRequestResolver")) {
                    Object resolver = ReflectSupport.call(requestManager, resolverGetter);
                    Object assigned = ReflectSupport.call(resolver, "getAllAssignedRequests");
                    if (!(assigned instanceof Collection<?> tokens)) continue;
                    for (Object token : tokens) {
                        Object request = ReflectSupport.call(requestManager, "getRequestForToken", token);
                        for (int i = 0; i < 12 && request != null && Boolean.TRUE.equals(
                                ReflectSupport.call(request, "hasParent")); i++) {
                            Object parent = ReflectSupport.call(request, "getParent");
                            request = ReflectSupport.call(requestManager, "getRequestForToken", parent);
                        }
                        if (request == null) continue;
                        Object requestId = ReflectSupport.call(request, "getId");
                        if (!seenTokens.add(requestId)) continue;
                        Object requester = ReflectSupport.call(request, "getRequester");
                        Object location = ReflectSupport.call(requester, "getLocation");
                        Object position = ReflectSupport.call(location, "getInDimensionLocation");
                        if (!(position instanceof BlockPos pos)) continue;
                        String description = ReflectSupport.text(ReflectSupport.call(request, "getShortDisplayString"));
                        if (!description.isBlank()) {
                            requests.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(description);
                        }
                    }
                }
                for (Map.Entry<BlockPos, List<String>> entry : requests.entrySet()) {
                    if (next.size() >= MAX_MARKERS) break;
                    List<String> needs = entry.getValue();
                    String label = needs.size() == 1 ? "Requested: " + needs.getFirst()
                        : needs.size() + " requests · " + needs.getFirst();
                    next.add(ReflectSupport.marker(prefix + "request:" + entry.getKey().asLong(),
                        label, entry.getKey(), dimension, 0xFFFFC53D, "REQ"));
                }
            }
            known = List.copyOf(next);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read MineColonies' client colony data", exception);
        }
    }

    @Override
    public List<Waypoint> markers(String requestedDimension) {
        return requestedDimension.equals(dimension) ? known : List.of();
    }
}
