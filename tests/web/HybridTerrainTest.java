package dev.diorama.web;
import java.nio.*;
import java.nio.file.*;
import dev.diorama.bake.TileSurface;

public final class HybridTerrainTest {
  public static void main(String[] args) throws Exception {
    DirtyChunks queue = new DirtyChunks(2, 30000);
    long negative = ((long)-17 << 32) | (-23 & 0xffffffffL);
    check(queue.mark(negative, 0), "first change");
    check(queue.mark(negative, 29000), "coalescing");
    check(queue.mark(99, 1000), "second change");
    check(!queue.mark(100, 1000), "bounded dirty queue");
    check(queue.poll(29999) == null, "capture must wait for coalescing window");
    check(queue.poll(30000) == negative, "continuous edits must not postpone first capture");
    check(queue.poll(30999) == null && queue.poll(31000) == 99, "FIFO deadlines");
    check(queue.poll(40000) == null, "drained");
    Path file = Files.createTempFile("diorama-depth-", ".depth");
    try {
      WebDepth.write(file, new TileSurface(2, 2, new float[] {-63.5f, Float.NaN, -1500, 99}));
      ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
      check(bytes.getInt() == 0x50454444 && bytes.getInt() == 2, "depth protocol magic/version");
      check(bytes.getInt() == 2 && bytes.getInt() == 2, "depth dimensions");
      byte[] encoded = Files.readAllBytes(file);
      try (var input = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(encoded, 16, encoded.length - 16))) {
        bytes = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
      }
      check(bytes.getFloat() == -63.5f && Float.isNaN(bytes.getFloat()), "signed camera depth and unknown samples");
      check(bytes.getFloat() == -1500 && bytes.getFloat() == 99 && !bytes.hasRemaining(), "depth payload");
    } finally { Files.deleteIfExists(file); }
    System.out.println("Hybrid terrain dirty-queue and depth-protocol checks passed");
  }
  private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
