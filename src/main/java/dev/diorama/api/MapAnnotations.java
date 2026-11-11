package dev.diorama.api;

import dev.diorama.Diorama;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small client-side extension point for companion mods. Providers are registered during client
 * initialization, then called on the Minecraft client thread. They may draw transient geometry
 * (for example curved rails or colony borders) and provide live, unsaved map pins.
 *
 * <p>Providers should cache their data: {@link Source#markers} and {@link Source#draw} can be
 * called every frame. Sources must not scan entire worlds from these callbacks. A broken optional
 * integration is logged and disabled for the session rather than breaking Diorama's map.
 */
public final class MapAnnotations {
    public interface Source {
        /** Live pins already known for this dimension. Use {@code Waypoint.Kind.LIVE}. */
        default List<Waypoint> markers(String dimension) {
            return List.of();
        }

        /** Draw additional map geometry, respecting the dimension and visible world bounds. */
        default void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        }

        /** Update/copy data into a cached snapshot without blocking the client. */
        default void tick(Minecraft minecraft) {
        }
    }

    private static final CopyOnWriteArrayList<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final Set<Source> FAILED = ConcurrentHashMap.newKeySet();

    private MapAnnotations() {
    }

    /** Safe to call from another mod's client-side initialization. */
    public static void register(Source source) {
        SOURCES.addIfAbsent(Objects.requireNonNull(source, "source"));
    }

    /** For a companion mod's resource or world lifecycle, if it wants to stop providing markers. */
    public static void unregister(Source source) {
        SOURCES.remove(source);
        FAILED.remove(source);
    }

    public static List<Waypoint> markers(String dimension) {
        List<Waypoint> out = new ArrayList<>();
        for (Source source : SOURCES) {
            if (FAILED.contains(source)) {
                continue;
            }
            try {
                List<Waypoint> markers = source.markers(dimension);
                if (markers == null) {
                    continue;
                }
                for (Waypoint waypoint : markers) {
                    if (waypoint != null && waypoint.kind == Waypoint.Kind.LIVE
                        && waypoint.visible && dimension.equals(waypoint.dimension)) {
                        out.add(waypoint);
                    }
                }
            } catch (RuntimeException | LinkageError exception) {
                fail(source, exception);
            }
        }
        return out;
    }

    public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        for (Source source : SOURCES) {
            if (!FAILED.contains(source)) {
                try {
                    source.draw(graphics, view, dimension, fullscreen);
                } catch (RuntimeException | LinkageError exception) {
                    fail(source, exception);
                }
            }
        }
    }

    public static void tick(Minecraft minecraft) {
        for (Source source : SOURCES) {
            if (!FAILED.contains(source)) {
                try {
                    source.tick(minecraft);
                } catch (RuntimeException | LinkageError exception) {
                    fail(source, exception);
                }
            }
        }
    }

    private static void fail(Source source, Throwable exception) {
        if (FAILED.add(source)) {
            Diorama.LOGGER.warn("Disabling failing map annotation source {}", source.getClass().getName(), exception);
        }
    }
}
