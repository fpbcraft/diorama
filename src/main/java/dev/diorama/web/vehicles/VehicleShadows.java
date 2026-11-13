package dev.diorama.web.vehicles;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import java.nio.file.*;
import java.util.*;

/** Same silhouette and ground-light composition as CraftPainter, using published terrain only. */
final class VehicleShadows {
	private record Ground(long modified, TileSurface surface) {}
	private final Path webRoot;
	private final LinkedHashMap<Path,Ground> tiles = new LinkedHashMap<>(16,0.75f,true);
	VehicleShadows(Path webRoot){this.webRoot=webRoot;}
	CraftBaker.Image bake(String dimension,CraftBlocks blocks,double[] worldPose,double[] center,Looks looks) throws java.io.IOException {
		double[] pose=worldPose.clone();
		for(int i=0;i<12;i+=3){double x=pose[i],z=pose[i+2];pose[i]=Projection.facing().x(x,z);pose[i+2]=Projection.facing().z(x,z);}
		MapStyle style=MapStyle.get();
		// Start at the vehicle's height, then extend using the lowest known ground.
		int[] reach=CraftShadow.reach(blocks,pose,(int)Math.floor(center[1])-64,style);
		int u0=Math.floorDiv(reach[0],128),u1=Math.floorDiv(reach[2],128);
		double sin=Projection.sin(),cos=Projection.cos();
		int v0=(int)Math.floor((reach[1]*sin-blocks.box(pose)[4]*cos)/128),v1=(int)Math.floor((reach[3]*sin-(center[1]-192)*cos)/128);
		int columns=u1-u0+1,rows=v1-v0+1;
		if(columns<=0||rows<=0||columns*rows>8)return null;
		TileSurface[] surfaces=new TileSurface[columns*rows];float lowest=Float.POSITIVE_INFINITY;
		for(int v=v0;v<=v1;v++)for(int u=u0;u<=u1;u++){
			Path file=webRoot.resolve(dimension.replace(':','_').replace('/','_')).resolve("0").resolve(u+"_"+v+".ground");
			if(!Files.isRegularFile(file))continue;
			long modified=Files.getLastModifiedTime(file).toMillis();Ground kept=tiles.get(file);
			if(kept==null||kept.modified!=modified){kept=new Ground(modified,VehicleGround.read(file));tiles.put(file,kept);}
			surfaces[(v-v0)*columns+u-u0]=kept.surface;
			for(float y:kept.surface.y())if(Float.isFinite(y))lowest=Math.min(lowest,y);
		}
		while(tiles.size()>8)tiles.remove(tiles.keySet().iterator().next());
		if(!Float.isFinite(lowest))return null;
		reach=CraftShadow.reach(blocks,pose,(int)Math.floor(lowest),style);
		int pu=reach[0]*4,pv=(int)Math.floor((reach[1]*sin-blocks.box(pose)[4]*cos)*4);
		int width=(reach[2]+1)*4-pu,height=(int)Math.ceil(((reach[3]+1)*sin-lowest*cos)*4)-pv;
		if(width<=0||height<=0||width>2048||height>2048)return null;
		SurfaceArea ground=new SurfaceArea(u0,v0,columns,rows,surfaces);
		var silhouette=CraftShadow.silhouette(blocks,Arrays.copyOfRange(pose,3,12),looks,style);
		var shadow=CraftShadow.cast(silhouette,pose,pu,pv,width,height,ground);
		if(shadow.mips()==null)return null;
		float[] depth=new float[width*height];
		double middleDepth=-(sin*center[1]+cos*Projection.facing().z(center[0],center[2]));
		for(int y=0;y<height;y++)for(int x=0;x<width;x++)depth[y*width+x]=(float)(ground.near(pu+x,pv+y)-middleDepth);
		return new CraftBaker.Image(shadow.edge(),width,height,pu/4.0-Projection.u(center[0],center[2]),pv/4.0-Projection.v(center[0],center[1],center[2]),shadow.mips(),depth,Arrays.copyOfRange(pose,3,12),sin,new int[width*height]);
	}
}
