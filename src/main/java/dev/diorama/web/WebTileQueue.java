package dev.diorama.web;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pending base tiles for one dimension, with an atomic on-disk checkpoint.
 * All mutation happens on the single Diorama web-map worker thread. Disk checkpoints
 * are written before advancing the saved region-scan cursor: a crash can repeat
 * some tiles but must never silently lose a queued tile.
 */
final class WebTileQueue {
	private final Path file;
	private final LinkedHashSet<Long> entries = new LinkedHashSet<>();

	WebTileQueue(Path dimensionFolder) {
		this.file = dimensionFolder.resolve("pending-tiles.txt");
		if (!Files.isRegularFile(this.file)) return;
		try {
			for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] parts = line.split(" ", -1);
				if (parts.length == 2) {
					try {
						this.entries.add(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
					} catch (NumberFormatException e) {
						Diorama.LOGGER.warn("Skipping invalid web-map pending tile: {}", line);
					}
				}
			}
			Diorama.LOGGER.info("Web map: restored {} pending tiles from {}", this.entries.size(), this.file);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't restore pending web-map tiles from {}", this.file, e);
		}
	}

	static long key(int u, int v) {
		return (long) u << 32 | v & 0xffffffffL;
	}

	void addAll(Collection<Long> tiles) {
		this.entries.addAll(tiles);
	}

	/** Operator-requested tiles take priority over background discovery tasks. */
	void addFirst(Collection<Long> tiles) {
		LinkedHashSet<Long> first = new LinkedHashSet<>(tiles);
		first.addAll(this.entries);
		this.entries.clear();
		this.entries.addAll(first);
	}

	void add(long tile) {
		this.entries.add(tile);
	}

	long first() {
		return this.entries.iterator().next();
	}

	void remove(long tile) {
		this.entries.remove(tile);
	}

	boolean isEmpty() {
		return this.entries.isEmpty();
	}

	int size() {
		return this.entries.size();
	}

	Set<Long> snapshot() {
		return new LinkedHashSet<>(this.entries);
	}

	void clear() {
		this.entries.clear();
	}

	/** Returns false when a checkpoint failed: the scanner must not advance. */
	boolean save() {
		Path part = this.file.resolveSibling(this.file.getFileName() + ".part");
		StringBuilder lines = new StringBuilder("# Diorama base JPEG tiles awaiting render\n");
		for (long key : this.entries) {
			lines.append((int) (key >> 32)).append(' ').append((int) key).append('\n');
		}
		try {
			Files.createDirectories(this.file.getParent());
			Files.writeString(part, lines, StandardCharsets.UTF_8);
			Files.move(part, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't persist pending web-map tiles in {}", this.file, e);
			return false;
		}
	}
}
