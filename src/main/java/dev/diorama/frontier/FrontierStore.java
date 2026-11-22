package dev.diorama.frontier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.diorama.Diorama;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Local, per-world frontier storage. Never writes to the Minecraft server. Client thread only. */
public final class FrontierStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static @Nullable FrontierStore current;

    private final String worldKey;
    private final Path file;
    private final List<Frontier> frontiers = new ArrayList<>();

    private FrontierStore(String worldKey) {
        this.worldKey = worldKey;
        this.file = WorldKey.folder(worldKey).resolve("frontiers.json");
        this.load();
    }

    public static @Nullable FrontierStore get() {
        String worldKey = WorldKey.current();
        if (worldKey == null) {
            return null;
        }
        if (current == null || !current.worldKey.equals(worldKey)) {
            current = new FrontierStore(worldKey);
        }
        return current;
    }

    public static void onLeave() {
        current = null;
    }

    public static FrontierStore open(String worldKey) {
        return new FrontierStore(worldKey);
    }

    public List<Frontier> in(String dimension) {
        return this.frontiers.stream()
            .filter(frontier -> frontier.dimension.equals(dimension))
            .map(Frontier::copy).toList();
    }

    public @Nullable Frontier at(String dimension, double x, double z) {
        for (int i = this.frontiers.size() - 1; i >= 0; i--) {
            Frontier frontier = this.frontiers.get(i);
            if (frontier.visible && frontier.dimension.equals(dimension) && frontier.contains(x, z)) {
                return frontier.copy();
            }
        }
        return null;
    }

    public boolean put(Frontier proposed) {
        if (!proposed.valid()) {
            return false;
        }
        Frontier next = proposed.copy();
        int index = -1;
        for (int i = 0; i < this.frontiers.size(); i++) {
            if (this.frontiers.get(i).id.equals(next.id)) {
                index = i;
                break;
            }
        }
        Frontier old = index >= 0 ? this.frontiers.set(index, next) : null;
        if (index < 0) {
            this.frontiers.add(next);
        }
        if (this.save()) {
            return true;
        }
        if (old != null) {
            this.frontiers.set(index, old);
        } else {
            this.frontiers.remove(next);
        }
        return false;
    }

    public boolean remove(UUID id) {
        for (int i = 0; i < this.frontiers.size(); i++) {
            if (this.frontiers.get(i).id.equals(id)) {
                Frontier removed = this.frontiers.remove(i);
                if (this.save()) {
                    return true;
                }
                this.frontiers.add(i, removed);
                return false;
            }
        }
        return false;
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        boolean damaged = false;
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                damaged = true;
            } else {
                for (JsonElement element : root.getAsJsonArray()) {
                    try {
                        Frontier frontier = GSON.fromJson(element, Frontier.class);
                        if (frontier != null && frontier.valid()
                            && this.frontiers.stream().noneMatch(f -> f.id.equals(frontier.id))) {
                            this.frontiers.add(frontier);
                        } else {
                            damaged = true;
                        }
                    } catch (RuntimeException ex) {
                        damaged = true;
                        Diorama.LOGGER.warn("Skipping invalid frontier in {}", this.file, ex);
                    }
                }
            }
        } catch (Exception ex) {
            damaged = true;
            Diorama.LOGGER.warn("Unable to load frontiers from {}", this.file, ex);
        }
        if (damaged) {
            try {
                Files.copy(this.file, this.file.resolveSibling("frontiers.json.invalid"),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Diorama.LOGGER.warn("Unable to back up damaged frontiers in {}", this.file, ex);
            }
        }
    }

    private boolean save() {
        Path tmp = this.file.resolveSibling(this.file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(this.file.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this.frontiers, writer);
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            Diorama.LOGGER.error("Unable to save frontiers to {}", this.file, ex);
            return false;
        }
    }
}
