package dev.diorama.web;

import com.google.gson.JsonParser;
import dev.diorama.frontier.Frontier;
import dev.diorama.frontier.LabelAnchor;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class WebFrontiersTest {
    public static void main(String[] args) throws Exception {
        Frontier frontier = new Frontier();
        frontier.name = "Harbour"; frontier.dimension = "minecraft:overworld";
        // Concave shape: averaging vertices puts the label outside the L.
        frontier.vertices = new double[] {0,0,100,0,100,20,20,20,20,100,0,100};
        LabelAnchor anchor = LabelAnchor.inside(frontier.vertices);
        check(frontier.contains(anchor.x(), anchor.z()) && anchor.clearance() > 0, "label lies inside concave frontier");
        Frontier excluded = frontier.copy(); excluded.dimension = "minecraft:the_nether";
        AtomicReference<List<Frontier>> snapshot = new AtomicReference<>(List.of(frontier, excluded));
        Path folder = Path.of(args[0]);
        try (WebFrontiers exporter = new WebFrontiers(folder, snapshot::get, "minecraft:overworld"::equals)) {
            Path file = folder.resolve("frontiers.json");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
            var data = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            check(data.size() == 1, "excluded dimension leaked");
            check(data.get(0).getAsJsonObject().get("name").getAsString().equals("Harbour"), "missing name");
            var label = data.get(0).getAsJsonObject().getAsJsonObject("label");
            check(frontier.contains(label.get("x").getAsDouble(), label.get("z").getAsDouble()), "invalid exported anchor");
            snapshot.set(List.of());
            deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.readString(file).equals("[]") && System.nanoTime() < deadline) Thread.sleep(20);
            check(Files.readString(file).equals("[]"), "removed frontier retained");
        }
        System.out.println("Frontier labels and public export checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
