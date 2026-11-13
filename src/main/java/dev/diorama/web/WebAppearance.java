package dev.diorama.web;

import com.google.gson.Gson;
import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.bake.VanillaTable;
import java.nio.file.*;

/** Optional admin-installed client appearance, never uploaded by an untrusted map visitor. */
final class WebAppearance {
	private WebAppearance() {}
	static String fingerprint(VanillaTable table) {
		try {
			var bytes = new java.io.ByteArrayOutputStream(); table.write(bytes);
			var digest = java.security.MessageDigest.getInstance("SHA-256"); digest.update(bytes.toByteArray());
			digest.update(new Gson().toJson(MapStyle.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (java.io.IOException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}

	static VanillaTable load(Path configFolder) {
		Path folder = configFolder.resolve("diorama-web-appearance");
		if (Files.isRegularFile(folder.resolve("looks.dat"))) {
			try (var input = Files.newInputStream(folder.resolve("looks.dat"))) {
				VanillaTable table = VanillaTable.read(input);
				if (Files.isRegularFile(folder.resolve("style.json"))) {
					MapStyle style = new Gson().fromJson(Files.readString(folder.resolve("style.json")), MapStyle.class);
					if (style == null || !Double.isFinite(style.pitchDegrees()) || style.pitchDegrees() < 15 || style.pitchDegrees() > 80
						|| style.sunTint() == null || style.shadowTint() == null || style.sunTint().length != 3 || style.shadowTint().length != 3) throw new IllegalArgumentException("Invalid web map style");
					MapStyle.set(style);
				}
				return table;
			} catch (java.io.IOException | RuntimeException error) { Diorama.LOGGER.warn("Couldn't load client web appearance; using shipped colours", error); }
		}
		return VanillaTable.shipped();
	}
}
