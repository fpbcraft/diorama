package dev.diorama.web;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Mean tile alpha at the in-game fog mask's two-block resolution; independent of terrain colour. */
final class WebCoverage {
	static final int SIZE = 64;
	private WebCoverage() {}
	static Path file(Path jpeg) { return jpeg.resolveSibling(jpeg.getFileName().toString().replace(".jpg", ".fog.png")); }
	static BufferedImage image(int[] abgr) {
		BufferedImage out = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
		for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
			int sum=0;
			for(int dy=0;dy<8;dy++)for(int dx=0;dx<8;dx++)sum+=abgr[(y*8+dy)*512+x*8+dx]>>>24;
			int value=(sum+32)/64;out.setRGB(x,y,value*0x010101);
		}
		return out;
	}
	static byte[] png(int[] abgr) throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(image(abgr),"PNG",out);return out.toByteArray();}
	static void write(Path jpeg,int[] abgr) throws IOException { writeImage(file(jpeg),image(abgr)); }
	static void writeImage(Path file,BufferedImage image) throws IOException {
		Files.createDirectories(file.getParent());Path part=file.resolveSibling(file.getFileName()+".part");
		ImageIO.write(image,"PNG",part.toFile());Files.move(part,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
	}
	static boolean valid(Path jpeg) {
		try {var image=ImageIO.read(file(jpeg).toFile());return image!=null&&image.getWidth()==SIZE&&image.getHeight()==SIZE;}
		catch(IOException|RuntimeException error){return false;}
	}
	static long version(Path jpeg) throws IOException {
		long time=Files.getLastModifiedTime(jpeg).toMillis();Path mask=file(jpeg);
		return Files.isRegularFile(mask)?Math.max(time,Files.getLastModifiedTime(mask).toMillis()):time;
	}
	/** Existing depth retains which pixels hit terrain, even when a legacy JPEG flattened alpha. */
	static boolean recoverDepth(Path jpeg) throws IOException {
		Path depth=jpeg.resolveSibling(jpeg.getFileName().toString().replace(".jpg",".depth"));
		if(!Files.isRegularFile(depth))return false;
		try(var input=Files.newInputStream(depth)) {
			byte[] header=input.readNBytes(16);if(header.length!=16)return false;
			var h=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			int magic=h.getInt(),version=h.getInt(),width=h.getInt(),height=h.getInt();
			if(magic!=0x50454444||(version!=1&&version!=2)||width!=512||height!=512)return false;
			InputStream pixels=version==2?new java.util.zip.InflaterInputStream(input):input;
			byte[] raw=pixels.readNBytes(512*512*4+1);if(raw.length!=512*512*4)return false;
			var values=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);int[] alpha=new int[512*512];
			for(int i=0;i<alpha.length;i++)if(Float.isFinite(values.getFloat()))alpha[i]=0xff000000;
			write(jpeg,alpha);return true;
		}
	}

	static void shrink(Path out,Path[] children) throws IOException {
		BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
		for(int i=0;i<4;i++) {
			if(children[i]==null||!Files.isRegularFile(children[i]))continue;
			Path mask=file(children[i]);BufferedImage child=Files.isRegularFile(mask)?ImageIO.read(mask.toFile()):null;
			if(child!=null&&(child.getWidth()!=SIZE||child.getHeight()!=SIZE))child=null;
			for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
				int sum=0;
				for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)sum+=child==null?0:child.getRGB(x*2+dx,y*2+dy)&255;
				int value=(sum+2)/4;image.setRGB((i&1)*32+x,(i>>1)*32+y,value*0x010101);
			}
		}
		writeImage(file(out),image);
	}
}
