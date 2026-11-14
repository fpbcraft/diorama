package dev.diorama.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

public final class UrlWaypointIconsTest {
    static byte[] png(int width, int height, boolean visible) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (visible) image.setRGB(0, 0, 0xff65cab8);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }
    interface Attempt { void run() throws IOException; }
    static void rejects(Attempt attempt) throws IOException {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid icon accepted");
    }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("diorama-url-icons-");
        try {
            var requests = new AtomicInteger();
            UrlWaypointIcons.Fetch fetch = uri -> { requests.incrementAndGet(); return png(11, 11, true); };
            for (String url : new String[] {"file:///secret.png", "http://example.com/a.png", "data:image/png,x", "https://user:pass@example.com/a", "https://example.com:8443/a", "https://example.com/a#fragment", "https:///a", "x".repeat(2049)})
                rejects(() -> UrlWaypointIcons.importIcon(url, folder, fetch));
            if (requests.get() != 0) throw new AssertionError("Invalid URLs reached downloader");
            rejects(() -> UrlWaypointIcons.download("https://127.0.0.1/icon.png", folder));
            rejects(() -> UrlWaypointIcons.download("https://[::1]/icon.png", folder));
            rejects(() -> UrlWaypointIcons.importIcon("https://example.com/a", folder, uri -> "<svg/>".getBytes()));
            rejects(() -> UrlWaypointIcons.importIcon("https://example.com/a", folder, uri -> new byte[UrlWaypointIcons.MAX_BYTES + 1]));
            rejects(() -> UrlWaypointIcons.importIcon("https://example.com/a", folder, uri -> png(257, 11, true)));
            rejects(() -> UrlWaypointIcons.importIcon("https://example.com/a", folder, uri -> png(11, 11, false)));
            String id = UrlWaypointIcons.importIcon("https://example.com/first.png", folder, fetch);
            String same = UrlWaypointIcons.importIcon("https://other.example.com/second.png", folder, fetch);
            if (!id.equals(same) || id.length() > 64 || UrlWaypointIcons.all(folder).size() != 1)
                throw new AssertionError("Icons must be content addressed, persistent and protocol compatible");
            if (UrlWaypointIcons.file(folder, "url:../../secret") != null) throw new AssertionError("Cache traversal accepted");
            if (!java.util.Arrays.equals(Files.readAllBytes(UrlWaypointIcons.file(folder, id)), png(11, 11, true)))
                throw new AssertionError("Cache changed PNG pixels");
            for (int i = 0; i < 127; i++) Files.write(folder.resolve(String.format("%040x.png", i)), png(1, 1, true));
            rejects(() -> UrlWaypointIcons.importIcon("https://example.com/full.png", folder, uri -> { throw new AssertionError("Full cache must not make a request"); }));
            System.out.println("URL icons: explicit download, URL/image bounds, private-host rejection, stable cache and protocol IDs passed");
        } finally {
            try (var files = Files.walk(folder)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
}
