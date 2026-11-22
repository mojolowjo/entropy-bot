package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test tooling (docs repo to-do/test-tooling-plan.md; cloud tester runs 25-28, 2026-11-22: at "tick rate 200" the world
 * ran 10 times as fast and the bot no faster). Vanilla 1.21.1 {@code Minecraft.getTickTargetMillis(float)} answers
 * {@code Math.max(defaultValue, tickRateManager.millisecondsPerTick())}: the client follows a SLOWER tick rate and never a
 * faster one, so the player, Baritone and this mod stay at 20 ticks a second (read in neoforge-21.1.249-sources.jar,
 * Minecraft.java; the timer is {@code new DeltaTracker.Timer(20.0F, 0L, this::getTickTargetMillis)}). With Tuning
 * {@code clock.clientTickRate} 1 this answers the level's own milliseconds per tick instead, so the client ticks at the
 * server's rate. The client still runs 10 ticks a frame at most (Minecraft.runTick's {@code Math.min(10, i)}): the
 * reachable rate is 10 x the frames a second. Off (the default): vanilla, untouched.
 *
 * <p>No NeoForge event sets the client's tick length (ClientTickEvent only reports a tick), hence the mixin; require = 1
 * as every hook here; {@code GameClock.clientRateApplied} says it went in (aa status' clock line).
 */
@Mixin(Minecraft.class)
public abstract class ClockMixinMinecraft {

    @Inject(method = "getTickTargetMillis(F)F", at = @At("HEAD"), cancellable = true, require = 1)
    private void entropybot$tickTarget(float defaultValue, CallbackInfoReturnable<Float> cir) {
        try {
            io.github.mojolowjo.entropybot.aa.brain.GameClock.clientRateApplied = true;
            if (!io.github.mojolowjo.entropybot.aa.brain.GameClock.followServerRate()) return;
            Minecraft mc = (Minecraft) (Object) this;
            if (mc.level == null) return;
            TickRateManager m = mc.level.tickRateManager();
            if (m.runsNormally()) cir.setReturnValue(m.millisecondsPerTick());
        } catch (Throwable t) {
            io.github.mojolowjo.entropybot.Quiet.warn("clock: the client tick-rate hook failed (the client stays at 20 ticks a second)", t);
        }
    }

    /**
     * Tick-rate trial 2 (cloud tester runs 30-33, 2026-11-22: at rate 200 the frame rate fell to 4 and the bot reached 3.4
     * to 4.7 times, not 10): {@code Minecraft.runTick(boolean)} runs {@code Math.min(10, i)} client ticks a frame - the one
     * Math.min(II) call in that method (read in the same sources jar, Minecraft.java line 1164). With the hook on, the
     * 10 becomes Tuning clock.ticksAFrame, so a slow renderer no longer caps the tick rate. Off: vanilla's 10.
     */
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "runTick(Z)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), index = 0, require = 1)
    private int entropybot$ticksAFrame(int vanilla) {
        return io.github.mojolowjo.entropybot.aa.brain.GameClock.ticksAFrame(vanilla);
    }
}
