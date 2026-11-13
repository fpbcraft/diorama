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
	/** Resolved, already cropped and tiled client pixels. No resource paths from players are opened. */
	static java.util.Map<String,PinArt.Image> icons(Path file) {
		if(file==null||!Files.isRegularFile(file))return java.util.Map.of();
		try {
			if(Files.size(file)>2*1024*1024)throw new IOException("Waypoint icon export exceeds 2 MiB");
			var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if(json.size()>512)throw new IOException("Too many waypoint icons");
			var result=new java.util.HashMap<String,PinArt.Image>();
			for(var entry:json.entrySet()){
				if(!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||entry.getKey().length()>64)continue;
				var pixels=entry.getValue().getAsJsonArray();if(pixels.size()!=121)continue;
				int[] argb=new int[121];for(int i=0;i<121;i++)argb[i]=pixels.get(i).getAsInt();
				result.put(entry.getKey(),PinArt.fromArgb(11,11,argb));
			}return java.util.Map.copyOf(result);
		}catch(IOException|RuntimeException error){dev.diorama.Diorama.LOGGER.warn("Couldn't load exported waypoint icons; using built-in icons",error);return java.util.Map.of();}
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
