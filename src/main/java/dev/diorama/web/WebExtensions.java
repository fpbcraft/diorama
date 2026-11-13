package dev.diorama.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Optional server-side web extensions. Mods register their own JAR resources, explicitly
 * public files, and (optionally) a fixed local API. No external symlink or directory
 * mount is added to Diorama's existing web-map root.
 *
 * Registration may occur before or after the web map starts. The generated manifest
 * is virtual: no extensions.json file needs to be edited or overwritten.
 */
public final class WebExtensions {
	private WebExtensions() {}

	private record Assets(String id, String urlPrefix, Class<?> owner, String resourcePrefix,
		Set<String> files, List<String> scripts, List<String> styles) {}
	private record PublicFiles(String id, String urlPrefix, Supplier<Path> root, List<String> allowed) {}
	private record LocalApi(String id, String urlPrefix, int port) {}

	private static final ConcurrentHashMap<String, Assets> ASSETS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, PublicFiles> PUBLIC_FILES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, LocalApi> LOCAL_APIS = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
	private static final long MAX_STATIC_BYTES = 16L * 1024 * 1024;
	private static final long MAX_PUBLIC_BYTES = 128L * 1024 * 1024;

	/**
	 * Registers JAR-packaged resources without copying them to the web-map directory.
	 *
	 * @param files exact relative filenames allowed from the JAR, typically an asset build manifest
	 * @param scripts/styles publicly served absolute URLs to include in the auto-generated manifest
	 */
	public static void registerAssets(String id, String urlPrefix, Class<?> owner,
			String resourcePrefix, List<String> files, List<String> scripts, List<String> styles) {
		checkId(id);
		checkPrefix(urlPrefix);
		if (owner == null) throw new IllegalArgumentException("Resource owner is required");
		if (!resourcePrefix.startsWith("/") || !resourcePrefix.endsWith("/")) {
			throw new IllegalArgumentException("Classpath resource prefix must begin and end with /");
		}
		var names = new LinkedHashSet<String>();
		for (String file : files) {
			checkRelative(file);
			names.add(file);
		}
		for (String url : scripts) checkRegisteredUrl(urlPrefix, names, url);
		for (String url : styles) checkRegisteredUrl(urlPrefix, names, url);
		ASSETS.put(id, new Assets(id, urlPrefix, owner, resourcePrefix,
			Set.copyOf(names), List.copyOf(scripts), List.copyOf(styles)));
	}

	/** A mounted directory is exclusively a caller-specified public export, never a parent folder. */
	/** An empty allowlist serves the entire explicitly public root; nonempty values restrict it. */
	public static void registerPublicFiles(String id, String urlPrefix, Supplier<Path> root) {
		registerPublicFiles(id, urlPrefix, root, List.of());
	}

	/** Entries ending in / allow a public subtree; other entries allow one exact file. */
	public static void registerPublicFiles(String id, String urlPrefix, Supplier<Path> root, List<String> allowed) {
		checkId(id);
		checkPrefix(urlPrefix);
		if (root == null) throw new IllegalArgumentException("Public root supplier is required");
		for (String item : allowed) {
			if (item.endsWith("/")) checkRelative(item.substring(0, item.length() - 1));
			else checkRelative(item);
		}
		PUBLIC_FILES.put(id + "|" + urlPrefix, new PublicFiles(id, urlPrefix, root, List.copyOf(allowed)));
	}

	/**
	 * Optional same-origin proxy to a fixed loopback HTTP service. No arbitrary host or
	 * URL can be passed by the caller. Only the recorder chat API verbs are accepted.
	 */
	public static void registerLoopbackApi(String id, String urlPrefix, int port) {
		checkId(id);
		checkPrefix(urlPrefix);
		if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid API port");
		LOCAL_APIS.put(id, new LocalApi(id, urlPrefix, port));
	}

	public static void unregister(String id) {
		ASSETS.remove(id);
		PUBLIC_FILES.entrySet().removeIf(e -> e.getValue().id.equals(id));
		LOCAL_APIS.remove(id);
	}

	private static void checkId(String id) {
		if (id == null || !id.matches("[a-z][a-z0-9_-]{0,63}")) {
			throw new IllegalArgumentException("Invalid extension id");
		}
	}

	private static void checkPrefix(String prefix) {
		if (prefix == null || !prefix.startsWith("/") || !prefix.endsWith("/") || prefix.equals("/")
				|| prefix.contains("..") || !prefix.matches("/[A-Za-z0-9_./-]+/")) {
			throw new IllegalArgumentException("Invalid web extension URL prefix");
		}
	}

