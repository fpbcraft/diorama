package dev.diorama.web;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

/** Runs the production handler in isolation, with no Minecraft server or world. */
public final class WebServerSecurityTest {
  public static void main(String[] args) throws Exception {
    Path temp = Files.createTempDirectory("diorama-http-");
    Path root = Files.createDirectory(temp.resolve("public"));
    Files.writeString(root.resolve("index.html"), "map");
    Files.writeString(root.resolve("overlay-api.js"), "export const version = 1;");
    Files.writeString(root.resolve("map.json"), "{}");
    Files.writeString(root.resolve("presence.json"), "{}");
    Files.writeString(root.resolve("test.css"), "body{}");
    Files.write(root.resolve("head.png"), new byte[] {1});
    Files.write(root.resolve("tile.jpg"), new byte[] {1});
    Files.write(root.resolve("tile.depth"), new byte[] {1});
    Files.createDirectories(root.resolve("modules/v1"));
    Files.writeString(root.resolve("modules/v1/live.js"), "export {};");
    Files.writeString(temp.resolve("secret.json"), "private");
    Files.createSymbolicLink(root.resolve("escape.json"), temp.resolve("secret.json"));
    WebServer server = WebServer.start(root, "127.0.0.1", 0);
    if (server == null) throw new AssertionError("server did not start");
    var client = HttpClient.newHttpClient();
    String origin = "http://127.0.0.1:" + server.port();
    try {
      for (String path : new String[] {"/../secret.json", "/%2e%2e/secret.json", "/escape.json", "/config.toml"}) {
        check(get(client, origin + path).statusCode() == 404, "private/traversal route " + path);
      }
      check(get(client, origin + "/overlay-api.js").headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"), "JS MIME");
      check(get(client, origin + "/test.css").statusCode() == 200, "CSS");
      check(get(client, origin + "/head.png").statusCode() == 200, "PNG");
      var depth = get(client, origin + "/tile.depth?v=1");
      check(depth.statusCode() == 200 && depth.headers().firstValue("Content-Type").orElse("").startsWith("application/octet-stream"), "depth MIME");
      check(depth.headers().firstValue("Cache-Control").orElse("").contains("immutable"), "versioned depth cache");
      check(get(client, origin + "/modules/v1/live.js").headers().firstValue("Cache-Control").orElse("").contains("immutable"), "versioned modules cache");
      var tile = get(client, origin + "/tile.jpg?v=123");
      check(tile.headers().firstValue("Cache-Control").orElse("").contains("immutable"), "versioned tiles cache");
      String modified = tile.headers().firstValue("Last-Modified").orElseThrow();
      var conditional = client.send(HttpRequest.newBuilder(URI.create(origin + "/tile.jpg")).header("If-Modified-Since", modified).build(), HttpResponse.BodyHandlers.ofString());
      check(conditional.statusCode() == 304, "tile conditional request");
      var live = client.send(HttpRequest.newBuilder(URI.create(origin + "/presence.json")).header("If-Modified-Since", modified).build(), HttpResponse.BodyHandlers.ofString());
      check(live.statusCode() == 200 && live.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "live freshness");
      var head = client.send(HttpRequest.newBuilder(URI.create(origin + "/overlay-api.js")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      check(head.statusCode() == 200 && head.body().isEmpty(), "HEAD");
      var post = client.send(HttpRequest.newBuilder(URI.create(origin + "/map.json")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      check(post.statusCode() == 405, "read-only server");
      System.out.println("Production WebServer security, MIME and cache checks passed");
    } finally {
      server.stop();
      try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
  }
  static HttpResponse<String> get(HttpClient client, String url) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
  static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
