package dev.diorama.mixin;
import dev.diorama.web.WebMap;
import net.minecraft.server.level.ServerLevel;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class WebMapTickMixin {
	@Inject(method = "tick", at = @At("RETURN"))
	private void diorama$webTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) { WebMap.tick((ServerLevel) (Object) this); }
}
