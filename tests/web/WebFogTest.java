package dev.diorama.web;

import dev.diorama.bake.FogNoise;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;

/** Production fog assets and coverage export, without a Minecraft runtime. */
public final class WebFogTest {
	public static void main(String[] args) throws Exception {
		int[] pixels=new int[512*512];
		Arrays.fill(pixels,0xff000000); // Opaque dark blocks are known, independent of their colour.
		if((WebCoverage.image(pixels).getRGB(0,0)&255)!=255)throw new AssertionError("Dark terrain must stay known");
		Arrays.fill(pixels,0);
		for(int y=0;y<8;y++)for(int x=0;x<4;x++)pixels[y*512+x]=0xff123456;
		if((WebCoverage.image(pixels).getRGB(0,0)&255)!=128)throw new AssertionError("Mean coverage");
		if((WebCoverage.image(pixels).getRGB(1,0)&255)!=0)throw new AssertionError("Missing ground");
		Path root=Files.createTempDirectory("diorama-fog-");
		try {
			Path child=root.resolve("child.jpg");Files.write(child,new byte[]{1});
			Arrays.fill(pixels,0xff000000);WebCoverage.write(child,pixels);
			Path parent=root.resolve("parent.jpg");WebCoverage.shrink(parent,new Path[]{child,null,null,null});
			BufferedImage mask=ImageIO.read(WebCoverage.file(parent).toFile());
			if((mask.getRGB(15,15)&255)!=255||(mask.getRGB(45,15)&255)!=0||(mask.getRGB(15,45)&255)!=0)throw new AssertionError("Overview holes");
			Path legacy=root.resolve("legacy.jpg");Files.write(legacy,new byte[]{1});
			WebCoverage.shrink(parent,new Path[]{legacy,null,null,null});
			if((ImageIO.read(WebCoverage.file(parent).toFile()).getRGB(10,10)&255)!=0)throw new AssertionError("A legacy JPEG must not reveal its unknown pixels");
			var depthHeader=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			depthHeader.putInt(0x50454444).putInt(2).putInt(512).putInt(512);
			var depth=java.nio.ByteBuffer.allocate(512*512*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			for(int y=0;y<512;y++)for(int x=0;x<512;x++)depth.putFloat(y<8&&x<4?0:Float.NaN);
			try(var out=Files.newOutputStream(root.resolve("legacy.depth"))){out.write(depthHeader.array());try(var zip=new java.util.zip.DeflaterOutputStream(out)){zip.write(depth.array());}}
			if(!WebCoverage.recoverDepth(legacy)||(ImageIO.read(WebCoverage.file(legacy).toFile()).getRGB(0,0)&255)!=128)throw new AssertionError("Legacy depth coverage");
			Files.setLastModifiedTime(legacy,java.nio.file.attribute.FileTime.fromMillis(1000));Files.setLastModifiedTime(WebCoverage.file(legacy),java.nio.file.attribute.FileTime.fromMillis(2000));
			if(WebCoverage.version(legacy)!=2000)throw new AssertionError("Coverage repair must invalidate the tile revision");
			Path dimension=root.resolve("dimension");Files.createDirectories(dimension.resolve("0"));Files.createDirectories(dimension.resolve("1"));
			Path base=dimension.resolve("0/-2_-3.jpg");Files.write(base,new byte[]{4,5});
			var repair=new WebCoverageRepair(dimension);repair.add(0,-2,-3);repair.add(1,-1,-2);
			var pending=new java.util.ArrayList<Long>();repair.step(1,pending::add);
			if(!pending.equals(java.util.List.of((-2L<<32)|(-3&0xffffffffL)))||!repair.pending()||!Arrays.equals(Files.readAllBytes(base),new byte[]{4,5}))throw new AssertionError("Missing alpha queues a bounded redraw and keeps the JPEG");
			repair.step(1,pending::add);
			if(repair.pending()||!Files.isRegularFile(dimension.resolve("fog-coverage-v2.txt")))throw new AssertionError("Coverage repair checkpoint");
			String shader=new String(WebFogAssets.read("fog.fsh"),StandardCharsets.UTF_8);
			if(!shader.startsWith("#version 300 es")||!shader.contains("Previous0")||!shader.contains("vec4(color * alpha, alpha)")||shader.contains("for (int"))throw new AssertionError("Premultiplied single-pass web cloud shader");
			if(!Arrays.equals(FogNoise.pixels(),FogNoise.pixels()))throw new AssertionError("Stable world noise");
			BufferedImage noise=ImageIO.read(new java.io.ByteArrayInputStream(WebFogAssets.read("fog-noise.png")));
			int expected=FogNoise.pixels()[1234];int actual=noise.getRGB(1234%256,1234/256);
			if((actual&0xffffff)!=((expected&255)<<16|(expected&0xff00)|(expected>>16&255)))throw new AssertionError("Noise channel order");
			try(var in=WebFogAssets.class.getResourceAsStream("/diorama/web/fog-noise.png")) {
				if(in==null)throw new AssertionError("Standalone frontend noise missing");
				var standalone=ImageIO.read(in);for(int y=0;y<256;y++)for(int x=0;x<256;x++)if(standalone.getRGB(x,y)!=noise.getRGB(x,y))throw new AssertionError("Standalone noise differs from the server");
			}
			if(args.length>0) {Path out=Path.of(args[0]);Files.createDirectories(out);for(String asset:WebFogAssets.FILES)Files.write(out.resolve(asset),WebFogAssets.read(asset));}
			System.out.println("Fog alpha coverage, overview holes, web shader and deterministic noise checks passed");
		}finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
