package dev.diorama.frontier;

import java.util.List;

/** Only banner dyes and pattern IDs are saved or sent. Item contents and custom data stay local. */
public record FrontierBanner(int base, List<Layer> layers) {
    public static final int MAX_LAYERS = 16;

    public record Layer(String pattern, int dye) {
    }

    public FrontierBanner {
        layers = List.copyOf(layers);
    }

    public boolean valid() {
        return base >= 0 && base < 16 && layers.size() <= MAX_LAYERS && layers.stream().allMatch(layer ->
            layer.dye >= 0 && layer.dye < 16 && layer.pattern != null && layer.pattern.length() <= 128
                && layer.pattern.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"));
    }
}
