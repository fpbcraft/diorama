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
			if(!WebSettings.load(config).vehicleHistory() || WebSettings.load(config).vehicleHistoryIntervalMs()!=500)
				throw new AssertionError("Existing configs retain history with a separate 500 ms sample interval");
			for (String interval : new String[]{"0", "1000", "90000", "invalid"}) {
				Files.writeString(config.resolve(WebSettings.FILE), "web-map=true\nweb-map-vehicles=true\nweb-map-vehicle-history= false \nweb-map-vehicle-history-interval-ms="+interval+"\n");
				var settings=WebSettings.load(config);
				int expected=interval.equals("0")?100:interval.equals("1000")?1000:interval.equals("90000")?60000:500;
				if(settings.vehicleHistory() || !settings.vehicles() || settings.vehicleHistoryIntervalMs()!=expected)
					throw new AssertionError("History must disable independently of live vehicles; bounded interval "+interval);
			}
			System.out.println("Independent generated web land, private in-game discovery and inherited settings checks passed");
		}finally{try(var files=Files.walk(config)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
	}
}
