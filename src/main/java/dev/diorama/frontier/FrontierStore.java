package dev.diorama.frontier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.diorama.Diorama;
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

/**
 * Server-owned, world-wide frontier data. Not keyed to the client's maps or preferences.
 * The server saves this in its world folder; every connected Diorama client receives a
 * read-only view and writes go through server permission checks.
 */
public final class FrontierStore {
    public static final int MAX_FRONTIERS = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<Frontier> frontiers = new ArrayList<>();

    private FrontierStore(Path file) {
        this.file = file;
        this.load();
    }

    /** Open the server-world data file (also useful for storage tests). */
    public static FrontierStore open(Path file) {
        return new FrontierStore(file);
    }

    public List<Frontier> all() {
        return this.frontiers.stream().map(Frontier::copy).toList();
    }

    public List<Frontier> in(String dimension) {
        return this.frontiers.stream().filter(f -> f.dimension.equals(dimension))
            .map(Frontier::copy).toList();
    }

    public @Nullable Frontier byId(UUID id) {
        return this.frontiers.stream().filter(frontier -> frontier.id.equals(id)).findFirst()
            .map(Frontier::copy).orElse(null);
    }

    public @Nullable Frontier at(String dimension, double x, double z) {
        for (int i = this.frontiers.size() - 1; i >= 0; i--) {
            Frontier f = this.frontiers.get(i);
            if (f.visible && f.dimension.equals(dimension) && f.contains(x, z)) {
                return f.copy();
            }
        }
        return null;
    }

    public boolean put(Frontier proposed) {
        if (proposed == null || !proposed.valid()) {
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
        if (index == -1 && this.frontiers.size() >= MAX_FRONTIERS) {
            return false;
        }
        Frontier old = index == -1 ? null : this.frontiers.get(index);
        if (index == -1) this.frontiers.add(next);
        else this.frontiers.set(index, next);
        if (this.save()) return true;
        if (index == -1) this.frontiers.remove(next);
        else this.frontiers.set(index, old);
        return false;
    }

    public boolean remove(UUID id) {
        for (int i = 0; i < this.frontiers.size(); i++) {
            if (this.frontiers.get(i).id.equals(id)) {
                Frontier old = this.frontiers.remove(i);
                if (this.save()) return true;
                this.frontiers.add(i, old);
                return false;
            }
        }
        return false;
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) return;
        boolean damaged = false;
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                damaged = true;
            } else {
                for (JsonElement entry : root.getAsJsonArray()) {
                    try {
                        Frontier frontier = GSON.fromJson(entry, Frontier.class);
                        if (frontier != null && frontier.valid()
                            && this.frontiers.size() < MAX_FRONTIERS
                            && this.frontiers.stream().noneMatch(f -> f.id.equals(frontier.id))) {
                            this.frontiers.add(frontier);
                        } else damaged = true;
                    } catch (RuntimeException e) {
                        damaged = true;
                        Diorama.LOGGER.warn("Skipping invalid frontier in {}", this.file, e);
                    }
                }
            }
        } catch (Exception e) {
            damaged = true;
            Diorama.LOGGER.warn("Couldn't load global frontiers from {}", this.file, e);
        }
        if (damaged) {
            try {
                Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".invalid"),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Diorama.LOGGER.warn("Couldn't back up damaged global frontiers {}", this.file, e);
            }
        }
    }

    private boolean save() {
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(this.frontiers, writer);
            }
            try {
                Files.move(temporary, this.file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Diorama.LOGGER.error("Couldn't save global frontiers {}", this.file, e);
            return false;
        }
    }
}
