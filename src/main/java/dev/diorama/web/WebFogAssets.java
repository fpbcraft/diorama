package dev.diorama.web;

import dev.diorama.bake.FogNoise;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.imageio.ImageIO;

/** WebGL adapter around the game's fog shader and deterministic noise, without client classes. */
final class WebFogAssets {
	static final Set<String> FILES = Set.of("web-fog.js", "fog-mask.js", "fog-mask-task.js", "fog-mask-worker.js", "fog-coverage.js", "fog-gpu.js", "fog-reveal.js", "fog.fsh", "fog-noise.png");
	private static byte[] noise;
	private WebFogAssets() {}
	static byte[] read(String name) throws IOException {
		if (!FILES.contains(name)) return null;
		if (name.equals("fog-noise.png")) return noise();
		if (name.equals("fog.fsh")) {
			try (var in = WebFogAssets.class.getResourceAsStream("/assets/diorama/shaders/core/fog.fsh")) {
				if (in == null) throw new IOException("Missing fog shader");
				String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				int body = source.indexOf("const float MASK_TEXELS");
				if (body < 0) throw new IOException("Unsupported fog shader");
				String uniforms = "#version 300 es\nprecision highp float;\nuniform sampler2D Sampler0;uniform sampler2D Sampler1;uniform sampler2D Sampler2;uniform sampler2D Previous0;uniform sampler2D Previous2;uniform float Reveal;uniform vec4 params;uniform float GameTime;uniform vec4 ColorModulator;in vec2 maskUv;out vec4 fragColor;\n";
				String transition = "int FogPass=1;\n"
					+ "vec4 maskAt(vec2 uv){if(any(lessThan(uv,vec2(0.0)))||any(greaterThan(uv,vec2(1.0))))return vec4(0.0,0.0,1.0,1.0);return FogPass==0?texture(Previous0,uv):texture(Sampler0,uv); }\n"
					+ "vec4 detailAt(vec2 uv){return FogPass==0?texture(Previous2,uv):texture(Sampler2,uv); }\n"
					+ "vec4 flagsAt(ivec2 uv){if(any(lessThan(maskUv,vec2(0.0)))||any(greaterThan(maskUv,vec2(1.0))))return vec4(0.0,0.0,1.0,1.0);return FogPass==0?texelFetch(Previous0,uv,0):texelFetch(Sampler0,uv,0); }\n";

				String adapted = source.substring(body).replace("texture(Sampler0, uv)","maskAt(uv)")
					.replace("texture(Sampler0, maskUv)","maskAt(maskUv)")
					.replace("texture(Sampler2, at / vec2(textureSize(Sampler2, 0)))", "detailAt(at / vec2(textureSize(Sampler2, 0)))")
					.replace("texelFetch(Sampler0, texel, 0)","flagsAt(texel)")
					.replace("void main()", "void renderFog()")
					.replace("discard;", "fragColor=vec4(0.0);return;");
				String reveal = "\nvoid main(){if(Reveal>=1.0){FogPass=1;renderFog();return;}FogPass=0;renderFog();vec4 a=fragColor;FogPass=1;renderFog();float x=a.a*(1.0-Reveal),y=fragColor.a*Reveal;fragColor=vec4((a.rgb*x+fragColor.rgb*y)/max(x+y,.00001),x+y);}";
				return (uniforms + "const float SOFT_TEXELS = 256.0;\n" + transition + adapted + reveal).getBytes(StandardCharsets.UTF_8);
			}
		}
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