	private static void checkRelative(String name) {
		if (name == null || name.isEmpty() || name.length() > 512
				|| name.startsWith("/") || name.contains("\\") || name.contains("//")
				|| !name.matches("[A-Za-z0-9_+.-]+(?:/[A-Za-z0-9_+.-]+)*")) {
			throw new IllegalArgumentException("Invalid web extension asset path: " + name);
		}
		for (String part : name.split("/")) {
			if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Traversal");
		}
	}

	private static void checkRegisteredUrl(String prefix, Set<String> files, String url) {
		if (url == null || !url.startsWith(prefix) || !files.contains(url.substring(prefix.length()))) {
			throw new IllegalArgumentException("Extension entrypoint must be a registered JAR asset");
		}
	}

	/** Called by the dedicated map HTTP server before its original contained-root handler. */
	static boolean serve(HttpExchange exchange, String path) throws IOException {
		if (path.equals("/extensions.auto.json")) {
			if (!methodAllowed(exchange)) return true;
			byte[] body = generatedManifest().getBytes(StandardCharsets.UTF_8);
			send(exchange, 200, "application/json; charset=utf-8", "no-store", body);
			return true;
		}
		for (Assets asset : ASSETS.values()) {
			if (!path.startsWith(asset.urlPrefix)) continue;
			String file = path.substring(asset.urlPrefix.length());
			if (!asset.files.contains(file)) continue;
			if (!methodAllowed(exchange)) return true;
			String mime = mime(file);
			if (mime == null) { send(exchange, 404, "text/plain", "no-store", null); return true; }
			try (InputStream in = asset.owner.getResourceAsStream(asset.resourcePrefix + file)) {
				if (in == null) { send(exchange, 404, "text/plain", "no-store", null); return true; }
				byte[] bytes = in.readNBytes((int) MAX_STATIC_BYTES + 1);
				if (bytes.length > MAX_STATIC_BYTES) {
					send(exchange, 413, "text/plain", "no-store", null);
				} else {
					String cache = file.startsWith("modules/") ? "public, max-age=31536000, immutable" : "no-cache";
					send(exchange, 200, mime, cache, bytes);
				}
			}
			return true;
		}
		List<PublicFiles> fileMounts = new ArrayList<>(PUBLIC_FILES.values());
		fileMounts.sort(Comparator.comparingInt((PublicFiles f) -> f.urlPrefix.length()).reversed());
		for (PublicFiles mount : fileMounts) {
			if (path.startsWith(mount.urlPrefix)) {
				servePublicFile(exchange, mount, path.substring(mount.urlPrefix.length()));
				return true;
			}
		}
		for (LocalApi api : LOCAL_APIS.values()) {
			if (path.startsWith(api.urlPrefix)) {
				serveLocalApi(exchange, api, path.substring(api.urlPrefix.length()));
				return true;
			}
		}
		return false;
	}

	private static boolean methodAllowed(HttpExchange exchange) throws IOException {
		if (exchange.getRequestMethod().equals("GET") || exchange.getRequestMethod().equals("HEAD")) return true;
		send(exchange, 405, "text/plain", "no-store", null);
		return false;
	}

	private static void servePublicFile(HttpExchange exchange, PublicFiles mount, String relative) throws IOException {
		if (!methodAllowed(exchange)) return;
		try {
			checkRelative(relative);
		} catch (IllegalArgumentException e) {
			send(exchange, 404, "text/plain", "no-store", null);
			return;
		}
		boolean listed = mount.allowed.isEmpty() || mount.allowed.stream()
			.anyMatch(rule -> rule.endsWith("/") ? relative.startsWith(rule) : relative.equals(rule));
		if (!listed) {
			send(exchange, 404, "text/plain", "no-store", null);
			return;
		}
		String mime = mime(relative);
		boolean geometry = relative.startsWith("objects/geometry/")
			&& (relative.endsWith(".bm3d") || relative.endsWith(".dspr"));
		if (mime == null || !(geometry || relative.endsWith(".json") || relative.endsWith(".png")
				|| relative.endsWith(".jpg") || relative.endsWith(".webp"))) {
			send(exchange, 404, "text/plain", "no-store", null);
			return;
		}
		Path configured = mount.root.get();
		if (configured == null || !Files.isDirectory(configured)) {
			send(exchange, 404, "text/plain", "no-store", null);
			return;
		}
		Path root = configured.toRealPath();
		Path current = root;
		for (String part : relative.split("/")) {
			current = current.resolve(part);
			if (Files.isSymbolicLink(current)) {
				send(exchange, 404, "text/plain", "no-store", null);
				return;
			}
		}
		if (!current.normalize().startsWith(root) || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
			send(exchange, 404, "text/plain", "no-store", null);
			return;
		}
		long size = Files.size(current);
		if (size > MAX_PUBLIC_BYTES) {
			send(exchange, 413, "text/plain", "no-store", null);
			return;
		}
		String cache = relative.equals("live.json") || relative.equals("presence.json")
			|| relative.equals("server-overlays.json") || relative.equals("world-status.json")
			? "no-store" : "no-cache";
		exchange.getResponseHeaders().set("Content-Type", mime);
		exchange.getResponseHeaders().set("Cache-Control", cache);
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		if (exchange.getRequestMethod().equals("HEAD") || size == 0) {
			exchange.sendResponseHeaders(200, -1);
			return;
		}
		exchange.sendResponseHeaders(200, size);
		try (var input = Files.newInputStream(current); OutputStream output = exchange.getResponseBody()) {
			input.transferTo(output);
		}
	}

