package dev.diorama.web;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/** Exercise the real authenticated-owner store, public export, pin pixels, and restart lifecycle. */
public final class WebWaypointsTest {
	static UUID marker=UUID.randomUUID();
	static String icon="diorama:home";
	static byte[] packet(String name,int color)throws Exception {
		var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(6);out.writeByte(name==null?0:1);
		if(name!=null){out.writeLong(marker.getMostSignificantBits());out.writeLong(marker.getLeastSignificantBits());out.writeUTF(name);out.writeUTF("AB");out.writeUTF(icon);out.writeUTF("minecraft:overworld");out.writeInt(-12);out.writeInt(64);out.writeInt(10);out.writeInt(color);}
		return bytes.toByteArray();
	}
	static String waitFor(Path file,String text,boolean present)throws Exception {
		for(int i=0;i<300;i++){if(Files.isRegularFile(file)){String value=Files.readString(file);if(value.contains(text)==present)return value;}Thread.sleep(10);}
		throw new AssertionError("Public snapshot not updated: "+text);
	}
	public static void main(String[] args)throws Exception {
		Path world=Files.createTempDirectory("diorama-waypoints-"),web=Files.createDirectory(world.resolve("web"));
		AtomicLong clock=new AtomicLong(1000);UUID a=UUID.randomUUID(),b=UUID.randomUUID();
		WebWaypoints store=new WebWaypoints(world,web,clock::get);
		try {
			store.start();store.receive(a,packet("Public harbour",0xffe5484d));
			String first=waitFor(web.resolve("markers.json"),"Public harbour",true);
			if(first.contains(a.toString())||first.contains("owner"))throw new AssertionError("Owner identity exposed");
			store.receive(b,packet("Other owner pin",0xff0090ff));
			String both=waitFor(web.resolve("markers.json"),"Other owner pin",true);
			if(!both.contains("Public harbour"))throw new AssertionError("Another owner overwrote the waypoint");
			store.receive(a,packet(null,0));
			if(!Files.readString(web.resolve("markers.json")).contains("Public harbour"))throw new AssertionError("Rate bound ignored");
			clock.set(3000);store.receive(a,packet(null,0));
			waitFor(web.resolve("markers.json"),"Public harbour",false);
			clock.set(5000);
			try{store.receive(a,packet("Hidden\u0000injection",0));throw new AssertionError("Control characters accepted");}catch(IOException expected){}
			byte[] invalid=packet("Outside world",0);java.nio.ByteBuffer.wrap(invalid).putInt(invalid.length-16,30000001);
			try{store.receive(a,invalid);throw new AssertionError("Coordinate bounds ignored");}catch(IOException expected){}
			try{store.receive(a,new byte[]{6,65});throw new AssertionError("Per-owner marker bound ignored");}catch(IOException expected){}
			store.close();Files.delete(web.resolve("markers.json"));
			WebWaypoints restored=new WebWaypoints(world,web,clock::get);restored.start();
			waitFor(web.resolve("markers.json"),"Other owner pin",true);restored.close();
			int[] pixels=new int[121];java.util.Arrays.fill(pixels,0xffff00ff);
			Path iconFile=world.resolve("waypoint-icons.json");Files.writeString(iconFile,new com.google.gson.Gson().toJson(Map.of("example:station",pixels,"example:invalid",new int[3])));
			var icons=WebWaypointIcons.load(iconFile);if(icons.size()!=1||!icons.containsKey("example:station"))throw new AssertionError("Exported icon bounds ignored");
			WebWaypoints custom=new WebWaypoints(world,web,clock::get);custom.icons(iconFile);custom.start();
			icon="example:station";clock.set(7000);custom.receive(a,packet("Custom station",0xff46a758));
			String published=waitFor(web.resolve("markers.json"),"Custom station",true);
			String key=WebPins.imageKey(icons.get(icon));
			if(!published.contains(key))throw new AssertionError("Custom icon not published");
			var expected=dev.diorama.render.PinArt.iconPin(icons.get(icon),0xff46a758).image();
			var actual=ImageIO.read(web.resolve("assets/diorama-markers/pin-46a758-"+key+".png").toFile());
			for(int y=0;y<expected.height;y++)for(int x=0;x<expected.width;x++)if(expected.argb(x,y)!=actual.getRGB(x,y))throw new AssertionError("Exported icon changed pixels");
			custom.close();icon="diorama:home";
			WebPins.install(web);
			var image=ImageIO.read(web.resolve("assets/diorama-markers/ship.png").toFile());
			var nativePin=dev.diorama.render.PinArt.standing(0xff8e4ec6,false,15,0).image();
			for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(image.getRGB(x,y)!=nativePin.argb(x,y))throw new AssertionError("Native pin pixel changed");
			if(args.length>0){Path output=Path.of(args[0]);WebPins.install(output);WebPins.write(output.resolve("assets/diorama-markers/home.png"),WebPins.art(0xff46a758,"home"));}
			System.out.println("Public waypoint ownership, opt-out, bounds, persistence and native pin checks passed");
		}finally{store.close();}
	}
}
