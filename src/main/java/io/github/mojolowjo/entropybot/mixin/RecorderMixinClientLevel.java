package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.recorder.FlightRecorder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * B7e (E5) the flight recorder's ears: every block the client sets passes one of these two methods.
 * <ul>
 *   <li>{@code setServerVerifiedBlockState}: the server's block and section updates (its {@code super.setBlock} is
 *       Level's, so the hook below does not see them twice). A state the bot predicted is not applied here: it stays as
 *       it is and the server's answer, if different, comes back through {@code syncBlockState} -> {@code setBlock}.</li>
 *   <li>{@code setBlock}: the client's own changes. While the prediction handler is predicting it is the bot's own
 *       break or place (MultiPlayerGameMode wraps those in a prediction): those are the {@code byBot} changes.</li>
 * </ul>
 * The old state is read at HEAD (one chunk lookup, only while the recorder is on) and the change reported at RETURN.
 * require = 0: if a hook can't go in, the game still starts and the recorder simply hears nothing.
 */
@Mixin(ClientLevel.class)
public abstract class RecorderMixinClientLevel {
    @Shadow @Final private BlockStatePredictionHandler blockStatePredictionHandler;

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), require = 0)
    private void entropybot$verifiedHead(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        FlightRecorder.pushOld((Level) (Object) this, pos);
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"), require = 0)
    private void entropybot$verifiedReturn(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        BlockState old = FlightRecorder.popOld();
        if (old == null) return;
        BlockState now = ((Level) (Object) this).getBlockState(pos);
        if (now != old) FlightRecorder.blockChanged((ClientLevel) (Object) this, pos, old, now, false);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), require = 0)
    private void entropybot$setHead(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        FlightRecorder.pushOld((Level) (Object) this, pos);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"), require = 0)
    private void entropybot$setReturn(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        BlockState old = FlightRecorder.popOld();
        if (old == null || !Boolean.TRUE.equals(cir.getReturnValue())) return;
        boolean byBot = false;
        try { byBot = blockStatePredictionHandler.isPredicting(); } catch (Throwable ignored) {}
        FlightRecorder.blockChanged((ClientLevel) (Object) this, pos, old, state, byBot);
    }
}
