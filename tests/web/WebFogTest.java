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
			String shader=new String(WebFogAssets.read("fog.fsh"),StandardCharsets.UTF_8);
			if(!shader.startsWith("#version 300 es")||!shader.contains("const int LAYERS = 8")||!shader.contains("Previous0")||!shader.contains("flagsAt(texel)"))throw new AssertionError("Shared shader adaptation");
			if(!Arrays.equals(FogNoise.pixels(),FogNoise.pixels()))throw new AssertionError("Stable world noise");
			BufferedImage noise=ImageIO.read(new java.io.ByteArrayInputStream(WebFogAssets.read("fog-noise.png")));
			int expected=FogNoise.pixels()[1234];int actual=noise.getRGB(1234%256,1234/256);
			if((actual&0xffffff)!=((expected&255)<<16|(expected&0xff00)|(expected>>16&255)))throw new AssertionError("Noise channel order");
			if(args.length>0) {Path out=Path.of(args[0]);Files.createDirectories(out);for(String asset:WebFogAssets.FILES)Files.write(out.resolve(asset),WebFogAssets.read(asset));}
			System.out.println("Fog alpha coverage, overview holes, shared shader and deterministic noise checks passed");
		}finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
