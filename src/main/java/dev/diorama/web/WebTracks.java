package dev.diorama.web;

import dev.diorama.compat.TrackGraph;
import dev.diorama.compat.TrackRaster;
import dev.diorama.bake.Looks;
import dev.diorama.bake.TileSurface;
import java.util.*;

/** Server graph capture, then ordinary tile-worker rasterization; no chunk loads or browser geometry. */
final class WebTracks {
    private final TrackGraph graph=TrackGraph.open("com.simibubi.create.Create");
    private volatile Map<String,List<TrackGraph.Segment>> captured=Map.of();
    private Map<String,List<TrackGraph.Segment>> applied=Map.of();
    private Map<TrackRaster.Key,List<TrackGraph.Segment>> indexed=Map.of();
    private int ticks;
    private TrackRaster raster;
    /** Owning server thread, once per server tick. */
    boolean tick(){
        if(graph==null||--ticks>0)return false;ticks=600;
        try{var next=graph.snapshot();if(captured.equals(next))return false;captured=next;return true;}
        catch(RuntimeException|LinkageError error){dev.diorama.Diorama.LOGGER.warn("Couldn't capture Create railway graph; keeping previous web tracks",error);return false;}
    }
    boolean hasUpdates(){return captured!=applied;}
    /** Web worker: only tiles whose geometry changed need a redraw. */
    Set<TrackRaster.Key> update(Looks looks){
        var next=captured;if(next==applied)return Set.of();
        var built=TrackRaster.index(next);var changed=new HashSet<TrackRaster.Key>();changed.addAll(indexed.keySet());changed.addAll(built.keySet());
        changed.removeIf(key->Objects.equals(indexed.get(key),built.get(key)));
        indexed=built;applied=next;raster=new TrackRaster(looks);return changed;
    }
    boolean contains(String dimension,int u,int v){return indexed.containsKey(new TrackRaster.Key(dimension,u,v));}
    void composite(String dimension,int u,int v,int[] pixels,TileSurface surface){
        var rows=indexed.get(new TrackRaster.Key(dimension,u,v));if(rows==null||raster==null)return;
        var tile=new TrackRaster.Tile(u,v);for(var row:rows)raster.rasterize(tile,row);TrackRaster.composite(tile,pixels,surface);
    }
}
