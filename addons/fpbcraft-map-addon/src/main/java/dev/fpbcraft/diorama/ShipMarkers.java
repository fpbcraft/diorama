package dev.fpbcraft.diorama;

import dev.diorama.api.MapAnnotations;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Moving pins for named Sable sub-levels (Create Aeronautics vehicles live on these sub-levels).
 * No server packet is needed: Sable already synchronizes the client sub-levels and their poses.
 */
final class ShipMarkers implements MapAnnotations.Source {
    private List<Waypoint> known = List.of();
    private String dimension = "";
    private int untilRefresh;

    @Override
    public void tick(Minecraft mc) {
        if (mc.level == null) {
            known = List.of();
            dimension = "";
            return;
        }
        if (!Settings.get().showSableShipMarkers) {
            known = List.of();
            untilRefresh = 0;
            return;
        }
        if (--untilRefresh > 0) {
            return;
        }
        untilRefresh = 2; // Moving markers, unlike static colony or station markers.
        dimension = mc.level.dimension().location().toString();
        try {
            Object container = ReflectSupport.staticCall(
                "dev.ryanhcode.sable.api.sublevel.SubLevelContainer", "getContainer", mc.level);
            if (container == null) {
                known = List.of();
                return;
            }
            Object value = ReflectSupport.call(container, "getAllSubLevels");
            if (!(value instanceof Iterable<?> levels)) {
                known = List.of();
                return;
            }
            List<Waypoint> next = new ArrayList<>();
            for (Object level : levels) {
                Object nameValue = ReflectSupport.call(level, "getName");
                String name=nameValue instanceof String shipName?shipName:"";
                if(name.isBlank()){if(!Settings.get().showAllVehicleMarkers)continue;name="Unnamed ship";}
                Object idValue = ReflectSupport.call(level, "getUniqueId");
                if (!(idValue instanceof UUID id)) {
                    continue;
                }
                Object pose = ReflectSupport.call(level, "logicalPose");
                Object position = ReflectSupport.call(pose, "position");
                double x = ((Number) ReflectSupport.call(position, "x")).doubleValue();
                double y = ((Number) ReflectSupport.call(position, "y")).doubleValue();
                double z = ((Number) ReflectSupport.call(position, "z")).doubleValue();
                BlockPos pos = BlockPos.containing(x, y, z);
                Waypoint marker = ReflectSupport.marker("sable:" + id, name + " · Y " + pos.getY(),
                    pos, dimension, 0xFF8E4EC6, "SHIP");
                next.add(marker);
            }
            known = List.copyOf(next);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read Sable's client sub-levels", exception);
        }
    }

    @Override
    public List<Waypoint> markers(String requestedDimension) {
        return Settings.get().showSableShipMarkers && requestedDimension.equals(dimension)
            ? known : List.of();
    }
}
