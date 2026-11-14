package dev.diorama.web;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Public bounded queue snapshots, with no disk work or extra chunk reads on the server thread. */
final class WebQueueStatus implements AutoCloseable {
  static final int LIMIT=8192;
  record Frame(int queuedTiles,int waitingChunks,boolean truncated,List<Long> tiles) {}
  private final Path folder;
  private final Supplier<Map<String,Frame>> frames;
  private final BooleanSupplier paused;
  private final Thread thread;
  private volatile boolean closed;
  private String previous;
  private long written;
  WebQueueStatus(Path folder,Supplier<Map<String,Frame>> frames,BooleanSupplier paused) {
    this.folder=folder;this.frames=frames;this.paused=paused;
    thread=new Thread(this::run,"Diorama queue status");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
  }
  private void run(){
    while(!closed)try{publish();Thread.sleep(2000);}
    catch(InterruptedException done){Thread.currentThread().interrupt();return;}
    catch(IOException|RuntimeException error){Diorama.LOGGER.warn("Couldn't publish queued web-map tiles",error);try{Thread.sleep(5000);}catch(InterruptedException done){return;}}
  }
  private void publish() throws IOException {
    StringBuilder body=new StringBuilder("\"paused\":").append(paused.getAsBoolean()).append(",\"dimensions\":{");boolean first=true;
    for(var entry:new TreeMap<>(frames.get()).entrySet()){
      if(!first)body.append(',');first=false;
      String id=entry.getKey().replace("\\","\\\\").replace("\"","\\\"");Frame frame=entry.getValue();
      body.append('"').append(id).append("\":{\"queuedTiles\":").append(frame.queuedTiles).append(",\"waitingChunks\":").append(frame.waitingChunks).append(",\"truncated\":").append(frame.truncated).append(",\"tiles\":[");
      boolean cell=true;int count=0;for(long key:frame.tiles){if(count++>=LIMIT)break;if(!cell)body.append(',');cell=false;body.append((int)(key>>32)).append(',').append((int)key);}body.append("]}");
    }
    body.append('}');long now=System.currentTimeMillis();String value=body.toString();
    if(value.equals(previous)&&now-written<8000)return;
    Files.createDirectories(folder);Path part=folder.resolve("queue-status.json.part");
    Files.writeString(part,"{\"version\":1,\"generatedAt\":"+now+","+value+"}");
    Files.move(part,folder.resolve("queue-status.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);previous=value;written=now;
  }
  @Override public void close(){closed=true;thread.interrupt();}
}
