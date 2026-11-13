package dev.diorama.web.vehicles;

import dev.diorama.bake.TileSurface;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Published terrain lighting for the shared CraftShadow tracer; no extra world reads. */
public final class VehicleGround {
	private VehicleGround() {}
	public static void write(Path file, TileSurface surface) throws IOException {
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (var out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(part)))) {
			out.writeInt(0x44475244);out.writeInt(1);out.writeInt(surface.width());out.writeInt(surface.height());
			for (int i=0;i<surface.y().length;i++) {out.writeFloat(surface.y()[i]);out.writeFloat(surface.near()[i]);out.writeByte(surface.sun()[i]);out.writeInt(surface.albedo()[i]);}
		}
		Files.move(part,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
	}
	public static TileSurface read(Path file) throws IOException {
		try (var in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
			if(in.readInt()!=0x44475244||in.readInt()!=1)throw new IOException("Invalid published ground");
			int width=in.readInt(),height=in.readInt();if(width!=512||height!=512)throw new IOException("Invalid ground dimensions");
			int n=width*height;float[] y=new float[n],near=new float[n];byte[] sun=new byte[n];int[] albedo=new int[n];
			for(int i=0;i<n;i++){y[i]=in.readFloat();near[i]=in.readFloat();sun[i]=in.readByte();albedo[i]=in.readInt();}
			if(in.read()!=-1)throw new IOException("Trailing ground data");
			return new TileSurface(0,0,0,width,height,y,near,new boolean[0],sun,albedo,new int[0],new float[0]);
		}
	}
}
