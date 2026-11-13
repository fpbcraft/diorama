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
			float[] reach=new float[80*80];Arrays.fill(reach,2f);
			var silhouette=new dev.diorama.bake.CraftShadow.Silhouette(new double[9],new double[]{0,1,0},new double[]{1,0,0},new double[]{0,0,1},-5,-5,8,80,80,reach);
			String caster=VehicleCaster.write(temp,silhouette,new double[3],new double[]{-5,-2,-5,5,2,5});
			if(!caster.equals(VehicleCaster.write(temp,silhouette,new double[3],new double[]{-5,-2,-5,5,2,5})))throw new AssertionError("Stable caster identity");
			byte[] shape=Files.readAllBytes(temp.resolve(caster));var data=ByteBuffer.wrap(shape).order(ByteOrder.LITTLE_ENDIAN);
			if(data.getInt()!=0x44485344||data.getInt()!=1||data.getInt()!=80||data.getInt()!=80||data.getInt()!=8)throw new AssertionError("Shadow silhouette header");
			try(var input=new InflaterInputStream(new java.io.ByteArrayInputStream(shape,128,shape.length-128))){
				var values=ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				for(float value:reach)if(values.getFloat()!=value)throw new AssertionError("Sun silhouette changed");
			}
			if(args.length>0){Files.createDirectories(Path.of(args[0]));Files.copy(temp.resolve(caster),Path.of(args[0]).resolve("fixture-caster.dshd"),StandardCopyOption.REPLACE_EXISTING);}
			System.out.println("Lossless craft colour, alpha, offset, depth and identity checks passed");
		}finally{try(var files=Files.walk(temp)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
