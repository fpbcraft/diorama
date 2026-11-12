package dev.diorama.web;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Operator controls for the progressive web-map worker.
 * "render" is similar to BlueMap's "update"; "force-render" to "force-update".
 * These commands schedule asynchronous background work, never read region files
 * or render tiles on the Minecraft server thread.
 */
public final class WebMapCommand {
	private static final int MAX_RADIUS = 16_384;

	private WebMapCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("diorama-web")
			//? if >=1.21.11 {
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
			//?} else {
			/*.requires(source -> source.hasPermission(2));
			*///?}

		root.then(Commands.literal("status").executes(context -> {
			WebMap map = WebMap.get();
			context.getSource().sendSuccess(() -> Component.literal(map == null
				? "The web map is off (web-map in config/" + WebSettings.FILE + ")"
				: "Web map: " + map.status()), false);
			return 1;
		}));
		root.then(Commands.literal("redraw").executes(c -> full(c, true)));
		root.then(Commands.literal("start").executes(c -> control(c, "start")));
		root.then(Commands.literal("resume").executes(c -> control(c, "start")));
		root.then(Commands.literal("stop").executes(c -> control(c, "stop")));
		root.then(Commands.literal("pause").executes(c -> control(c, "stop")));
		root.then(Commands.literal("cancel").executes(c -> control(c, "cancel")));

		render(root, "render", false);
		render(root, "update", false);
		render(root, "force-render", true);
		render(root, "force-update", true);
		dispatcher.register(root);
	}

	private static void render(
		com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root,
		String name, boolean force
	) {
		var command = Commands.literal(name).executes(c -> full(c, force));
		command.then(Commands.literal("full").executes(c -> full(c, force)));
		command.then(Commands.literal("radius")
			.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
				.executes(c -> {
					var pos = c.getSource().getPosition();
					return area(c, c.getSource().getLevel().dimension().identifier().toString(),
						(int) Math.floor(pos.x), (int) Math.floor(pos.z),
						IntegerArgumentType.getInteger(c, "radius"), force);
				})));
		command.then(Commands.literal("at")
			.then(Commands.argument("x", IntegerArgumentType.integer())
				.then(Commands.argument("z", IntegerArgumentType.integer())
					.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
						.executes(c -> area(c, c.getSource().getLevel().dimension().identifier().toString(),
							IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"),
							IntegerArgumentType.getInteger(c, "radius"), force))))));
		command.then(Commands.literal("dimension")
			.then(Commands.argument("dimension", StringArgumentType.word())
				.then(Commands.literal("at")
					.then(Commands.argument("x", IntegerArgumentType.integer())
						.then(Commands.argument("z", IntegerArgumentType.integer())
							.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
								.executes(c -> area(c, StringArgumentType.getString(c, "dimension"),
									IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"),
									IntegerArgumentType.getInteger(c, "radius"), force))))))));
		root.then(command);
	}

	private static int full(CommandContext<CommandSourceStack> context, boolean force) {
		WebMap map = WebMap.get();
		if (map == null) {
			context.getSource().sendFailure(Component.literal("Diorama web map is disabled"));
			return 0;
		}
		map.requestFull(force);
		context.getSource().sendSuccess(() -> Component.literal(
			"Queued " + (force ? "forced" : "normal") + " render for all dimensions. Use /diorama-web status."), false);
		return 1;
	}

	private static int area(CommandContext<CommandSourceStack> context, String dimension, int x, int z, int radius, boolean force) {
		WebMap map = WebMap.get();
		if (map == null) {
			context.getSource().sendFailure(Component.literal("Diorama web map is disabled"));
			return 0;
		}
		boolean exists = false;
		for (var level : context.getSource().getServer().getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				exists = true;
				break;
			}
		}
		if (!exists) {
			context.getSource().sendFailure(Component.literal("Unknown or unloaded dimension: " + dimension));
			return 0;
		}
		map.requestArea(dimension, x, z, radius, force);
		context.getSource().sendSuccess(() -> Component.literal(
			"Queued " + (force ? "force-render" : "render") + " for " + dimension
				+ " around " + x + "," + z + " radius " + radius + " blocks."), false);
		return 1;
	}

	private static int control(CommandContext<CommandSourceStack> context, String operation) {
		WebMap map = WebMap.get();
		if (map == null) {
			context.getSource().sendFailure(Component.literal("Diorama web map is disabled"));
			return 0;
		}
		switch (operation) {
			case "start" -> map.resume();
			case "stop" -> map.pause();
			case "cancel" -> map.cancel();
			default -> throw new IllegalArgumentException(operation);
		}
		context.getSource().sendSuccess(() -> Component.literal(
			"Web map " + (operation.equals("start") ? "resumed" : operation.equals("stop") ? "paused" : "render work cancelled")
				+ "; see /diorama-web status"), false);
		return 1;
	}
}
