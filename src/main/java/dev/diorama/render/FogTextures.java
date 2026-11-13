package dev.diorama.render;

import dev.diorama.render.gpu.MapTexture;
import org.jspecify.annotations.Nullable;

/** The noise texture every fog draw shares. */
final class FogTextures {
	static final int NOISE_SIZE = 256;

	private static @Nullable MapTexture noise;

	private FogTextures() {
	}

	static MapTexture noise() {
		if (noise == null) {
			noise = createNoise();
		}
		return noise;
	}

	private static MapTexture createNoise() {
		MapTexture texture = MapTexture.create("diorama fog noise", NOISE_SIZE, 1);
		texture.write(0, 0, 0, NOISE_SIZE, noisePixels());
		return texture;
	}

	static int[] noisePixels() { return dev.diorama.bake.FogNoise.pixels(); }
}
