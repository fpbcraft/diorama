package dev.diorama.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.function.LongConsumer;

/** Bounded startup repair: keep JPEGs, recover depth masks, and queue only unavailable alpha. */
final class WebCoverageRepair {
	private record Tile(int zoom,int u,int v) {}
	private final Path folder,marker;
	private final boolean oldOverviews;
	private final ArrayDeque<Tile> work=new ArrayDeque<>();
	WebCoverageRepair(Path folder) {
		this.folder=folder;this.marker=folder.resolve("fog-coverage-v2.txt");this.oldOverviews=!Files.isRegularFile(marker);
	}
	void add(int zoom,int u,int v){if(zoom==0||oldOverviews)work.add(new Tile(zoom,u,v));}
	boolean pending(){return !work.isEmpty();}
	boolean step(int budget,LongConsumer rerender)throws IOException {
		boolean changed=false;
		for(int n=0;n<budget&&!work.isEmpty();n++){
			Tile tile=work.removeFirst();Path jpeg=file(tile.zoom,tile.u,tile.v);
			if(!Files.isRegularFile(jpeg))continue;
			try {
				if(tile.zoom==0){
					if(WebCoverage.valid(jpeg))continue;
					boolean recovered;
					try{recovered=WebCoverage.recoverDepth(jpeg);}catch(IOException error){recovered=false;}
					if(recovered)changed=true;else rerender.accept((long)tile.u<<32|tile.v&0xffffffffL);
				}else{
					Path[] children=new Path[4];for(int i=0;i<4;i++)children[i]=file(tile.zoom-1,tile.u*2+(i&1),tile.v*2+(i>>1));
					WebCoverage.shrink(jpeg,children);changed=true;
				}
			}catch(IOException error){work.addFirst(tile);throw error;}
		}
		if(work.isEmpty()&&oldOverviews){Files.createDirectories(folder);Files.writeString(marker,"2\n");}
		return changed;
	}
	private Path file(int zoom,int u,int v){return folder.resolve(Integer.toString(zoom)).resolve(u+"_"+v+".jpg");}
}
