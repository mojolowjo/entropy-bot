package io.github.mojolowjo.entropybot.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Nav step 8 (our hands, nav/drive/DriveLive): sets {@code MultiPlayerGameMode.isDestroying}, Baritone's
 * IPlayerControllerMP / BlockBreakHelper trick. A break is held with {@code continueDestroyBlock} on the client tick's
 * start; later in the same tick vanilla's {@code Minecraft.continueAttack(false)} (the attack key is up) calls
 * {@code stopDestroyBlock}, which would abort it. So the driver sets the flag false after its call and back on its next
 * tick. An accessor is all-or-nothing: it applies or the game log has the mixin error. Loader notes: NeoForge 1.21.1 runs
 * Mojang names (the field name as written); Fabric needs the intermediary name through a refmap.
 */
@Mixin(MultiPlayerGameMode.class)
public interface DriveMixinGameModeAccess {
    @Accessor("isDestroying")
    void entropybot$setDestroying(boolean destroying);
}
