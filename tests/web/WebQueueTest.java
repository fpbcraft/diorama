package dev.diorama.web;
import java.nio.file.*;
import java.util.*;
public final class WebQueueTest {
  public static void main(String[] args)throws Exception {
    Path folder=Files.createTempDirectory("web-queue-");
    try {
      WebTileQueue queue=new WebTileQueue(folder);
      long negative=WebTileQueue.key(-3,-7),second=WebTileQueue.key(2,4);
      queue.add(negative);queue.add(second);queue.addFirst(List.of(second));
      if(queue.first()!=second||queue.size()!=2||!queue.save())throw new AssertionError("Queue priority or checkpoint failed");
      WebTileQueue restored=new WebTileQueue(folder);
      if(!restored.first(1).equals(List.of(second))||!restored.snapshot().contains(negative))throw new AssertionError("Negative tile checkpoint not restored");
      List<Long> cells=new ArrayList<>();cells.add(negative);for(int i=1;i<9000;i++)cells.add(WebTileQueue.key(i,0));
      try(var status=new WebQueueStatus(folder,()->Map.of("minecraft:overworld",new WebQueueStatus.Frame(9000,4,true,cells)),()->true)){
        Path file=folder.resolve("queue-status.json");String json="";
        for(int i=0;i<300&&!Files.exists(file);i++)Thread.sleep(10);
        json=Files.readString(file);
        if(!json.contains("\"paused\":true")||!json.contains("\"tiles\":[-3,-7,1,0")||!json.contains("8191,0]")||json.contains("8192,0"))throw new AssertionError("Status bounds or coordinates wrong");
        if(Files.exists(folder.resolve("queue-status.json.part")))throw new AssertionError("Partial public write left behind");
      }
      System.out.println("Queue priority, checkpoint, negative coordinates and bounded atomic status checks passed");
    }finally{try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
  }
}
