package io.github.mojolowjo.entropybot.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routing review M2, the part of the mixin check JUnit can do (docs/PLANNING.md section 5). Minecraft and Baritone are
 * not on the test classpath, so whether the injectors really land in {@code ClientLevel} can't be tested here: the dev
 * client run fails on that ({@code mixin.debug.countInjections} in build.gradle), and in the real game {@code check}
 * reports a hook that is not applied or applied but silent. This test catches the cheap mistakes: the mixin dropped from
 * the config, renamed so the plugin's applied flag never turns on, or its target methods changed in the source.
 */
class MixinConfigTest {
    static String resource(String path) throws IOException {
        try (InputStream in = MixinConfigTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theBlockHookIsListedAndRecognisedByThePlugin() throws IOException {
        String cfg = resource("entropybot.mixins.json");
        assertTrue(cfg.contains("\"RecorderMixinClientLevel\""), "listed in entropybot.mixins.json");
        assertTrue(cfg.contains("io.github.mojolowjo.entropybot.mixin.GuardMixinPlugin"), "the plugin that records applied hooks");
        // the plugin's postApply matches the mixin by name to set MixinFlags.levelHookApplied
        String plugin = resource("io/github/mojolowjo/entropybot/mixin/GuardMixinPlugin.class");
        assertTrue(plugin.contains("RecorderMixinClientLevel"), "GuardMixinPlugin.postApply still names it");
        assertTrue(plugin.contains("levelHookApplied"), "and still sets the flag check reads");
    }

    @Test
    void theBlockHookTargetsTheTwoClientLevelMethods() throws IOException {
        String mixin = resource("io/github/mojolowjo/entropybot/mixin/RecorderMixinClientLevel.class");
        assertTrue(mixin.contains("net/minecraft/client/multiplayer/ClientLevel"), "targets ClientLevel");
        assertTrue(mixin.contains("setServerVerifiedBlockState"));
        assertTrue(mixin.contains("setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"));
        assertTrue(mixin.contains("FlightRecorder"), "reports to the recorder, which feeds the route map");
    }
}
