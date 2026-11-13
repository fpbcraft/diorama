package dev.diorama.web.vehicles;

import dev.diorama.bake.CraftBaker;
import dev.diorama.Projection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Lossless output of the same craft tracer used in-game. No mesh approximation. */
public final class VehicleSprites {
	public record Fileset(long version, String sprite, String atlas) {}
	private VehicleSprites() {}
	public static Fileset write(Path root, CraftBaker.Image image) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		header.put(new byte[] {'D','S','P','R'}).putInt(2).putInt(image.width()).putInt(image.height())
			.putDouble(image.offsetU()).putDouble(image.offsetV())
			.putDouble(image.pitchSin()).putDouble(Math.sqrt(1-image.pitchSin()*image.pitchSin()))
			.putInt(Projection.facing().turns).putInt(Projection.PIXELS_PER_UNIT).putLong(0);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(header.array());
		try (var compressed = new DeflaterOutputStream(bytes)) {
			ByteBuffer depth = ByteBuffer.allocate(image.depth().length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (float value : image.depth()) depth.putFloat(value);
			compressed.write(depth.array());
		}
		BufferedImage picture = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < image.height(); y++) for (int x = 0; x < image.width(); x++) {
			int abgr = image.mips()[0][y * image.edge() + x];
			picture.setRGB(x, y, abgr & 0xff00ff00 | (abgr & 255) << 16 | abgr >> 16 & 255);
		}
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(picture, "PNG", png);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(bytes.toByteArray()); digest.update(png.toByteArray());
			byte[] hash = digest.digest();
			String name = HexFormat.of().formatHex(hash);
			String sprite = name + ".dspr", atlas = name + ".png";
			Files.createDirectories(root);
			atomic(root.resolve(sprite), bytes.toByteArray()); atomic(root.resolve(atlas), png.toByteArray());
			// Exact in a browser Number, and stable across server restarts.
			long version = ByteBuffer.wrap(hash).getLong() & ((1L << 53) - 1);
			return new Fileset(version, sprite, atlas);
		} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	public static void atomic(Path file, byte[] bytes) throws IOException {
		Path part = file.resolveSibling(file.getFileName() + ".part");
		Files.write(part, bytes);
		Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
