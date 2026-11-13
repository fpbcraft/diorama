package dev.diorama.web.vehicles;

import dev.diorama.bake.CraftShadow;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

/** Shared sun silhouette, independent of terrain and translation. Projected by the browser. */
public final class VehicleCaster {
	private VehicleCaster() {}
	public static String write(Path root,CraftShadow.Silhouette shape,double[] offset,double[] bounds) throws IOException {
		ByteBuffer header=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(0x44485344).putInt(1).putInt(shape.width()).putInt(shape.height()).putInt(shape.texels()).putInt(0);
		header.putDouble(shape.a0()).putDouble(shape.b0());
		for(double[] values:new double[][]{shape.s(),shape.e1(),shape.e2(),offset})for(double value:values)header.putFloat((float)value);
		for(double value:bounds)header.putFloat((float)value);
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(header.array());
		try(var out=new DeflaterOutputStream(bytes)) {
			ByteBuffer values=ByteBuffer.allocate(shape.reach().length*4).order(ByteOrder.LITTLE_ENDIAN);
			for(float value:shape.reach())values.putFloat(value);
			out.write(values.array());
		}
		try {
			String name=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))+".dshd";
			VehicleSprites.atomic(root.resolve(name),bytes.toByteArray());return name;
		}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
	}
}
