package dev.diorama.web;

import dev.diorama.render.PinArt;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Actual in-game pin pixels; initials and labels are accessible browser text. */
final class WebPins {
	private WebPins() {}
	static void install(Path root) throws IOException {
		Path folder=root.resolve("assets/diorama-markers");Files.createDirectories(folder);
		write(folder.resolve("ship.png"),0xff8e4ec6);write(folder.resolve("train.png"),0xffffc53d);
	}
	static String imageKey(PinArt.Image image) {
		var bytes=java.nio.ByteBuffer.allocate(4*image.width*image.height);
		for(int y=0;y<image.height;y++)for(int x=0;x<image.width;x++)bytes.putInt(image.argb(x,y));
		return java.util.UUID.nameUUIDFromBytes(bytes.array()).toString();
	}
	static String iconName(String id) {
		String name=id.startsWith("diorama:")?id.substring(8):"";
		return java.util.Set.of("home","bed","farm","animals","villagers","mine","storage","portal","danger","treasure").contains(name)?name:"";
	}
	static PinArt.Art art(int color,String icon) throws IOException {
		if(!icon.isEmpty())try(var in=WebPins.class.getResourceAsStream("/assets/diorama/textures/waypoint_icons/"+icon+".png")) {
			if(in!=null){var image=ImageIO.read(in);int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());return PinArt.iconPin(PinArt.fromArgb(image.getWidth(),image.getHeight(),pixels),color);}
		}
		return PinArt.standing(color,false,15,0);
	}
	static void write(Path file,int color) throws IOException { write(file,art(color,"")); }
	static void write(Path file,PinArt.Art art) throws IOException {
		var pixels=art.image();
		BufferedImage image=new BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<pixels.height;y++)for(int x=0;x<pixels.width;x++)image.setRGB(x,y,pixels.argb(x,y));
		ImageIO.write(image,"PNG",file.toFile());
	}
}
