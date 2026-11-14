package dev.diorama.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;

/** Explicit client imports only. Rendering and the dedicated server never fetch a waypoint URL. */
public final class UrlWaypointIcons {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ICONS = 128;
    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Diorama icon timeout");
        thread.setDaemon(true);
        return thread;
    });
    private static final byte[] PNG = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private UrlWaypointIcons() {}

    public static Path file(Path folder, String id) {
        if (!id.matches("url:[a-f0-9]{40}")) return null;
        return folder.resolve(id.substring(4) + ".png");
    }

    public static List<String> all(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString())
                .filter(name -> name.matches("[a-f0-9]{40}[.]png"))
                .sorted().limit(MAX_ICONS).map(name -> "url:" + name.substring(0, 40)).toList();
        } catch (IOException error) { return List.of(); }
    }

    /** Invoked by Download and use, on a worker thread, never from a pin's render path. */
    public static String download(String input, Path folder) throws IOException {
        return importIcon(input, folder, UrlWaypointIcons::fetch);
    }

    @FunctionalInterface interface Fetch { byte[] read(URI uri) throws IOException; }

    static String importIcon(String input, Path folder, Fetch fetch) throws IOException {
        URI uri;
        try { uri = new URI(input.trim()); }
        catch (URISyntaxException error) { throw new IOException("Enter a valid HTTPS image URL"); }
        if (input.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443)
            throw new IOException("Use a public HTTPS PNG URL without a login or custom port");
        // Bound the cache before making another network request.
        if (all(folder).size() >= MAX_ICONS) throw new IOException("Downloaded icon limit reached (128)");
        byte[] png = fetch.read(uri);
        if (png.length > MAX_BYTES || png.length < 24 || !java.util.Arrays.equals(PNG, java.util.Arrays.copyOf(png, 8)))
            throw new IOException("Use a PNG image smaller than 1 MiB");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Cannot read this PNG image");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 256 || height > 256)
                    throw new IOException("Use an image no larger than 256 by 256 pixels");
                var image = reader.read(0);
                boolean visible = false;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                    visible |= (image.getRGB(x, y) >>> 24) >= 128;
                if (!visible) throw new IOException("The image is completely transparent");
            } finally { reader.dispose(); }
        }
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)).substring(0, 40); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        Files.createDirectories(folder);
        Path part = Files.createTempFile(folder, "import-", ".part");
        try {
            Files.write(part, png);
            Files.move(part, folder.resolve(key + ".png"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(part); }
        return "url:" + key;
    }

    private static byte[] fetch(URI uri) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                || bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 0xc0) == 64)
                throw new IOException("Use a public image host");
        }
        var connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "image/png");
        var deadline = TIMEOUTS.schedule(connection::disconnect, 10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Image host must return a PNG directly (no redirects)");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Use a PNG image smaller than 1 MiB");
            try (var in = connection.getInputStream()) { return in.readNBytes(MAX_BYTES + 1); }
        } finally { deadline.cancel(false); connection.disconnect(); }
    }
}
