package dev.diorama.compat;

import dev.diorama.Diorama;
import java.lang.reflect.Method;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Optional Ecliptic Seasons public API bridge for the minimap information rows.
 * No class from Ecliptic Seasons is linked unless the mod is actually present.
 */
public final class EclipticSeasons {
	public enum Weather { CLEAR, RAIN, SNOW, THUNDER }
	public record Climate(@Nullable String season, Weather weather) {}

	private static final long REFRESH_MS = 1000;
	private static boolean resolved;
	private static @Nullable Object api;
	private static @Nullable Method enabled;
	private static @Nullable Method season;
	private static @Nullable Method snow;
	private static @Nullable Method rain;
	private static @Nullable Method thunder;
	private static @Nullable Level lastLevel;
	private static @Nullable BlockPos lastPos;
	private static long lastRead;
	private static @Nullable Climate cached;

	private EclipticSeasons() {}

	private static void resolve() {
		if (resolved) return;
		resolved = true;
		try {
			Class<?> type = Class.forName("com.teamtea.eclipticseasons.api.EclipticSeasonsApi");
			api = type.getMethod("getInstance").invoke(null);
			enabled = type.getMethod("isSeasonEnabled", Level.class);
			season = type.getMethod("getSeason", Level.class);
			snow = type.getMethod("isSnowAt", Level.class, BlockPos.class);
			rain = type.getMethod("isRainAt", Level.class, BlockPos.class);
			thunder = type.getMethod("isThunderAt", Level.class, BlockPos.class);
		} catch (ClassNotFoundException e) {
			// Not installed: leave the minimap's season row empty and use vanilla weather.
			api = null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			Diorama.LOGGER.warn("Ecliptic Seasons HUD integration is unavailable", e);
			api = null;
		}
	}

	/** Cached for one second, and refreshed immediately when entering another block or world. */
	public static @Nullable Climate current(Level level, BlockPos pos) {
		resolve();
		if (api == null) return null;
		long now = System.currentTimeMillis();
		if (lastLevel == level && pos.equals(lastPos) && now - lastRead >= 0 && now - lastRead < REFRESH_MS) {
			return cached;
		}
		lastLevel = level;
		lastPos = pos.immutable();
		lastRead = now;
		try {
			if (!Boolean.TRUE.equals(enabled.invoke(api, level))) {
				return cached = null;
			}
			Object value = season.invoke(api, level);
			String name = value == null || "NONE".equals(value.toString()) ? null
				: ((Component) value.getClass().getMethod("getTranslation").invoke(value)).getString();
			Weather weather = Boolean.TRUE.equals(thunder.invoke(api, level, pos)) ? Weather.THUNDER
				: Boolean.TRUE.equals(snow.invoke(api, level, pos)) ? Weather.SNOW
				: Boolean.TRUE.equals(rain.invoke(api, level, pos)) ? Weather.RAIN : Weather.CLEAR;
			return cached = new Climate(name, weather);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			Diorama.LOGGER.warn("Ecliptic Seasons HUD query failed; using vanilla weather", e);
			api = null; // Do not spam the log on every frame.
			return cached = null;
		}
	}
}
