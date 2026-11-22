package dev.diorama.frontier;

import dev.diorama.Diorama;
import dev.diorama.share.ShareProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Server-authoritative global frontiers. All clients can read; only OP level 2
 * may create, move, rename or delete, including LAN players.
 * All requests are validated again on the server, regardless of client UI state.
 */
public final class FrontierServer {
    private static FrontierServer current;

    private final MinecraftServer server;
    private final FrontierStore store;
    private int revision;

    private FrontierServer(MinecraftServer server) {
        this.server = server;
        Path file = server.getWorldPath(LevelResource.ROOT)
            .resolve("data").resolve("diorama-frontiers.json");
        this.store = FrontierStore.open(file);
    }

    public static void started(MinecraftServer server) {
        current = new FrontierServer(server);
        Diorama.LOGGER.info("Global frontiers initialized for {}", server.getWorldPath(LevelResource.ROOT));
    }

    public static void stopped(MinecraftServer server) {
        if (current != null && current.server == server) current = null;
    }

    public static boolean canEdit(ServerPlayer player) {
        //? if >=1.21.11 {
        return net.minecraft.commands.Commands.hasPermission(
            net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
        //?} else {
        /*return player.hasPermissions(2);
        *///?}
    }

    /** Client can read all dimensions. Never send authority or storage paths. */
    public static void listening(ServerPlayer player) {
        FrontierServer active = current;
        if (active != null && player.getServer() == active.server) active.snapshot(player);
    }

    private void snapshot(ServerPlayer player) {
        ShareProtocol.send(player, FrontierWire.begin(this.revision, canEdit(player)));
        for (Frontier frontier : this.store.all()) {
            ShareProtocol.send(player, FrontierWire.entry(frontier));
        }
        ShareProtocol.send(player, FrontierWire.end());
    }

    /** Called only on the server thread, before the shared-terrain protocol. */
    public static boolean receive(ServerPlayer player, byte[] data) {
        if (!FrontierWire.isFrontier(data)) return false;
        FrontierServer active = current;
        if (active == null || player.getServer() != active.server) return true;
        int kind = data[0] & 0xFF;
        if (kind == FrontierWire.REFRESH) {
            active.snapshot(player);
            return true;
        }
        if (kind != FrontierWire.PUT && kind != FrontierWire.REMOVE) return true;
        if (!canEdit(player)) {
            ShareProtocol.send(player, FrontierWire.error("Only server operators can edit global frontiers"));
            return true;
        }
        try (DataInputStream in = FrontierWire.input(data)) {
            int requestedRevision = in.readInt();
            if (requestedRevision != active.revision) {
                active.snapshot(player);
                return true;
            }
            if (kind == FrontierWire.PUT) {
                Frontier frontier = FrontierWire.readFrontier(in);
                FrontierWire.complete(in);
                boolean foundDimension = false;
                for (net.minecraft.server.level.ServerLevel level : active.server.getAllLevels()) {
                    if (level.dimension().identifier().toString().equals(frontier.dimension)) {
                        foundDimension = true;
                        break;
                    }
                }
                if (!foundDimension) {
                    throw new IOException("Unknown dimension");
                }
                if (!active.store.put(frontier)) {
                    ShareProtocol.send(player, FrontierWire.error("Could not save frontier; check the shape or server storage"));
                    return true;
                }
                active.revision++;
                active.broadcast(FrontierWire.changed(active.revision, frontier));
            } else {
                java.util.UUID id = FrontierWire.readUuid(in);
                FrontierWire.complete(in);
                if (!active.store.remove(id)) {
                    ShareProtocol.send(player, FrontierWire.error("Frontier no longer exists or could not be deleted"));
                    return true;
                }
                active.revision++;
                active.broadcast(FrontierWire.removed(active.revision, id));
            }
        } catch (IOException | RuntimeException e) {
            Diorama.LOGGER.warn("Invalid frontier update from {}", player.getName().getString(), e);
            ShareProtocol.send(player, FrontierWire.error("Invalid frontier request"));
        }
        return true;
    }

    private void broadcast(byte[] message) {
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            if (dev.diorama.share.ShareServer.listeningTo(player)) {
                ShareProtocol.send(player, message);
            }
        }
    }
}
