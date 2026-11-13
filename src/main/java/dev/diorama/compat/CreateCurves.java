package dev.diorama.compat;

import dev.diorama.Settings;
import dev.diorama.api.MapAnnotations;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Client adapter for the shared Create railway graph and pixel renderer. */
public final class CreateCurves implements MapAnnotations.Source {
    private final TrackGraph graph;
    private final CurveRaster pixels=new CurveRaster();
    private Object world;
    private BlockLooks looks;
    private int ticks;
    private CreateCurves(TrackGraph graph){this.graph=graph;}
    public static void start(){
        TrackGraph graph=TrackGraph.open("com.simibubi.create.CreateClient");
        if(graph!=null)MapAnnotations.register(new CreateCurves(graph));
    }
    @Override public void tick(Minecraft minecraft){
        if(minecraft.level==null){world=null;pixels.clear();return;}
        if(world!=minecraft.level||looks!=BlockLooks.get()){world=minecraft.level;looks=BlockLooks.get();pixels.clear();ticks=0;}
        if(--ticks>0)return;ticks=80;pixels.update(graph.snapshot());
    }
    @Override public void draw(GuiGraphicsExtractor graphics,MapViewport.View view,String dimension,boolean fullscreen){
        if(Settings.get().showCreateCurves&&view.layer()==TileKey.SURFACE)pixels.draw(graphics,view,dimension);
    }
}
