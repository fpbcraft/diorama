package dev.diorama.web;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Optional server frontend override. Native second-screen maps retain the core's own assets. */
public final class WebFrontendAssets {
    public static final int API_VERSION = 1;
    public static final Set<String> FILES = Set.of(
        "index.html", "overlay-api.js", "web-markers.js", "map-controls.js", "web-queue.js",
        "web-fog.js", "fog-mask.js", "fog-mask-task.js", "fog-mask-worker.js", "fog-coverage.js",
        "fog-gpu.js", "fog-reveal.js", "fog.fsh", "fog-noise.png"
    );
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private record Provider(String version, Class<?> owner, String prefix) {}
    private static volatile Provider provider;
    private WebFrontendAssets() {}

    /** Call during mod construction, before a server starts. No network channel is registered. */
    public static synchronized void register(int apiVersion, String version, Class<?> owner, String prefix) {
        if (apiVersion != API_VERSION) throw new IllegalArgumentException("Incompatible Diorama web-assets API");
        if (version == null || version.isBlank() || owner == null || prefix == null
            || !prefix.startsWith("/") || !prefix.endsWith("/") || prefix.contains("..")) {
            throw new IllegalArgumentException("Invalid web-assets provider");
        }
        if (provider != null) throw new IllegalStateException("A Diorama web-assets provider is already registered");
        provider = new Provider(version, owner, prefix);
    }

    /** Validate the complete bundle before replacing any installed assets. */
    static boolean install(Path folder) throws IOException {
        Provider current = provider;
        if (current == null) return false;
        Map<String, byte[]> bundle = new LinkedHashMap<>();
        try {
            for (String name : FILES) {
                try (var input = current.owner.getResourceAsStream(current.prefix + name)) {
                    if (input == null) throw new IOException("Missing web asset: " + name);
                    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid web asset size: " + name);
                    bundle.put(name, bytes);
                }
            }
        } catch (IOException error) {
            Diorama.LOGGER.warn("Couldn't load Diorama web-assets {}; using the bundled frontend", current.version, error);
            return false;
        }
        for (var entry : bundle.entrySet()) Files.write(folder.resolve(entry.getKey()), entry.getValue());
        Diorama.LOGGER.info("Diorama web frontend installed from web-assets {}", current.version);
        return true;
    }
}
