package dev.diorama.frontier;

import dev.diorama.Diorama;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.share.ShareProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Read-only client copy of the server's global frontiers. The server supplies the
 * edit capability and validates every mutation; local changes are never authoritative.
 */
public final class FrontierClient {
    private static final Map<UUID, Frontier> visible = new LinkedHashMap<>();
    private static final Map<UUID, Drawing> pendingConversions = new LinkedHashMap<>();
    private static @Nullable Map<UUID, Frontier> incoming;
    private static boolean ready;
    private static boolean editable;
    private static int revision;

    private FrontierClient() {
    }

    public static void reset() {
        visible.clear();
        pendingConversions.clear();
        incoming = null;
        ready = false;
        editable = false;
        revision = 0;
    }

    /** A server without native frontier support leaves the client in read-only mode. */
    public static boolean available() {
        return ready;
    }

    public static boolean canEdit() {
        return ready && editable;
    }

    public static List<Frontier> all() {
        return ready ? visible.values().stream().map(Frontier::copy).toList() : List.of();
    }

    public static List<Frontier> in(String dimension) {
        if (!ready) return List.of();
        List<Frontier> result = new ArrayList<>();
        for (Frontier frontier : visible.values()) {
            if (frontier.dimension.equals(dimension)) result.add(frontier.copy());
        }
        return result;
    }

    public static @Nullable Frontier at(String dimension, double x, double z) {
        if (!ready) return null;
        List<Frontier> candidates = in(dimension);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Frontier f = candidates.get(i);
            if (f.visible && f.contains(x, z)) return f;
        }
        return null;
    }

    public static boolean put(Frontier draft) {
        return put(draft, null);
    }

    public static boolean put(Frontier draft, @Nullable Drawing convertedFrom) {
        if (!canEdit() || !draft.valid()) return false;
        if (convertedFrom != null) pendingConversions.put(draft.id, convertedFrom);
        ShareProtocol.sendToServer(FrontierWire.put(revision, draft.copy()));
        return true;
    }

    public static boolean remove(UUID id) {
        if (!canEdit()) return false;
        ShareProtocol.sendToServer(FrontierWire.remove(revision, id));
        return true;
    }

    /** Called by ShareClient on the Minecraft client thread. */
    public static void receive(byte[] data) {
        if (!FrontierWire.isFrontier(data)) return;
        try (DataInputStream in = FrontierWire.input(data)) {
            switch (data[0] & 0xFF) {
                case FrontierWire.BEGIN -> {
                    int version = in.readUnsignedByte();
                    int nextRevision = in.readInt();
                    boolean permission = in.readBoolean();
                    if (version != FrontierWire.VERSION || nextRevision < 0) {
                        reset();
                        return;
                    }
                    incoming = new LinkedHashMap<>();
                    revision = nextRevision;
                    editable = permission;
                    ready = false;
                }
                case FrontierWire.ENTRY -> {
                    Frontier frontier = FrontierWire.readFrontier(in);
                    if (incoming != null && incoming.size() < FrontierStore.MAX_FRONTIERS) {
                        incoming.put(frontier.id, frontier);
                    }
                }
                case FrontierWire.END -> {
                    if (incoming != null) {
                        visible.clear();
                        visible.putAll(incoming);
                        incoming = null;
                        ready = true;
                    }
                }
                case FrontierWire.CHANGED -> {
                    int nextRevision = in.readInt();
                    Frontier frontier = FrontierWire.readFrontier(in);
                    if (!ready || nextRevision != revision + 1) {
                        requestRefresh();
                        return;
                    }
                    visible.put(frontier.id, frontier);
                    revision = nextRevision;
                    Drawing original = pendingConversions.remove(frontier.id);
                    DrawingStore drawings = DrawingStore.get();
                    if (original != null && drawings != null) drawings.remove(original);
                }
                case FrontierWire.REMOVED -> {
                    int nextRevision = in.readInt();
                    UUID id = FrontierWire.readUuid(in);
                    if (!ready || nextRevision != revision + 1) {
                        requestRefresh();
                        return;
                    }
                    visible.remove(id);
                    revision = nextRevision;
                }
                case FrontierWire.ERROR -> {
                    String message = in.readUTF();
                    pendingConversions.clear();
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal(message), false);
                }
                default -> { return; }
            }
            FrontierWire.complete(in);
        } catch (IOException | RuntimeException e) {
            Diorama.LOGGER.warn("Couldn't decode the server's frontier update", e);
            requestRefresh();
        }
    }

    private static void requestRefresh() {
        if (incoming == null) {
            ShareProtocol.sendToServer(FrontierWire.packet(FrontierWire.REFRESH, out -> {}));
        }
    }
}
