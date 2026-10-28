package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 0.39.z78 strip-off review: StripWays' default is OFF (a game must not strip before its world's switch is read); the
 * tests price the strip way as before - this extension (auto-detected: junit-platform.properties, META-INF/services) turns
 * it on before each test, and the strip-off tests turn it off themselves.
 */
public final class StripsOnInTests implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) { StripWays.set(true); }
}
