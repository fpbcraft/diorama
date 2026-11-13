package dev.diorama.compat;

import dev.diorama.Projection;
import dev.diorama.bake.Looks;
import dev.diorama.bake.CurveStyle;
import java.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Shared rail/tie/girder geometry and four-sample tile raster, independent of client rendering. */
public final class TrackRaster {
    private static final int SIZE=Projection.TILE_PIXELS,PPU=Projection.PIXELS_PER_UNIT;
    private final Looks looks;
    private final Map<TrackGraph.Paint,Integer> colours=new HashMap<>();
    private int girderTop=0x686B70,girderSide=0x424850;
    public TrackRaster(Looks looks){this.looks=looks;resolveGirderColours(looks);}
    private int colour(TrackGraph.Paint paint){return colours.computeIfAbsent(paint,p->{
        if(looks!=null)for(Block block:p.blocks()){int id=looks.lookId(block.defaultBlockState());int rgb=looks.top[id]!=0?looks.top[id]:looks.topTinted[id];if(rgb!=0)return rgb&0xffffff;}
        return p.rgb()&0xffffff;
    });}
    public record Key(String dimension,int tu,int tv) {}
    public static Map<Key,List<TrackGraph.Segment>> index(Map<String,List<TrackGraph.Segment>> paths) {
        Map<Key,List<TrackGraph.Segment>> built=new HashMap<>();
        for(var entry:paths.entrySet())for(var segment:entry.getValue()){
            Vec3 a=segment.from(),b=segment.to();
            int u0=(int)Math.floor((Math.min(Projection.u(a.x,a.z),Projection.u(b.x,b.z))-4)/Projection.TILE_UNITS);
            int u1=(int)Math.floor((Math.max(Projection.u(a.x,a.z),Projection.u(b.x,b.z))+4)/Projection.TILE_UNITS);
            int v0=(int)Math.floor((Math.min(Projection.v(a.x,a.y,a.z),Projection.v(b.x,b.y,b.z))-4)/Projection.TILE_UNITS);
            int v1=(int)Math.floor((Math.max(Projection.v(a.x,a.y,a.z),Projection.v(b.x,b.y,b.z))+5)/Projection.TILE_UNITS);
            if((long)(u1-u0+1)*(v1-v0+1)>16)continue;
            for(int u=u0;u<=u1;u++)for(int v=v0;v<=v1;v++)built.computeIfAbsent(new Key(entry.getKey(),u,v),k->new ArrayList<>()).add(segment);
        }
        built.replaceAll((key,rows)->List.copyOf(rows));return Map.copyOf(built);
    }
    /** Same camera-near rejection used by the client overlay. Unknown ground never reveals rails. */
    public static void composite(Tile tile,int[] ground,dev.diorama.bake.TileSurface surface){
        for(int y=tile.y0;y<tile.y1;y++)for(int x=tile.x0;x<tile.x1;x++){
            int at=y*SIZE+x,source=tile.pixels[at];if(source==0)continue;
            int sx=x-surface.x0(),sy=y-surface.y0();if(sx<0||sy<0||sx>=surface.width()||sy>=surface.height())continue;
            float near=surface.near()[sy*surface.width()+sx],rail=tile.depth[at];
            if(Float.isFinite(near)&&Float.isFinite(rail)&&near>=rail-.3f)ground[at]=over(source,source>>>24,ground[at]);
        }
    }
    public static class Tile {
        public final int u,v;
        public final int[] pixels=new int[SIZE*SIZE];
        public final float[] depth=new float[SIZE*SIZE];
        public int x0=SIZE,y0=SIZE,x1,y1;
        public Tile(int u,int v){this.u=u;this.v=v;Arrays.fill(depth,Float.POSITIVE_INFINITY);}
        void touched(int x,int y){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x+1);y1=Math.max(y1,y+1);}
        public boolean hasPixels(){return x0<x1&&y0<y1;}
    }
    private void resolveGirderColours(Looks looks) {
        girderTop = 0x686B70;
        girderSide = 0x424850;
        if (looks == null) return;
        try {
            Object holder = Class.forName("com.simibubi.create.AllBlocks").getField("METAL_GIRDER").get(null);
            Object block = holder.getClass().getMethod("get").invoke(holder);
            if (block instanceof Block girder) {
                int id = looks.lookId(girder.defaultBlockState());
                int top = looks.top[id];
                int side = looks.side[id];
                if (top != 0) girderTop = top;
                if (side != 0) girderSide = side;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The source mod provides its own girder registry on some versions.
        }
    }

    public void rasterize(Tile tile, TrackGraph.Segment segment) {
        TrackGraph.Palette p = segment.palette();
        double gauge = p.gauge();
        Vec3 from = segment.from();
        Vec3 to = segment.to();
        Vec3 across = segment.across();
        Vec3 up = segment.up();

        // Real Create hasGirder beams sit under the rail centre. No beams for curves
        // without that flag, including Steam 'n' Rails curves that don't use supports.
        if (segment.girder()) {
            Vec3 offset = up.scale(-.55);
            Vec3 a = from.add(offset);
            Vec3 b = to.add(offset);
            paint(tile, strip(a, b, across, 0, gauge * 2.15),
                CurveStyle.shade(girderTop, (from.y + to.y) / 2 - .55, true));
            Vec3 lower = up.scale(-.65);
            for (int side = -1; side <= 1; side += 2) {
                Vec3 sideOffset = across.scale(side * gauge * 1.075);
                Vec3 a0 = from.add(offset).add(sideOffset);
                Vec3 b0 = to.add(offset).add(sideOffset);
                paint(tile, new Vec3[]{a0, b0, b0.add(lower), a0.add(lower)},
                    CurveStyle.shade(girderSide, from.y - 1, false));
            }
        }

        // Create draws one transverse tie at each Bezier sample. In Diorama these are
        // map pixels with the same sunlight/grade as straight track blocks, not sprites.
        Vec3 direction = to.subtract(from).normalize();
        if (direction.lengthSqr() < 1e-7) return;
        double halfTie = Math.min(.20, to.distanceTo(from) * .32);
        Vec3 halfAlong = direction.scale(halfTie);
        Vec3 halfAcross = across.scale(gauge * 1.28);
        Vec3 center = to.add(up.scale(-.085));
        paint(tile, new Vec3[] {
            center.subtract(halfAlong).add(halfAcross),
            center.subtract(halfAlong).subtract(halfAcross),
            center.add(halfAlong).subtract(halfAcross),
            center.add(halfAlong).add(halfAcross)
        }, CurveStyle.shade(colour(p.sleeper()), center.y, true));

        // Two parallel rails (one for monorail), continuous between Bezier samples.
        // Narrow/standard/wide gauge remains defined by each TrackMaterial.
        for (int side = -1; side <= 1; side += 2) {
            if (p.monorail() && side == 1) break;
            double position = p.monorail() ? 0 : side * gauge * .965;
            Vec3 lift = up.scale(.065);
            paint(tile, strip(from.add(lift), to.add(lift), across, position, gauge * .20),
                CurveStyle.shade(colour(p.metal()), center.y, true));
        }
    }

    private static Vec3[] strip(Vec3 a, Vec3 b, Vec3 cross, double offset, double width) {
        Vec3 far = cross.scale(offset + width / 2);
        Vec3 near = cross.scale(offset - width / 2);
        return new Vec3[] {a.add(far), a.add(near), b.add(near), b.add(far)};
    }

    /** Raster a convex quadrilateral with Diorama's four subpixel samples per map pixel. */
    private static void paint(Tile tile, Vec3[] quad, int abgr) {
        double[] px = new double[4], py = new double[4], depth = new double[4];
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            Vec3 w = quad[i];
            px[i] = Projection.u(w.x, w.z) * PPU - tile.u * SIZE;
            py[i] = Projection.v(w.x, w.y, w.z) * PPU - tile.v * SIZE;
            depth[i] = -(Projection.sin() * w.y + Projection.cos() * Projection.facing().z(w.x, w.z));
            minX = Math.min(minX, px[i]);
            minY = Math.min(minY, py[i]);
            maxX = Math.max(maxX, px[i]);
            maxY = Math.max(maxY, py[i]);
        }
        int x0 = Math.max(0, (int) Math.floor(minX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int x1 = Math.min(SIZE, (int) Math.ceil(maxX));
        int y1 = Math.min(SIZE, (int) Math.ceil(maxY));
        if (x0 >= x1 || y0 >= y1) return;

        float approxDepth = (float) ((depth[0] + depth[1] + depth[2] + depth[3]) * .25);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int coverage = 0;
                if (inside(px, py, x + .375, y + .125)) coverage++;
                if (inside(px, py, x + .875, y + .375)) coverage++;
                if (inside(px, py, x + .125, y + .625)) coverage++;
                if (inside(px, py, x + .625, y + .875)) coverage++;
                if (coverage == 0) continue;
                int index = y * SIZE + x;
                // The nearer face owns the map pixel. A later rail face can still
                // cover its sleeper where they are almost coplanar.
                if (approxDepth > tile.depth[index] + .14f) continue;
                int alpha = coverage * 255 / 4;
                tile.pixels[index] = over(abgr, alpha, tile.pixels[index]);
                tile.depth[index] = Math.min(tile.depth[index], approxDepth);
                tile.touched(x, y);
            }
        }
    }

    private static boolean inside(double[] x, double[] y, double px, double py) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double cross = (x[j] - x[i]) * (py - y[i]) - (y[j] - y[i]) * (px - x[i]);
            if (Math.abs(cross) <= 1e-8) continue;
            int next = cross > 0 ? 1 : -1;
            if (sign != 0 && sign != next) return false;
            sign = next;
        }
        return sign != 0;
    }

    /** Source and destination are ABGR, matching Diorama's tile pixels. */
    private static int over(int source, int coverage, int destination) {
        int destAlpha = destination >>> 24;
        int outAlpha = coverage + destAlpha * (255 - coverage) / 255;
        if (outAlpha == 0) return 0;
        int r = ((source & 255) * coverage
            + (destination & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        int g = (((source >>> 8) & 255) * coverage
            + ((destination >>> 8) & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        int b = (((source >>> 16) & 255) * coverage
            + ((destination >>> 16) & 255) * destAlpha * (255 - coverage) / 255) / outAlpha;
        return outAlpha << 24 | b << 16 | g << 8 | r;
    }
}
