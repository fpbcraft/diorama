package dev.diorama.web;

import java.nio.file.*;
import java.util.Properties;

public final class WebSettingsTest {
	public static void main(String[] args)throws Exception {
		Path config=Files.createTempDirectory("diorama-web-land-");
		try{
			for(String shared:new String[]{"visited","generated"})for(String web:new String[]{"inherit","visited","generated","invalid"}){
				Path file=config.resolve(WebSettings.FILE);Files.writeString(file,"web-map=true\nshare-map=false\nshare-land="+shared+"\nweb-map-land="+web+"\n");
				var settings=WebSettings.load(config);boolean expected=(web.equals("inherit")||web.equals("invalid")?shared:web).equals("generated");
				if(settings.everyChunk()!=expected)throw new AssertionError("Independent web land: "+shared+"/"+web);
				Properties kept=new Properties();try(var reader=Files.newBufferedReader(file)){kept.load(reader);}
				if(!kept.getProperty("share-land").equals(shared)||!kept.getProperty("share-map").equals("false"))throw new AssertionError("Web settings changed in-game discovery policy");
			}
			Files.writeString(config.resolve(WebSettings.FILE),"web-map=true\nshare-land=generated\n");
			if(!WebSettings.load(config).everyChunk())throw new AssertionError("Existing configs must inherit share-land");
			System.out.println("Independent generated web land, private in-game discovery and inherited settings checks passed");
		}finally{try(var files=Files.walk(config)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
