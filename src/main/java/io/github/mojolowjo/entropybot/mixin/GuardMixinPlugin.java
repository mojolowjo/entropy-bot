package io.github.mojolowjo.entropybot.mixin;

import io.github.mojolowjo.entropybot.guard.MixinFlags;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the Baritone Mixin when the readable class is not there (the ProGuard "api" jar instead of the
 * unoptimized one), and records which hooks really went in, so {@code features()} tells the truth.
 */
public final class GuardMixinPlugin implements IMixinConfigPlugin {
    private static final String BARITONE_TARGET = "baritone/pathing/movement/CalculationContext.class";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GuardMixinCalculationContext")) {
            boolean present = resourceExists(BARITONE_TARGET);
            MixinFlags.astarTargetPresent = present;
            return present;
        }
        if (mixinClassName.endsWith("FarmlandMixinPrecomputedData")) return resourceExists("baritone/pathing/precompute/PrecomputedData.class");
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith("GuardMixinCalculationContext")) MixinFlags.astarApplied = true;
        else if (mixinClassName.endsWith("GuardMixinGameMode")) MixinFlags.clickApplied = true;
        else if (mixinClassName.endsWith("GuardMixinBlockItem")) MixinFlags.placeApplied = true;
        else if (mixinClassName.endsWith("FarmlandMixinPrecomputedData")) MixinFlags.farmlandApplied = true;
        else if (mixinClassName.endsWith("WatchMixinCameraAccess")) MixinFlags.cameraPosApplied = true;
        else if (mixinClassName.endsWith("RecorderMixinClientLevel")) MixinFlags.levelHookApplied = true;
    }

    /** A resource lookup never loads the class, so the Mixin can still transform it. */
    private static boolean resourceExists(String path) {
        ClassLoader own = GuardMixinPlugin.class.getClassLoader();
        if (own != null && own.getResource(path) != null) return true;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null && ctx != own && ctx.getResource(path) != null;
    }
}
