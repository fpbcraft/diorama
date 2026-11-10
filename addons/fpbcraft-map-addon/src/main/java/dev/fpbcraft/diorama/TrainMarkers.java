package dev.fpbcraft.diorama;

import dev.diorama.api.MapAnnotations;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Live station and train markers from Create's synchronized railway graph. CRN uses this same
 * Create network; a separate CRN schedule adapter can enrich these markers with departure times.
 */
final class TrainMarkers implements MapAnnotations.Source {
    private List<Waypoint> known = List.of();
    private String dimension = "";
    private int ticks;

    @Override
    public void tick(Minecraft mc) {
        if (mc.level == null) {
            known = List.of();
            dimension = "";
            return;
        }
        if (--ticks > 0) return;
        ticks = 10;
        dimension = mc.level.dimension().location().toString();

        try {
            Object railways = Class.forName("com.simibubi.create.CreateClient").getField("RAILWAYS").get(null);
            Object graphsObject = railways.getClass().getField("trackNetworks").get(railways);
            Object trainsObject = railways.getClass().getField("trains").get(railways);
            if (!(graphsObject instanceof Map<?, ?> graphs) || !(trainsObject instanceof Map<?, ?> trains)) {
                known = List.of();
                return;
            }
            Object stationType = Class.forName("com.simibubi.create.content.trains.graph.EdgePointType")
                .getField("STATION").get(null);
            List<Waypoint> next = new ArrayList<>();

            for (Object graph : graphs.values()) {
                Object stationPoints = ReflectSupport.call(graph, "getPoints", stationType);
                if (!(stationPoints instanceof Iterable<?> stations)) continue;
                for (Object station : stations) {
                    Object id = ReflectSupport.call(station, "getId");
                    Object pos = ReflectSupport.call(station, "getBlockEntityPos");
                    Object dim = ReflectSupport.call(station, "getBlockEntityDimension");
                    if (!(id instanceof UUID) || !(pos instanceof BlockPos position)
                        || !(dim instanceof ResourceKey<?> key) || !key.equals(mc.level.dimension())) continue;
                    String name = String.valueOf(station.getClass().getField("name").get(station));
                    Object dockedTrain = ReflectSupport.call(station, "getPresentTrain");
                    if (dockedTrain != null) {
                        Object trainName = dockedTrain.getClass().getField("name").get(dockedTrain);
                        name += " · " + ReflectSupport.text(trainName);
                    }
                    next.add(ReflectSupport.marker("station:" + id, name, position,
                        dimension, 0xFFF76B15, "ST"));
                }
            }

            for (Object train : trains.values()) {
                Object id = train.getClass().getField("id").get(train);
                Object graph = train.getClass().getField("graph").get(train);
                if (!(id instanceof UUID) || graph == null) continue;
                Object carriagesObject = train.getClass().getField("carriages").get(train);
                if (!(carriagesObject instanceof List<?> carriages) || carriages.isEmpty()) continue;
                Object leading = ReflectSupport.call(carriages.getFirst(), "getLeadingPoint");
                if (leading == null) continue;
                Object node = leading.getClass().getField("node1").get(leading);
                Object location = ReflectSupport.call(node, "getLocation");
                Object dim = ReflectSupport.call(location, "getDimension");
                if (!mc.level.dimension().equals(dim)) continue;
                Object position = ReflectSupport.call(leading, "getPosition", graph);
                if (!(position instanceof Vec3 vec)) continue;
                Object nameComponent = train.getClass().getField("name").get(train);
                String name = ReflectSupport.text(nameComponent);
                Object navigation = train.getClass().getField("navigation").get(train);
                Object destination = navigation == null ? null : navigation.getClass().getField("destination").get(navigation);
                if (destination != null) {
                    name += " → " + destination.getClass().getField("name").get(destination);
                }
                next.add(ReflectSupport.marker("train:" + id, name,
                    BlockPos.containing(vec), dimension, 0xFF0090FF, "TR"));
            }
            known = List.copyOf(next);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read Create's synchronized station/train data", exception);
        }
    }

    @Override
    public List<Waypoint> markers(String requestedDimension) {
        if (!requestedDimension.equals(dimension)) return List.of();
        Settings s = Settings.get();
        if (s.showRailwayStations && s.showRailwayTrains) return known;
        if (!s.showRailwayStations && !s.showRailwayTrains) return List.of();
        return known.stream().filter(pin -> pin.initials.equals("ST")
            ? s.showRailwayStations : s.showRailwayTrains).toList();
    }
}
