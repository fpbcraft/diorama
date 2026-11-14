package dev.diorama.web;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SavedChunk;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Called only on the server thread. Never requests or generates an unloaded chunk. */
final class LiveChunkCapture {
	private LiveChunkCapture() {}
	@SuppressWarnings("unchecked")
	static ChunkSnapshot capture(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		PalettedContainer<net.minecraft.world.level.block.state.BlockState>[] states = new PalettedContainer[sections.length];
		PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[sections.length];
		DataLayer[] block = new DataLayer[sections.length], sky = new DataLayer[sections.length];
		for (int i = 0; i < sections.length; i++) {
			var section = sections[i];
			if (section != null) {
				if (!section.hasOnlyAir()) states[i] = section.getStates().copy();
				var palette = section.getBiomes();
				biomes[i] = palette instanceof PalettedContainer<?> container
					? (PalettedContainerRO<Holder<Biome>>) container.copy() : palette;
			}
			var position = SectionPos.of(chunk.getPos().x(), chunk.getMinSectionY() + i, chunk.getPos().z());
			var light = level.getLightEngine();
			var b = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(position);
			var s = light.getLayerListener(LightLayer.SKY).getDataLayerData(position);
			if (b != null) block[i] = b.copy();
			if (s != null) sky[i] = s.copy();
		}
		int[] surface = new int[256], solid = new int[256];
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			surface[z * 16 + x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			solid[z * 16 + x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		}
		SavedChunk saved = new SavedChunk(chunk.getPos().x(), chunk.getPos().z(), true, states, biomes, block, sky, surface, solid, dev.diorama.data.DomumMaterials.capture(chunk));
		SavedChunk[] around = new SavedChunk[9]; around[4] = saved;
		return ChunkSnapshot.fromSave(saved, around, level, 2);
	}
}
