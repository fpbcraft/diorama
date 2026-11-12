package dev.diorama.mixin;
import dev.diorama.web.WebMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LevelChunk.class)
public abstract class WebMapChunkMixin {
	@Inject(method = "setBlockState", at = @At("RETURN"))
	//? if >=1.21.5 {
	private void diorama$webChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
	//?} else {
	/*private void diorama$webChange(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
	*///?}
		if (cir.getReturnValue() != null && cir.getReturnValue() != state
				&& ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) WebMap.blockChanged(level, pos);
	}
}
