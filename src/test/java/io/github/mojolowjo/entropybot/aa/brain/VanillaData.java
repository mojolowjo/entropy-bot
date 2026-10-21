package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit extension: every test of the class runs on {@link TestData#vanilla()} as the game's item data, cleared after. */
public final class VanillaData implements BeforeEachCallback, AfterEachCallback {
    @Override public void beforeEach(ExtensionContext c) { TestData.vanilla().install(); }
    @Override public void afterEach(ExtensionContext c) { TestData.clear(); }
}
