package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit extension: the vanilla item data plus what the goal planner reads (recipes, furnace recipes, ore drops), cleared after. */
public final class PlannerData implements BeforeEachCallback, AfterEachCallback {
    @Override public void beforeEach(ExtensionContext c) { TestData.planner().install(); }
    @Override public void afterEach(ExtensionContext c) { TestData.clear(); }
}