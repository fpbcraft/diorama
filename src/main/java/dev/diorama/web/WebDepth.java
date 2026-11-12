package dev.diorama.web;

import dev.diorama.bake.TileSurface;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;

/** Camera depth, not height: matches TileBaker/CraftPainter, including negative coordinates. */
final class WebDepth {
	private WebDepth() {}
	static void write(Path file, TileSurface surface) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		header.put(new byte[] {'D', 'D', 'E', 'P'}).putInt(2).putInt(surface.width()).putInt(surface.height());
		ByteBuffer values = ByteBuffer.allocate(surface.near().length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : surface.near()) values.putFloat(value);
		Files.createDirectories(file.getParent());
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (var output = Files.newOutputStream(part)) {
			output.write(header.array());
			var deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
			try {
				var compressed = new java.util.zip.DeflaterOutputStream(output, deflater);
				compressed.write(values.array()); compressed.finish();
			} finally { deflater.end(); }
		}
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
