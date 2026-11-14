package dev.diorama.web;

import java.nio.file.*;

public final class WebAssetsTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),web=root.resolve("web");Files.createDirectories(web);
        check(!WebFrontendAssets.install(web),"core-only fallback");
        if(args[1].equals("bad-api")) {
            try {WebFrontendAssets.register(999,"test",WebAssetsTest.class,"/bundle/");throw new AssertionError("accepted incompatible API");}
            catch(IllegalArgumentException expected){}
            check(!WebFrontendAssets.install(web),"bad provider registered");
        } else {
            Path bundle=root.resolve("bundle");Files.createDirectories(bundle);
            for(String file:WebFrontendAssets.FILES)Files.writeString(bundle.resolve(file),"new:"+file);
            WebFrontendAssets.register(1,"test",WebAssetsTest.class,"/bundle/");
            Files.writeString(web.resolve("index.html"),"old frontend");
            Files.writeString(web.resolve("markers.json"),"world data");
            if(args[1].equals("missing")) {
                Files.delete(bundle.resolve("fog-gpu.js"));
                check(!WebFrontendAssets.install(web),"incomplete bundle accepted");
                check(Files.readString(web.resolve("index.html")).equals("old frontend"),"incomplete bundle partially installed");
            } else {
                check(WebFrontendAssets.install(web),"complete bundle rejected");
                for(String file:WebFrontendAssets.FILES)check(Files.readString(web.resolve(file)).equals("new:"+file),"stale asset: "+file);
            }
            check(Files.readString(web.resolve("markers.json")).equals("world data"),"changed server data");
        }
        System.out.println("Web-assets checks passed: "+args[1]);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
