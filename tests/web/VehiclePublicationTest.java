package dev.diorama.web.vehicles;

import dev.diorama.bake.CraftBaker;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Verifies lossless transport of the shared craft tracer's exact colours, alpha and depth. */
public final class VehiclePublicationTest {
	public static void main(String[] args) throws Exception {
		Path temp=Files.createTempDirectory("diorama-craft-");
		try {
			int[] pixels={0xff123456,0x80554433,0,0xffeeccaa};float[] depth={-10,Float.NaN,0,123.25f};
			var image=new CraftBaker.Image(2,2,2,-3.25,4.5,new int[][]{pixels},depth,new double[9],.7,new int[4]);
			var files=VehicleSprites.write(temp,image);
			var again=VehicleSprites.write(temp,image);
			if(!files.equals(again))throw new AssertionError("Stable geometry identities");
			var png=ImageIO.read(temp.resolve(files.atlas()).toFile());
			for(int i=0;i<4;i++){
				int abgr=pixels[i],argb=abgr&0xff00ff00|(abgr&255)<<16|abgr>>16&255;
				if(png.getRGB(i%2,i/2)!=argb)throw new AssertionError("Craft pixel changed at "+i);
			}
			byte[] bytes=Files.readAllBytes(temp.resolve(files.sprite()));ByteBuffer header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			if(header.getInt()!=0x52505344||header.getInt()!=2||header.getInt()!=2||header.getInt()!=2||header.getDouble()!=-3.25||header.getDouble()!=4.5)throw new AssertionError("Sprite header");
			try(var input=new InflaterInputStream(new java.io.ByteArrayInputStream(bytes,64,bytes.length-64))){
				var decoded=ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				for(float value:depth)if(Float.floatToIntBits(decoded.getFloat())!=Float.floatToIntBits(value))throw new AssertionError("Craft depth changed");
			}
			System.out.println("Lossless craft colour, alpha, offset, depth and identity checks passed");
		}finally{try(var files=Files.walk(temp)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
