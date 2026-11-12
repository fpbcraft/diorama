package dev.diorama.waypoint;

import dev.diorama.MapSession;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.render.Pins;
import dev.diorama.render.TextSize;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Waypoints in the world: each one is projected through the game camera and drawn on the HUD as
 * a pin standing on its spot, with distance shown only while the crosshair aims at it.
 * Names remain configurable, and icon and label text sizes are independent. Under the pins
 * go the beams ({@link WaypointBeams}) of the waypoints the beam setting picks.
 */
public final class WaypointHud implements HudElement {
	/** Within this many GUI pixels of the crosshair a waypoint reveals its distance. */
	private static final float NAME_RADIUS = 20;
	/** Within this many blocks a waypoint always shows its name. */
	private static final double NAME_DISTANCE = 48;

	private final WaypointBeams beams = new WaypointBeams();

	/**
	 * Distance is only displayed while the crosshair points at the waypoint.
	 * Name visibility still follows the user's AUTO/ALWAYS/DISTANCE/NONE preference.
	 */
	public static String labelText(String name, long distance, Settings.WaypointLabels labels, boolean aimed, boolean close) {
		if (labels == Settings.WaypointLabels.NONE) return null;
		boolean showName = labels == Settings.WaypointLabels.ALWAYS
			|| labels == Settings.WaypointLabels.AUTO && (aimed || close);
		String result = showName ? name : null;
		if (aimed) {
			String meters = distance + " m";
			result = result == null ? meters : result + " · " + meters;
		}
		return result;
	}

	/** True only when the waypoint is in the 20-GUI-pixel crosshair radius. */
	public static boolean aimedAt(float x, float y, float width, float height) {
		return Math.hypot(x - width / 2, y - height / 2) < NAME_RADIUS;
	}

	@Override
	//? if >=1.21 {
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
	//?} else {
	/*public void extractRenderState(GuiGraphicsExtractor graphics, float partialTick) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		Entity viewer = MapSession.viewer();
		WaypointStore store = WaypointStore.get();
		//? if >=26.2 {
		if (viewer == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.gui.hud.isHidden() || !ServerRules.minimapAllowed()) {
		//?} else {
		/*if (viewer == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.options.hideGui || !ServerRules.minimapAllowed()) {
		*///?}
			return;
		}
		Vec3 eye = WorldProjection.camera();
		if (eye == null) {
			return;
		}
		float width = graphics.guiWidth();
		float height = graphics.guiHeight();
		Font font = mc.font;
		List<Waypoint> waypoints = store.shownIn(mc.level.dimension().identifier().toString());
		float scale = Pins.scale(Settings.get().waypointWorldSize);
		float textScale = TextSize.scale(Settings.get().waypointLabelTextSize);
		// A pin stands above its spot, so a big one is still on screen with its spot well off it.
		float margin = 28 * Math.max(1, scale);
		Settings.Beams beamsShown = Settings.get().waypointBeams;
		if (beamsShown != Settings.Beams.OFF) {
			// Under every pin and label, so a beam never covers one. A pin shows through the land; its
			// beam only where nothing stands in front of it.
			float z = 0;
			//? if <1.21.6 {
			/*// Drawn straight away here, at the depth the pins are given.
			z = graphics.pose().last().pose().m32();
			*///?}
			this.beams.begin(mc.level, eye, width, height, z);
			try {
				for (Waypoint waypoint : waypoints) {
					if (WaypointBeams.has(waypoint, beamsShown) && WaypointStore.isShown(waypoint) && WaypointStore.isNear(waypoint, viewer)) {
						this.beams.add(waypoint);
					}
				}
				this.beams.draw(graphics);
			} finally {
				this.beams.end();
			}
		}
		for (Waypoint waypoint : waypoints) {
			if (!WaypointStore.isShown(waypoint) || !WaypointStore.isNear(waypoint, viewer)) {
				continue;
			}
			double dx = waypoint.x + 0.5 - eye.x;
			double dy = waypoint.y + 1.0 - eye.y;
			double dz = waypoint.z + 0.5 - eye.z;
			float[] screen = WorldProjection.project(waypoint.x + 0.5, waypoint.y + 1.0, waypoint.z + 0.5, width, height);
			if (screen == null) {
				continue;
			}
			float sx = screen[0];
			float sy = screen[1];
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			Settings.WaypointLabels labels = Settings.get().waypointLabels;
			boolean nearCrosshair = aimedAt(sx, sy, width, height);
			String text = labelText(waypoint.shownName(), Math.round(distance), labels, nearCrosshair, distance < NAME_DISTANCE);
			int w = text == null ? 0 : font.width(text);
			// It stays until the pin and the text under it have both left the screen: big text is
			// wider than the pin, and hangs further below the spot.
			float side = Math.max(margin, (w / 2.0F + 2) * textScale);
			float below = Math.max(margin, Pins.SHADOW * scale + 10 * textScale);
			if (sx < -side || sy < -below || sx > width + side || sy > height + margin) {
				continue;
			}
			// Keep the world anchor fractional: snapping it to GUI pixels makes markers jump
			// by several framebuffer pixels at larger GUI scales. Move the entire marker together.
			graphics.pose().pushMatrix();
			graphics.pose().translate(sx, sy);
			try {
				Pins.standing(graphics, waypoint, 0, 0, Pins.LARGE, scale);
				if (text == null) {
					continue;
				}
				graphics.pose().translate(-w * textScale / 2.0F, textScale + Pins.SHADOW * scale);
				graphics.pose().scale(textScale);
				graphics.fill(-2, -1, w + 2, 9, 0x80000000);
				graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
			} finally {
				graphics.pose().popMatrix();
			}
		}
	}
}
