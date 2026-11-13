package dev.diorama.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.diorama.Diorama;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated owner snapshots; only explicitly selected markers enter the public web root. */
final class WebWaypoints implements AutoCloseable {
	static final int MESSAGE=6, PER_PLAYER=64, TOTAL=4096;
	record Marker(UUID id,String name,String initials,String icon,String dimension,int x,int y,int z,int color) {
		Marker {
			if(id==null||name==null||name.isBlank()||name.length()>64||initials==null||initials.length()>8||icon==null||icon.length()>64
				||dimension==null||dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||Math.abs((long)x)>30000000||Math.abs((long)z)>30000000||y< -2048||y>4096
				||name.codePoints().anyMatch(Character::isISOControl)||initials.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid public waypoint");
		}
	}
	private final Path file,root;
	private Map<String,dev.diorama.render.PinArt.Image> icons=Map.of();
	private final Map<UUID,List<Marker>> owners=new LinkedHashMap<>();
	private final Map<UUID,Long> next=new HashMap<>();
	private final AtomicBoolean queued=new AtomicBoolean();
	private final ExecutorService writer=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"Diorama public waypoints");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;});
	private final java.util.function.LongSupplier clock;
	private long revision;
	private volatile boolean closed;
	WebWaypoints(Path world,Path root) {this(world,root,System::currentTimeMillis);}
	WebWaypoints(Path world,Path root,java.util.function.LongSupplier clock) {
		this.clock=clock;
		this.file=world.resolve("diorama-public-waypoints.json");this.root=root;
		try {
			if(Files.isRegularFile(file)&&Files.size(file)<4*1024*1024) {
				Map<UUID,List<Marker>> stored=new Gson().fromJson(Files.readString(file),new TypeToken<Map<UUID,List<Marker>>>(){}.getType());
				if(stored!=null)for(var entry:stored.entrySet())replace(entry.getKey(),entry.getValue());
			}
		}catch(IOException|RuntimeException error){Diorama.LOGGER.warn("Couldn't restore public waypoints",error);}
	}
	synchronized void receive(UUID owner,byte[] bytes) throws IOException {
		next.entrySet().removeIf(entry->entry.getValue()<clock.getAsLong());
		if(closed||bytes.length>32768||clock.getAsLong()<next.getOrDefault(owner,0L))return;
		try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
			if(in.readUnsignedByte()!=MESSAGE)return;
			int count=in.readUnsignedByte();if(count>PER_PLAYER)throw new IOException("Too many public waypoints");
			List<Marker> markers=new ArrayList<>();Set<UUID> ids=new HashSet<>();
			for(int i=0;i<count;i++){
				Marker marker=new Marker(new UUID(in.readLong(),in.readLong()),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
				if(!ids.add(marker.id))throw new IOException("Duplicate waypoint id");markers.add(marker);
			}
			if(in.available()!=0)throw new IOException("Trailing waypoint data");
			if(replace(owner,markers)){next.put(owner,clock.getAsLong()+1000);schedule();}
		}catch(IllegalArgumentException error){throw new IOException("Invalid public waypoint",error);}
	}
	private boolean replace(UUID owner,List<Marker> markers) {
		if(owner==null||markers==null||markers.size()>PER_PLAYER||markers.stream().anyMatch(Objects::isNull))return false;
		int count=owners.values().stream().mapToInt(List::size).sum()-owners.getOrDefault(owner,List.of()).size()+markers.size();
		if(count>TOTAL||(!owners.containsKey(owner)&&owners.size()>=TOTAL))return false;
		if(markers.equals(owners.getOrDefault(owner,List.of())))return false;
		if(markers.isEmpty())owners.remove(owner);else owners.put(owner,List.copyOf(markers));revision++;return true;
	}
	void icons(Path file){icons=WebWaypointIcons.load(file);}
	void start(){schedule();}
	private void schedule(){if(!closed&&queued.compareAndSet(false,true))writer.execute(this::flush);}
	private void flush(){
		try {
			while(true) {
				Map<UUID,List<Marker>> snapshot;long at;
				synchronized(this){snapshot=new LinkedHashMap<>(owners);at=revision;}
				Files.createDirectories(root.resolve("assets/diorama-markers"));
				List<Map<String,Object>> published=new ArrayList<>();Set<String> images=new HashSet<>();
				for(var entry:snapshot.entrySet())for(Marker marker:entry.getValue()) {
					String id=UUID.nameUUIDFromBytes((entry.getKey()+"/"+marker.id).getBytes(StandardCharsets.UTF_8)).toString();
					String icon=WebPins.iconName(marker.icon);
					var custom=icons.get(marker.icon);
					String key=custom==null?icon:WebPins.imageKey(custom);
					String image="assets/diorama-markers/pin-"+Integer.toHexString(marker.color&0xffffff)+"-"+key+".png";
					var art=custom==null?WebPins.art(marker.color|0xff000000,icon):dev.diorama.render.PinArt.iconPin(custom,marker.color|0xff000000);
					if(!Files.isRegularFile(root.resolve(image)))WebPins.write(root.resolve(image),art);
					images.add(root.resolve(image).getFileName().toString());
					published.add(Map.ofEntries(Map.entry("id",id),Map.entry("name",marker.name),Map.entry("initials",icon.isEmpty()&&custom==null?marker.initials:""),Map.entry("icon",image),Map.entry("dimension",marker.dimension),Map.entry("x",marker.x),Map.entry("y",marker.y),Map.entry("z",marker.z),Map.entry("width",art.image().width),Map.entry("height",art.image().height),Map.entry("anchorX",art.anchorX()),Map.entry("anchorY",art.anchorY())));
				}
				atomic(file,new Gson().toJson(snapshot));atomic(root.resolve("markers.json"),new Gson().toJson(Map.of("markers",published)));
				try(var files=Files.newDirectoryStream(root.resolve("assets/diorama-markers"),"pin-*.png")){for(Path image:files)if(!images.contains(image.getFileName().toString()))Files.deleteIfExists(image);}
				synchronized(this){if(at==revision){queued.set(false);return;}}
			}
		}catch(IOException|RuntimeException error){queued.set(false);Diorama.LOGGER.warn("Couldn't publish selected web waypoints",error);}
	}

	private static void atomic(Path file,String text)throws IOException {
		Path part=file.resolveSibling(file.getFileName()+".part");Files.writeString(part,text);Files.move(part,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
	}
	@Override public void close(){closed=true;writer.shutdown();}
}
