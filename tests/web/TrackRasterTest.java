package dev.diorama.compat;
import java.util.*;
import java.nio.*;
import java.security.*;
import net.minecraft.world.phys.Vec3;
import dev.diorama.Projection;
import dev.diorama.bake.TileSurface;
public final class TrackRasterTest {
  public static void main(String[] args)throws Exception {
    if(TrackGraph.open("com.simibubi.create.Create")!=null)throw new AssertionError("Optional Create dependency not optional");
    // Geometry/depth goldens produced by the original in-game CurveRaster at a7245201.
    // The harness substitutes colour grading only; it exercises the real quad geometry and coverage.
    String[] goldens={"9b5af761743e26f1c0a9fa14dd77a0cfc9bad4cecbd862f6c3cac0a06c1f69cc","7c5b80bdb1bd788bf321111ac8a72285e46678d91f080223fc08f11031022cec","2a355d8859ac6f3434cad657393b35e509f79c60059627b4a7b2a7af3652dac4","f9190591998f2fcacca5d1e410ae0fc3a85563bcc99fedadc77618449e43f7a1"};
    for(int facing=0;facing<4;facing++){
      Projection.turn=facing;List<TrackGraph.Segment> rows=new ArrayList<>();
      for(int kind=0;kind<3;kind++){
        var palette=new TrackGraph.Palette(new TrackGraph.Paint(0x765e48,List.of()),new TrackGraph.Paint(0xd2d2d0,List.of()),kind==1?1.5f:kind==2?.72f:1,kind==2,false);
        Vec3 previous=new Vec3(-8,64,16);
        for(int i=1;i<=48;i++){
          Vec3 current=new Vec3(-8+i*.5,64+i*.04,16+kind*Math.sin(i*.07)*6);
          Vec3 up=new Vec3(.08,1,.04).normalize(),across=current.subtract(previous).cross(up).normalize();
          rows.add(new TrackGraph.Segment(previous,current,across,up,palette,kind==1,kind==0));previous=current;
        }
      }
      var indexed=TrackRaster.index(Map.of("minecraft:overworld",rows));var raster=new TrackRaster(null);
      var digest=MessageDigest.getInstance("SHA-256");int visible=0;
      var keys=indexed.keySet().stream().sorted(Comparator.comparingInt(TrackRaster.Key::tu).thenComparingInt(TrackRaster.Key::tv)).toList();
      for(var key:keys){
        var tile=new TrackRaster.Tile(key.tu(),key.tv());for(var row:indexed.get(key))raster.rasterize(tile,row);
        var bytes=ByteBuffer.allocate(tile.pixels.length*8);for(int i=0;i<tile.pixels.length;i++){bytes.putInt(tile.pixels[i]);bytes.putFloat(tile.depth[i]);if(tile.pixels[i]!=0)visible++;}digest.update(bytes.array());
        int[] ground=new int[512*512],before=ground.clone();float[] near=new float[ground.length];Arrays.fill(near,Float.NaN);
        TrackRaster.composite(tile,ground,new TileSurface(0,0,512,512,near));if(!Arrays.equals(ground,before))throw new AssertionError("Unknown ground revealed tracks");
        Arrays.fill(near,-10000);TrackRaster.composite(tile,ground,new TileSurface(0,0,512,512,near));if(!Arrays.equals(ground,before))throw new AssertionError("Tracks visible through foreground terrain");
        Arrays.fill(near,10000);TrackRaster.composite(tile,ground,new TileSurface(0,0,512,512,near));if(tile.hasPixels()&&Arrays.equals(ground,before))throw new AssertionError("Visible tracks missing");
      }
      if(visible<100)throw new AssertionError("Rail geometry missing");
      if(!goldens[facing].equals(HexFormat.of().formatHex(digest.digest())))throw new AssertionError("In-game rail geometry or depth changed for facing "+facing);
    }
    System.out.println("Shared straight/curved rail raster, gauges, girders, facing and terrain occlusion checks passed");
  }
}
