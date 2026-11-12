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
    Path history = Files.createDirectory(temp.resolve("public-history"));
    Files.writeString(history.resolve("manifest.json"), "{\"protocolVersion\":2}");
    Files.createSymbolicLink(history.resolve("escape.json"), temp.resolve("secret.json"));
    Path publicAssets = Files.createDirectory(temp.resolve("published-assets"));
    Files.writeString(publicAssets.resolve("world-status.json"), "{\"dayTime\":123}");
    WebExtensions.registerAssets("test-history", "/player-history/", WebServerSecurityTest.class,
      "/dev/diorama/web/", java.util.List.of("test-embedded.js"),
      java.util.List.of("/player-history/test-embedded.js"), java.util.List.of());
    WebExtensions.registerPublicFiles("test-history", "/player-history/data/", () -> history);
    Files.createDirectory(publicAssets.resolve("legacy-data-123"));
    Files.writeString(publicAssets.resolve("legacy-data-123/manifest.json"), "{\"private\":true}");
    WebExtensions.registerPublicFiles("test-history", "/player-history/", () -> publicAssets,
      java.util.List.of("world-status.json", "server-overlays.json", "integration.json", "skins/", "equipment/"));
    var chat = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 8);
    var receivedAuth = new java.util.concurrent.atomic.AtomicReference<String>();
    chat.createContext("/player-history-api/", exchange -> {
      receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] reply = "{\"messages\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, reply.length);
      try (var output = exchange.getResponseBody()) { output.write(reply); }
    });
    chat.start();
    WebExtensions.registerLoopbackApi("test-history", "/player-history-api/", chat.getAddress().getPort());
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
      // JAR assets and public history must work without a physical extensions.json
      // or a symlink to the recorder's private directory.
      var generated = get(client, origin + "/extensions.auto.json");
      check(generated.statusCode() == 200 && generated.body().contains("/player-history/test-embedded.js"), "virtual JAR manifest");
      check(get(client, origin + "/player-history/test-embedded.js").body().contains("fromJar"), "classpath JS");
      check(get(client, origin + "/player-history/data/manifest.json").body().contains("protocolVersion"), "public history read");
      check(get(client, origin + "/player-history/world-status.json").statusCode() == 200, "public publisher read");
      check(get(client, origin + "/player-history/legacy-data-123/manifest.json").statusCode() == 404, "legacy backup excluded");
      check(get(client, origin + "/player-history/data/escape.json").statusCode() == 404, "public file symlink rejected");
      check(get(client, origin + "/player-history/data/%2e%2e/secret.json").statusCode() == 404, "public traversal rejected");
      check(get(client, origin + "/player-history/data/missing.json").statusCode() == 404, "public missing file");
      check(get(client, origin + "/player-history/data/manifest.json").headers().firstValue("Cache-Control").orElse("").equals("no-cache"), "public data cache");
      check(get(client, origin + "/player-history/data/secret.toml").statusCode() == 404, "private format not served");
      var publicWrite = client.send(HttpRequest.newBuilder(URI.create(origin + "/player-history/data/manifest.json"))
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      check(publicWrite.statusCode() == 405, "public file write rejected");
      var publicHead = client.send(HttpRequest.newBuilder(URI.create(origin + "/player-history/data/manifest.json"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      check(publicHead.statusCode() == 200 && publicHead.body().isEmpty(), "public HEAD");

      // Only a fixed loopback chat endpoint is proxied, with bearer header intact.
      var messages = get(client, origin + "/player-history-api/messages");
      check(messages.statusCode() == 200 && messages.body().contains("messages"), "local chat feed proxy");
      var apiPost = client.send(HttpRequest.newBuilder(URI.create(origin + "/player-history-api/session"))
          .header("Authorization", "Bearer test-token").header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      check(apiPost.statusCode() == 200 && "Bearer test-token".equals(receivedAuth.get()), "local proxy auth forwarding");
      check(get(client, origin + "/player-history-api/private").statusCode() == 404, "chat action allowlist");
      var apiTooLarge = client.send(HttpRequest.newBuilder(URI.create(origin + "/player-history-api/send"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("x".repeat(5000))).build(), HttpResponse.BodyHandlers.ofString());
      check(apiTooLarge.statusCode() == 413, "chat payload limit");
      var htmlForm = client.send(HttpRequest.newBuilder(URI.create(origin + "/player-history-api/pair"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("pair=true")).build(), HttpResponse.BodyHandlers.ofString());
      check(htmlForm.statusCode() == 415, "cross-site form cannot be upgraded to JSON");

      WebExtensions.unregister("test-history");
      check(get(client, origin + "/extensions.auto.json").body().equals("{\"scripts\":[],\"styles\":[]}"), "unregister clears virtual manifest");
      check(get(client, origin + "/player-history/data/manifest.json").statusCode() == 404, "unregister revokes data mount");
      System.out.println("Production WebServer and JAR/public-data extension security checks passed");
    } finally {
      server.stop();
      chat.stop(0);
      try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
  }
  static HttpResponse<String> get(HttpClient client, String url) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
  static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