	private static void serveLocalApi(HttpExchange exchange, LocalApi api, String action) throws IOException {
		String method = exchange.getRequestMethod();
		boolean valid = method.equals("GET") && action.equals("messages")
			|| method.equals("POST") && Set.of("pair", "session", "logout", "send").contains(action);
		if (!valid || exchange.getRequestURI().getRawQuery() != null) {
			send(exchange, 404, "application/json", "no-store", null);
			return;
		}
		// Preserve the recorder's JSON-only POST policy. Never upgrade a cross-site
		// HTML form submission to application/json on behalf of its sender.
		if (method.equals("POST") && !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
			send(exchange, 415, "application/json", "no-store", null);
			return;
		}
		byte[] data = method.equals("POST") ? exchange.getRequestBody().readNBytes(4097) : new byte[0];
		if (data.length > 4096) {
			send(exchange, 413, "application/json", "no-store", null);
			return;
		}
		try {
			String target = "http://127.0.0.1:" + api.port + api.urlPrefix + action;
			var request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(8));
			if (method.equals("POST")) {
				request.header("Content-Type", "application/json");
				String auth = exchange.getRequestHeaders().getFirst("Authorization");
				if (auth != null && auth.length() <= 512 && auth.startsWith("Bearer ")) request.header("Authorization", auth);
				request.POST(HttpRequest.BodyPublishers.ofByteArray(data));
			} else request.GET();
			var upstream = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
			byte[] body = upstream.body();
			if (body.length > 65536) {
				send(exchange, 502, "application/json", "no-store", null);
				return;
			}
			send(exchange, upstream.statusCode(), "application/json; charset=utf-8", "no-store", body);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			send(exchange, 503, "application/json", "no-store", null);
		} catch (IOException | IllegalArgumentException e) {
			send(exchange, 503, "application/json", "no-store", null);
		}
	}

	private static String mime(String name) {
		if (name.endsWith(".json")) return "application/json; charset=utf-8";
		if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
		if (name.endsWith(".css")) return "text/css; charset=utf-8";
		if (name.endsWith(".png")) return "image/png";
		if (name.endsWith(".jpg")) return "image/jpeg";
		if (name.endsWith(".webp")) return "image/webp";
		if (name.endsWith(".dspr") || name.endsWith(".bm3d")) return "application/octet-stream";
		return null;
	}

	/** JSON is constructed exclusively from validated, ASCII-only absolute URLs. */
	static String generatedManifest() {
		var scripts = new LinkedHashSet<String>();
		var styles = new LinkedHashSet<String>();
		for (Assets asset : ASSETS.values()) {
			scripts.addAll(asset.scripts);
			styles.addAll(asset.styles);
		}
		return "{\"scripts\":" + urls(scripts) + ",\"styles\":" + urls(styles) + "}";
	}

	private static String urls(Set<String> values) {
		var out = new StringBuilder("[");
		for (String value : values) {
			if (out.length() > 1) out.append(',');
			out.append('"').append(value).append('"');
		}
		return out.append(']').toString();
	}

	private static void send(HttpExchange exchange, int code, String type, String cache, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.getResponseHeaders().set("Cache-Control", cache);
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		boolean head = exchange.getRequestMethod().equals("HEAD");
		exchange.sendResponseHeaders(code, body == null || head || body.length == 0 ? -1 : body.length);
		if (body != null && !head && body.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
		}
	}
}
