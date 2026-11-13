package dev.diorama.web;

import dev.diorama.render.PinArt;
import java.nio.file.*;
import java.io.IOException;

/** Admin-installed icon data, separate from the standalone native pin pixel exporter. */
final class WebWaypointIcons {
	private WebWaypointIcons(){}
	/** Resolved, already cropped and tiled client pixels. No resource paths from players are opened. */
	static java.util.Map<String,PinArt.Image> load(Path file) {
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
}
