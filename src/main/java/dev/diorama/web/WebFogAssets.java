package dev.diorama.web;

import dev.diorama.bake.FogNoise;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Set;
import javax.imageio.ImageIO;

/** Web-specific cloud shader and shared deterministic noise, without client classes. */
final class WebFogAssets {
	static final Set<String> FILES = Set.of("web-fog.js", "fog-mask.js", "fog-mask-task.js", "fog-mask-worker.js", "fog-coverage.js", "fog-gpu.js", "fog-reveal.js", "fog.fsh", "fog-noise.png");
	private static byte[] noise;
	private WebFogAssets() {}
	static byte[] read(String name) throws IOException {
		if (!FILES.contains(name)) return null;
		if (name.equals("fog-noise.png")) return noise();
		try (var in = WebFogAssets.class.getResourceAsStream("/diorama/web/" + name)) { return in == null ? null : in.readAllBytes(); }
	}
	private static synchronized byte[] noise() throws IOException {
		if (noise != null) return noise;
		int[] pixels = FogNoise.pixels();
		BufferedImage image = new BufferedImage(FogNoise.SIZE, FogNoise.SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < pixels.length; i++) {
			int p = pixels[i]; image.setRGB(i % FogNoise.SIZE, i / FogNoise.SIZE, 0xff000000 | (p & 0xff00) | (p & 255) << 16 | p >> 16 & 255);
		}
		var out = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", out); return noise = out.toByteArray();
	}
}
