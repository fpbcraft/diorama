package dev.diorama.web.vehicles;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Sable removes unloaded ships from its live list; only UNLOADED may keep a last snapshot. */
final class DormantShips {
    interface Snapshot { String key(); String dimension(); }
    <T extends Snapshot> void append(String dimension, java.util.List<T> visible, Map<String,T> cached, int limit) {
        Set<String> present = new java.util.HashSet<>();
        for (T body : visible) present.add(body.key());
        for (T body : cached.values()) {
            if (visible.size() >= limit) break;
            if (body.dimension().equals(dimension) && !present.contains(body.key()) && contains(dimension, body.key())) visible.add(body);
        }
    }
    private final Map<String, String> dimensions = new HashMap<>();
    void event(String dimension, String id, String event) {
        if (event.equals("UNLOADED")) dimensions.put(id, dimension);
        else dimensions.remove(id);
    }
    boolean contains(String dimension, String id) { return dimension.equals(dimensions.get(id)); }
    boolean contains(String id) { return dimensions.containsKey(id); }
    void forget(String id) { dimensions.remove(id); }
    void retain(Set<String> cached) { dimensions.keySet().retainAll(cached); }
}
