package dev.fpbcraft.diorama;

import dev.diorama.api.MapAnnotations;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/** Client-only glue. All optional mod APIs are accessed reflectively. */
@Mod(value = "fpbcraftdiorama", dist = Dist.CLIENT)
public final class FpbcraftDioramaAddon {
    public FpbcraftDioramaAddon() {
        if (ModList.get().isLoaded("minecolonies")) {
            MapAnnotations.register(new MineColoniesMarkers());
        }
        if (ModList.get().isLoaded("create")) {
            MapAnnotations.register(new TrainMarkers());
        }
        if (ModList.get().isLoaded("sable")) {
            MapAnnotations.register(new ShipMarkers());
        }
    }
}
