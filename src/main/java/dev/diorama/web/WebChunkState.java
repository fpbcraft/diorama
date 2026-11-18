package dev.diorama.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** Durable render-input fingerprints and old footprints, keyed by region coordinates. */
final class WebChunkState {
	private static final int FORMAT = 1;
	static final int CHUNKS = 1024;
	record Drawn(int blocks, int top, int floor) {}

	private final Path folder;

	WebChunkState(Path folder) {
		this.folder = folder;
	}

	Drawn[] read(long region) throws IOException {
		Drawn[] result = new Drawn[CHUNKS];
		Path file = file(region);
		if (Files.notExists(file)) return result;
		try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != FORMAT) throw new IOException("Unknown chunk fingerprint format in " + file);
			for (int i = 0; i < CHUNKS; i++) {
				if (in.readBoolean()) result[i] = new Drawn(in.readInt(), in.readInt(), in.readInt());
			}
		}
		return result;
	}

	void write(long region, Drawn[] entries) throws IOException {
		Path file = file(region);
		if (java.util.Arrays.stream(entries).allMatch(java.util.Objects::isNull)) {
			Files.deleteIfExists(file);
			return;
		}
		Files.createDirectories(folder);
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(part)))) {
			out.writeInt(FORMAT);
			for (Drawn entry : entries) {
				out.writeBoolean(entry != null);
				if (entry == null) continue;
				out.writeInt(entry.blocks());
				out.writeInt(entry.top());
				out.writeInt(entry.floor());
			}
		}
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	Set<Long> regions() throws IOException {
		Set<Long> regions = new LinkedHashSet<>();
		if (Files.notExists(folder)) return regions;
		try (var files = Files.newDirectoryStream(folder, "*.dat")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				int separator = name.indexOf('_');
				if (separator <= 0) continue;
				try {
					int x = Integer.parseInt(name.substring(0, separator));
					int z = Integer.parseInt(name.substring(separator + 1, name.length() - 4));
					regions.add((long) x << 32 | z & 0xffffffffL);
				} catch (NumberFormatException ignored) {
					// Other files do not represent chunk fingerprints.
				}
			}
		}
		return regions;
	}

	private Path file(long region) {
		return folder.resolve((int) (region >> 32) + "_" + (int) region + ".dat");
	}
}
