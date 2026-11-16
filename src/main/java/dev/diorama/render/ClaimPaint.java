package dev.diorama.render;

import dev.diorama.ClaimProjection;
import dev.diorama.Claims;
import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Straight chunk boundaries on a fixed-height plane, independent of hills, trees and saved picks. */
final class ClaimPaint {
	static final int FILL_ALPHA = 0x33;
	static final int FORCE_LOADED_ALPHA = 0x52;
	static final int EDGE_ALPHA = 0xCC;

	private ClaimPaint() {
	}

	static int @Nullable [] paint(TileKey key, Claims.Snapshot claims) {
		int size = Projection.TILE_PIXELS;
		int edge = key.lod() == 0 ? 2 : 1;
		int stride = size + edge * 2;
		double step = key.units() / size;
		Facing facing = Projection.facing();
		int[] columns = new int[stride];
		for (int x = 0; x < stride; x++) {
			columns[x] = Math.floorDiv((int) Math.floor(key.minU() + (x - edge + 0.5) * step), 16);
		}
		// Sample beyond every edge so adjacent tiles agree on the same continuous grid.
		int[] ids = new int[stride * stride];
		List<Claims.Claim> owners = new ArrayList<>();
		owners.add(null);
		Map<Claims.Claim, Integer> idOf = new HashMap<>();
		boolean any = false;
		for (int y = 0; y < stride; y++) {
			double v = key.minV() + (y - edge + 0.5) * step;
			int cz = Math.floorDiv((int) Math.floor(Projection.zAt(v, ClaimProjection.height(key.layer()))), 16);
			int previousX = Integer.MIN_VALUE;
			int id = 0;
			for (int x = 0; x < stride; x++) {
				int cx = columns[x];
				if (cx != previousX) {
					previousX = cx;
					Claims.Claim claim = claims.at(facing.worldBlockX(cx, cz), facing.worldBlockZ(cx, cz));
					id = claim == null ? 0 : idOf.computeIfAbsent(claim, owner -> {
						owners.add(owner);
						return owners.size() - 1;
					});
				}
				ids[y * stride + x] = id;
				any |= id != 0 && x >= edge && x < size + edge && y >= edge && y < size + edge;
			}
		}
		if (!any) return null;
		int[] fill = new int[owners.size()];
		int[] line = new int[owners.size()];
		for (int id = 1; id < owners.size(); id++) {
			Claims.Claim claim = owners.get(id);
			fill[id] = abgr(claim.forceLoaded() ? FORCE_LOADED_ALPHA : FILL_ALPHA, claim.rgb());
			line[id] = abgr(EDGE_ALPHA, claim.rgb());
		}
		int[] pixels = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = (y + edge) * stride + x + edge;
				int id = ids[i];
				if (id == 0) continue;
				boolean border = false;
				for (int d = 1; d <= edge; d++) {
					border |= ids[i - d] != id || ids[i + d] != id
						|| ids[i - stride * d] != id || ids[i + stride * d] != id;
				}
				pixels[y * size + x] = border ? line[id] : fill[id];
			}
		}
		return pixels;
	}

	private static int abgr(int alpha, int rgb) {
		return alpha << 24 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
	}
}
