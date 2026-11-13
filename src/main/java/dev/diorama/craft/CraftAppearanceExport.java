package dev.diorama.craft;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.VanillaTable;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Explicit local export of the map's resolved colours and style, including resource-pack changes. */
public final class CraftAppearanceExport {
	private CraftAppearanceExport() {}
	public static <S> void register(CommandDispatcher<S> dispatcher) {
		dispatcher.register(LiteralArgumentBuilder.<S>literal("diorama-export-web-appearance").executes(context -> {
			var client = Minecraft.getInstance();
			var looks = BlockLooks.get();
			if (looks == null) return 0;
			try {
				Path folder = client.gameDirectory.toPath().resolve("diorama-web-appearance");
				Files.createDirectories(folder);
				try (var output = Files.newOutputStream(folder.resolve("looks.dat"))) { VanillaTable.of(looks, true).write(output); }
				var gson = new GsonBuilder().setPrettyPrinting().create();
				var style = gson.toJsonTree(MapStyle.get()).getAsJsonObject();
				style.addProperty("facing", dev.diorama.Projection.facing().id());
				Files.writeString(folder.resolve("style.json"), gson.toJson(style));
				if (client.player != null) client.player.displayClientMessage(Component.literal("Diorama web appearance exported to " + folder), false);
				return 1;
			} catch (java.io.IOException | RuntimeException error) { Diorama.LOGGER.warn("Couldn't export web appearance", error); return 0; }
		}));
	}
}
