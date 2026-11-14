package dev.diorama.web;

import dev.diorama.render.PinArt;
import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bundled client/server icons, optionally overridden by the client's resolved appearance export. */
final class WebWaypointIcons {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Map<String,PinArt.Image> BUNDLED = bundled();
    private WebWaypointIcons(){}

    /** No resource paths or URLs supplied by players are opened. */
    static Map<String,PinArt.Image> load(Path file) {
        if(file==null||!Files.isRegularFile(file))return BUNDLED;
        try {
            if(Files.size(file)>MAX_BYTES)throw new IOException("Waypoint icon export exceeds 2 MiB");
            var result=new java.util.HashMap<>(BUNDLED);
            result.putAll(parse(Files.readString(file)));
            return Map.copyOf(result);
        }catch(IOException|RuntimeException error){
            dev.diorama.Diorama.LOGGER.warn("Couldn't load exported waypoint icons; using bundled icons",error);
            return BUNDLED;
        }
    }

    private static Map<String,PinArt.Image> bundled() {
        try(var in=WebWaypointIcons.class.getResourceAsStream("/assets/fpbcraft/waypoint-icons.json")) {
            if(in==null)throw new IOException("Bundled waypoint icon data missing");
            byte[] data=in.readNBytes(MAX_BYTES+1);
            if(data.length>MAX_BYTES)throw new IOException("Bundled waypoint icon data exceeds limit");
            return parse(new String(data,StandardCharsets.UTF_8));
        }catch(IOException|RuntimeException error){
            dev.diorama.Diorama.LOGGER.warn("Couldn't load bundled waypoint icons",error);
            return Map.of();
        }
    }

    private static Map<String,PinArt.Image> parse(String text) throws IOException {
        var json=com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        if(json.size()>512)throw new IOException("Too many waypoint icons");
        var result=new java.util.HashMap<String,PinArt.Image>();
        for(var entry:json.entrySet()){
            if(!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||entry.getKey().length()>64)continue;
            var pixels=entry.getValue().getAsJsonArray();if(pixels.size()!=121)continue;
            int[] argb=new int[121];for(int i=0;i<121;i++)argb[i]=pixels.get(i).getAsInt();
            result.put(entry.getKey(),PinArt.fromArgb(11,11,argb));
        }
        return Map.copyOf(result);
    }
}
