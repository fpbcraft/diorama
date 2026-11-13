package dev.diorama.webassets;

import dev.diorama.web.WebFrontendAssets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/** Server-only bundle; clients use their core frontend for the native second-screen map. */
@Mod(value = "diorama_web", dist = Dist.DEDICATED_SERVER)
public final class DioramaWebAssets {
    public DioramaWebAssets() {
        String version = ModList.get().getModContainerById("diorama_web")
            .orElseThrow().getModInfo().getVersion().toString();
        try {
            WebFrontendAssets.register(1, version, DioramaWebAssets.class, "/diorama-web-assets/");
        } catch (LinkageError | IllegalArgumentException error) {
            System.getLogger("DioramaWebAssets").log(System.Logger.Level.WARNING,
                "This web-assets bundle requires a compatible Diorama core; retaining its bundled frontend", error);
        }
    }
}
