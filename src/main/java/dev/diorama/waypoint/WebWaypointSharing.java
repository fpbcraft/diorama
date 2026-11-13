package dev.diorama.waypoint;

import dev.diorama.share.ShareClient;
import dev.diorama.share.ShareProtocol;
import java.io.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Coalesces explicit public waypoint snapshots over the authenticated Minecraft connection. */
public final class WebWaypointSharing {
	private static Object connection;
	private static byte[] previous;
	private static long next;
	private WebWaypointSharing() {}
	public static void tick(Minecraft mc) {
		if(connection!=mc.getConnection()){connection=mc.getConnection();previous=null;next=0;}
		if(mc.player==null||ShareClient.settings()==null||System.currentTimeMillis()<next)return;
		next=System.currentTimeMillis()+2000;
		WaypointStore store=WaypointStore.get();if(store==null)return;
		try {
			var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(6);
			List<Waypoint> selected=store.webWaypoints().stream().sorted(Comparator.comparing(w->w.id)).toList();out.writeByte(selected.size());
			for(Waypoint w:selected){out.writeLong(w.id.getMostSignificantBits());out.writeLong(w.id.getLeastSignificantBits());out.writeUTF(w.name);out.writeUTF(w.initials);out.writeUTF(w.icon==null?"":w.icon);out.writeUTF(w.dimension);out.writeInt(w.x);out.writeInt(w.y);out.writeInt(w.z);out.writeInt(w.color);}
			byte[] data=bytes.toByteArray();if(Arrays.equals(previous,data))return;
			ShareProtocol.sendToServer(data);previous=data;
		}catch(IOException|RuntimeException error){dev.diorama.Diorama.LOGGER.warn("Couldn't publish selected web waypoints",error);}
	}
}
