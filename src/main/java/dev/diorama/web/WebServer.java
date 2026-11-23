package dev.diorama.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.diorama.Diorama;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * The web map's own small web server, as BlueMap and Dynmap have one: serves the folder the map
 * draws into, read-only, and nothing else. Java's built-in HTTP server, two threads.
 */
final class WebServer {
	private final HttpServer server;
	private final java.util.concurrent.ExecutorService workers;

	private WebServer(HttpServer server, java.util.concurrent.ExecutorService workers) {
		this.server = server;
		this.workers = workers;
	}

	/** Starts serving a map with no excluded dimension folders. */
	static @Nullable WebServer start(Path folder, String address, int port) {
		return start(folder, address, port, name -> false);
	}

	/**
	 * Starts serving {@code folder}, or returns null (logged) if the port can't be had. Nothing is
	 * served from a folder in it whose name, in lower case, {@code closed} says yes to.
	 */
	static @Nullable WebServer start(Path folder, String address, int port, java.util.function.Predicate<String> closed) {
		try {
			Path root = folder.toAbsolutePath().normalize();
			return start(address, port, exchange -> serve(root, closed, exchange));
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Diorama's web map can't serve on port {} ({}); set web-map-port to a free one, or 0 to serve the folder yourself", port, e.toString());
			return null;
		} catch (NoClassDefFoundError e) {
			Diorama.LOGGER.warn("This Java has no built-in web server; serve {} with one of your own", folder);
			return null;
		}
	}

	/**
	 * Starts answering every request with {@code handler}, on {@code address} (empty for every
	 * address). Throws if the port can't be had, and {@link NoClassDefFoundError} on a Java without
	 * the built-in web server.
	 */
	static WebServer start(String address, int port, com.sun.net.httpserver.HttpHandler handler) throws IOException {
		InetSocketAddress bind = address.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(address, port);
		HttpServer server = HttpServer.create(bind, 64);
		server.createContext("/", handler);
		java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
			Thread thread = new Thread(task, "Diorama web map server");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(workers);
		server.start();
		return new WebServer(server, workers);
	}

	/** The port it listens on. */
	int port() {
		return this.server.getAddress().getPort();
	}

	void stop() {
		this.server.stop(0);
		this.workers.shutdownNow();
	}

	private static void serve(Path root, java.util.function.Predicate<String> closed, HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			// Registered routes enforce their own method limits; the bounded
			// same-origin chat adapter is the only extension accepting POST.
			if (WebExtensions.serve(exchange, path)) return;
			if (!method.equals("GET") && !method.equals("HEAD")) {
				send(exchange, 405, "text/plain", null);
				return;
			}
			if (path.equals("/") || path.isEmpty()) {
				path = "/index.html";
			}
			Path file = root.resolve(path.substring(1)).normalize();
			// Only public map assets within the real map root.
			String name = file.getFileName() == null ? "" : file.getFileName().toString();
			String type = name.endsWith(".jpg") ? "image/jpeg" : name.endsWith(".json") ? "application/json" : name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8" : name.endsWith(".fsh") ? "text/plain; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".png") ? "image/png" : (name.endsWith(".depth") || name.endsWith(".dspr") || name.endsWith(".dshd") || name.endsWith(".ground")) ? "application/octet-stream" : null;
			if (!file.startsWith(root) || type == null
				|| excluded(root, file, closed)
				|| !Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath())
				|| excluded(root.toRealPath(), file.toRealPath(), closed)) {
				// Outside the folder, also through a link inside it; or a dimension the settings keep off the map.
				send(exchange, 404, "text/plain", null);
				return;
			}
			exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
			Instant modified = Files.getLastModifiedTime(file).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
			exchange.getResponseHeaders().set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(modified, ZoneOffset.UTC)));
			// Pictures change when their land does: the browser asks again, and gets a 304 if not.
			// A picture asked for with its version (the page's way) never changes under that address: the
			// browser keeps it. Anything else may change, and is checked each time (a 304 if not).
			String query = exchange.getRequestURI().getRawQuery();
			exchange.getResponseHeaders().set("Cache-Control", (type.equals("image/jpeg") || name.endsWith(".fog.png") || name.endsWith(".depth") || name.endsWith(".dspr") || name.endsWith(".dshd") || name.endsWith(".ground")) && query != null && query.startsWith("v=")
				? "public, max-age=31536000, immutable" : "no-cache");
			boolean live = name.equals("scene.json") || name.equals("live.json") || name.equals("presence.json") || name.equals("player.json")
				|| name.equals("frontiers.json") || name.equals("server-overlays.json") || name.equals("world-status.json");
			if (live) exchange.getResponseHeaders().set("Cache-Control", "no-store");
			if (name.endsWith(".js") && root.relativize(file).toString().replace('\\', '/').matches("modules/[A-Za-z0-9.+-]+/.+\\.js")) {
				exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
			}
			String since = live ? null : exchange.getRequestHeaders().getFirst("If-Modified-Since");
			if (since != null) {
				try {
					if (!modified.isAfter(ZonedDateTime.parse(since, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())) {
						exchange.sendResponseHeaders(304, -1);
						return;
					}
				} catch (DateTimeParseException ignored) {
				}
			}
			byte[] body;
			try {
				body = Files.readAllBytes(file);
			} catch (IOException e) {
				// Replaced as it was read: the browser asks again.
				send(exchange, 404, "text/plain", null);
				return;
			}
			send(exchange, 200, type, method.equals("HEAD") ? null : body);
		} catch (IOException | RuntimeException e) {
			// The browser went away.
		} finally {
			exchange.close();
		}
	}

	/** Also check the real path: a link inside the map root must not alias a closed dimension. */
	private static boolean excluded(Path root, Path file, java.util.function.Predicate<String> closed) {
		return file.getNameCount() > root.getNameCount() + 1
			&& closed.test(file.getName(root.getNameCount()).toString().toLowerCase(java.util.Locale.ROOT));
	}

	static void send(HttpExchange exchange, int code, String type, byte @Nullable [] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(code, body == null ? -1 : body.length);
		if (body != null) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
	}
}
