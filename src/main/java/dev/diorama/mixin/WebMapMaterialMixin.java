package dev.diorama.mixin;
import dev.diorama.data.DomumMaterials;
import dev.diorama.web.WebMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockEntity.class)
public abstract class WebMapMaterialMixin {
    @Inject(method="setChanged()V",at=@At("RETURN"))
    private void diorama$materialChanged(CallbackInfo ci){BlockEntity entity=(BlockEntity)(Object)this;if(entity.getLevel() instanceof ServerLevel level&&DomumMaterials.supported(entity))WebMap.blockChanged(level,entity.getBlockPos());}
}
