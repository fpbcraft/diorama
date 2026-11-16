package dev.diorama.render;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Exercises the actual flat painter/projection without starting a Minecraft client. */
public final class ClaimGridTest {
  private static final int SIZE = Projection.TILE_PIXELS;
  private static final Claims.Claim OWN = new Claims.Claim(0xD5A6E5, false, "Harbour");
  private static final Claims.Claim LOADED = new Claims.Claim(0xD5A6E5, true, "Harbour");

  public static void main(String[] args) throws Exception {
    var pitch = Projection.class.getDeclaredMethod("setPitch", double.class);
    pitch.setAccessible(true);
    for (double angle : new double[] {25, 40, 70}) {
      pitch.invoke(null, angle);
      for (Facing facing : Facing.values()) {
        Projection.setFacing(facing);
        rectangleAndPicking(-2, 3);
        rectangleAndPicking(0, -1);
        seamlessOverviews();
      }
    }
    pitch.invoke(null, 40);
    Projection.setFacing(Facing.NORTH);
    irregularAndForceLoaded();
    check(ClaimPaint.paint(new TileKey(TileKey.SURFACE, 0, 0), new Claims.Snapshot((x,z) -> null)) == null, "Empty claims must not allocate a texture");
    check(ClaimProjection.height(TileKey.SURFACE) == 64 && ClaimProjection.height(-32) == -32, "Stable surface plane and cave-slice picking");
    if (args.length > 0) preview(Path.of(args[0]));
    System.out.println("Flat claim rectangles, 4 facings, 3 pitches, negative coordinates, picking, seams, overviews, irregular gaps and force-load borders passed");
  }

  private static void rectangleAndPicking(int cx, int cz) {
    double wx = cx * 16 + 8, wz = cz * 16 + 8;
    double u = Projection.u(wx, wz), v = Projection.v(wx, 64, wz);
    var hit = ClaimProjection.spot(u, v, TileKey.SURFACE);
    check(hit.getX() >> 4 == cx && hit.getZ() >> 4 == cz, "Claim-center picking must match rotation");
    TileKey key = new TileKey(TileKey.SURFACE, (int)Math.floor(u / 128), (int)Math.floor(v / 128));
    Claims.Snapshot claims = new Claims.Snapshot((x,z) -> x == cx && z == cz ? OWN : null);
    int[] pixels = ClaimPaint.paint(key, claims);
    check(pixels != null, "A visible claim must render without any saved terrain or picking data");
    int left = SIZE, right = -1, top = SIZE, bottom = -1, count = 0;
    double step = key.units() / SIZE;
    for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
      var at = ClaimProjection.spot(key.minU() + (x + .5) * step, key.minV() + (y + .5) * step, TileKey.SURFACE);
      boolean claimed = pixels[y * SIZE + x] != 0;
      check(claimed == (at.getX() >> 4 == cx && at.getZ() >> 4 == cz), "Every rendered pixel must pick the displayed chunk");
      if (claimed) {
        left = Math.min(left,x); right = Math.max(right,x); top = Math.min(top,y); bottom = Math.max(bottom,y); count++;
      }
    }
    check(count == (right - left + 1) * (bottom - top + 1), "Each chunk must have a rectangular silhouette");
    check(right - left < 65 && bottom - top < 65, "Rectangle dimensions must follow the projected chunk grid");
  }

  private static void seamlessOverviews() {
    Claims.Snapshot solid = new Claims.Snapshot((x,z) -> OWN);
    for (int lod : new int[] {0, 1, 4, 11}) {
      for (int tu : new int[] {-1, 0, 1}) {
        int[] pixels = ClaimPaint.paint(new TileKey(TileKey.SURFACE, tu, -1, lod), solid);
        check(pixels != null, "All zoom levels must show known claims");
        for (int color : pixels) check(color >>> 24 == ClaimPaint.FILL_ALPHA, "Continuous claims must not acquire tile seams");
      }
    }
  }

  private static int pixel(Claims.Snapshot claims, double x, double z) {
    double u = Projection.u(x,z), v = Projection.v(x,64,z);
    TileKey key = new TileKey(TileKey.SURFACE, (int)Math.floor(u / 128), (int)Math.floor(v / 128));
    int[] pixels = ClaimPaint.paint(key,claims);
    int px = (int)Math.floor((u - key.minU()) * 4), py = (int)Math.floor((v - key.minV()) * 4);
    return pixels == null ? 0 : pixels[py * SIZE + px];
  }

  private static void irregularAndForceLoaded() {
    Claims.Snapshot shape = new Claims.Snapshot((x,z) -> x == 0 && (z == 0 || z == 1) || x == 1 && z == 0 ? OWN : null);
    check(pixel(shape,24,24) == 0, "An L-shaped claim must not fill its unclaimed bounding-box corner");
    check(pixel(shape,15.875,8) >>> 24 == ClaimPaint.FILL_ALPHA, "Adjacent chunks of the same claim share an uninterrupted fill");
    Claims.Snapshot forced = new Claims.Snapshot((x,z) -> z == 0 && x == 0 ? OWN : z == 0 && x == 1 ? LOADED : null);
    check(pixel(forced,24,8) >>> 24 == ClaimPaint.FORCE_LOADED_ALPHA, "Forceload fill must remain distinct");
    check(pixel(forced,15.875,8) >>> 24 == ClaimPaint.EDGE_ALPHA, "Forceload boundary must remain visible");
  }

  private static void preview(Path output) throws Exception {
    TileKey key = new TileKey(TileKey.SURFACE,0,0);
    Claims.Snapshot claims = new Claims.Snapshot((x,z) -> x >= 1 && x <= 5 && z >= 5 && z <= 10 && !(x >= 4 && z >= 9) ? (x == 3 && z == 7 ? LOADED : OWN) : null);
    int[] pixels = ClaimPaint.paint(key,claims);
    BufferedImage image = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
      int color = pixels[y * SIZE + x];
      int background = ((x / 32 + y / 32) & 1) == 0 ? 0xFF193C40 : 0xFF214A4C;
      int alpha = color >>> 24;
      int rgb = 0xFF000000;
      for (int shift : new int[] {0,8,16}) {
        int foreground = color >> (16 - shift) & 255;
        int back = background >> shift & 255;
        rgb |= ((foreground * alpha + back * (255 - alpha)) / 255) << shift;
      }
      image.setRGB(x,y,rgb);
    }
    Files.createDirectories(output.toAbsolutePath().getParent());
    ImageIO.write(image,"PNG",output.toFile());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
