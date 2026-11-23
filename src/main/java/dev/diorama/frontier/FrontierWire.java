package dev.diorama.frontier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Small, versioned messages carried by Diorama's existing optional play channel.
 * A frontier is sent individually to keep each packet below the custom-payload limit.
 */
public final class FrontierWire {
    public static final int VERSION = 1;
    public static final int BEGIN = 6;
    public static final int ENTRY = 7;
    public static final int END = 8;
    public static final int PUT = 9;
    public static final int REMOVE = 10;
    public static final int CHANGED = 11;
    public static final int REMOVED = 12;
    public static final int ERROR = 13;
    public static final int REFRESH = 14;
    public static final int BANNER = 15;
    private static final int MAX_MESSAGE = 8192;

    private FrontierWire() {
    }

    public static boolean isFrontier(byte[] data) {
        return data != null && data.length > 0
            && (data[0] & 0xFF) >= BEGIN && (data[0] & 0xFF) <= BANNER;
    }

    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public static byte[] packet(int kind, Writer write) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(kind);
                write.write(out);
            }
            if (bytes.size() > MAX_MESSAGE) {
                throw new IllegalArgumentException("Frontier packet too large");
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static DataInputStream input(byte[] data) throws IOException {
        if (data.length > MAX_MESSAGE || !isFrontier(data)) {
            throw new IOException("Invalid frontier message");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readUnsignedByte();
        return in;
    }

    public static void complete(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("Trailing frontier packet data");
    }

    public static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    public static void writeFrontier(DataOutputStream out, Frontier frontier) throws IOException {
        if (!frontier.valid()) throw new IOException("Invalid frontier");
        writeUuid(out, frontier.id);
        out.writeUTF(frontier.dimension);
        out.writeUTF(frontier.name);
        out.writeInt(frontier.color & 0xFFFFFF);
        out.writeBoolean(frontier.visible);
        out.writeShort(frontier.vertices.length / 2);
        for (double coordinate : frontier.vertices) out.writeDouble(coordinate);
    }

    public static Frontier readFrontier(DataInputStream in) throws IOException {
        Frontier result = new Frontier();
        result.id = readUuid(in);
        result.dimension = in.readUTF();
        result.name = in.readUTF();
        result.color = in.readInt() & 0xFFFFFF;
        result.visible = in.readBoolean();
        int count = in.readUnsignedShort();
        if (count < 3 || count > Frontier.MAX_VERTICES) {
            throw new IOException("Invalid frontier vertex count");
        }
        result.vertices = new double[count * 2];
        for (int i = 0; i < result.vertices.length; i++) result.vertices[i] = in.readDouble();
        if (!result.valid()) throw new IOException("Invalid frontier geometry");
        return result;
    }

    public static byte[] begin(int revision, boolean editable) {
        return packet(BEGIN, out -> {
            out.writeByte(VERSION);
            out.writeInt(revision);
            out.writeBoolean(editable);
        });
    }

    public static byte[] entry(Frontier frontier) {
        return packet(ENTRY, out -> writeFrontier(out, frontier));
    }

    public static byte[] end() {
        return packet(END, out -> {});
    }

    public static byte[] put(int revision, Frontier frontier, boolean bannerEdited) {
        return packet(PUT, out -> {
            out.writeInt(revision);
            writeFrontier(out, frontier);
            if (bannerEdited) writeBanner(out, frontier.banner);
        });
    }

    public static byte[] banner(Frontier frontier) {
        return packet(BANNER, out -> {
            // Legacy viewers route unknown kinds through shared terrain, which reads a dimension first.
            out.writeUTF("");
            writeUuid(out, frontier.id);
            writeBanner(out, frontier.banner);
        });
    }

    private static void writeBanner(DataOutputStream out, @Nullable FrontierBanner banner) throws IOException {
        out.writeBoolean(banner != null);
        if (banner == null) return;
        out.writeByte(banner.base());
        out.writeByte(banner.layers().size());
        for (FrontierBanner.Layer layer : banner.layers()) {
            out.writeUTF(layer.pattern());
            out.writeByte(layer.dye());
        }
    }

    public static @Nullable FrontierBanner readBanner(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        int base = in.readUnsignedByte();
        int count = in.readUnsignedByte();
        if (count > FrontierBanner.MAX_LAYERS) throw new IOException("Too many banner layers");
        var layers = new ArrayList<FrontierBanner.Layer>(count);
        for (int i = 0; i < count; i++) {
            layers.add(new FrontierBanner.Layer(in.readUTF(), in.readUnsignedByte()));
        }
        FrontierBanner banner = new FrontierBanner(base, layers);
        if (!banner.valid()) throw new IOException("Invalid frontier banner");
        return banner;
    }

    public static byte[] remove(int revision, UUID id) {
        return packet(REMOVE, out -> {
            out.writeInt(revision);
            writeUuid(out, id);
        });
    }

    public static byte[] changed(int revision, Frontier frontier) {
        return packet(CHANGED, out -> {
            out.writeInt(revision);
            writeFrontier(out, frontier);
        });
    }

    public static byte[] removed(int revision, UUID id) {
        return packet(REMOVED, out -> {
            out.writeInt(revision);
            writeUuid(out, id);
        });
    }

    public static byte[] error(String message) {
        return packet(ERROR, out -> out.writeUTF(message));
    }
}
