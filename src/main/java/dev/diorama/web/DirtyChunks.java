package dev.diorama.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, coalescing capture schedule. Continuous building cannot postpone a chunk forever. */
final class DirtyChunks {
	private final Map<Long, Long> due = new LinkedHashMap<>();
	private final int capacity;
	private final long delay;
	DirtyChunks(int capacity, long delay) { this.capacity = capacity; this.delay = delay; }
	synchronized boolean mark(long key, long now) {
		if (due.containsKey(key)) return true;
		if (due.size() >= capacity) return false;
		due.put(key, now + delay); return true;
	}
	synchronized java.util.Set<Long> snapshot() { return java.util.Set.copyOf(due.keySet()); }
	synchronized Long poll(long now) {
		var iterator = due.entrySet().iterator();
		if (!iterator.hasNext()) return null;
		var entry = iterator.next();
		if (entry.getValue() > now) return null;
		iterator.remove(); return entry.getKey();
	}
}
