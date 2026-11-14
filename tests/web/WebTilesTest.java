package dev.diorama.web;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
public final class WebTilesTest {
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("rail-pixels-");
  try{
   Path file=dir.resolve("0_0.jpg");int[] pixels=new int[512*512];
   for(int i=0;i<pixels.length;i++)pixels[i]=(i%512%2==0)?0xffd0d0d0:0xff485e76;
   WebTiles.write(file,pixels);WebTiles.writeLossless(file,pixels,true);
   var image=ImageIO.read(WebTiles.lossless(file).toFile());
   for(int i=0;i<pixels.length;i++){int p=pixels[i],expected=0xff000000|(p&255)<<16|(p&0xff00)|(p>>16&255);if(image.getRGB(i%512,i/512)!=expected)throw new AssertionError("Thin track pixel changed");}
   WebTiles.writeLossless(file,pixels,false);if(Files.exists(WebTiles.lossless(file)))throw new AssertionError("Removed rail tile kept stale PNG");
   System.out.println("Lossless thin railway pixel roundtrip and removal checks passed");
  }finally{try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
 }
}