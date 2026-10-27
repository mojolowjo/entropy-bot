package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 spot (run DC, 2026-10-27, docs/runs/runDC-z78c): at 01:35:36 "no progress 30 s ... at collect raw_iron 4" with the
 * feet at -10 23 23. The monitor keyed the fact by the collect's ITEM key, "collect raw_iron". The order and, as a world fact,
 * the other orders then held every raw_iron spot (NeedChain.spotHeld). The iron seen at 13 19 12 at 01:36:55 (18 away) was
 * never offered, and the 01:37:05 chain said "no raw_iron source known". The fix: a stalled collect holds the place it
 * stalled at (the spot the chain priced, else the feet) by its near-collect key, so spots within failures.moved (16) are
 * out and a spot farther off stays a way. Hand numbers: 13 19 12 is sqrt(23^2 + 4^2 + 11^2) = 25.8 blocks from -10 23 23.
 */
class Z77DcSpotTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] STALL = {-10, 23, 23};

    /** The monitor's stall on a collect whose signals never move (Z71's fake hands): the fact it hands the planner. */
    static FactChange.Fact stalled(Step.Collect collect) {
        Z71StepMonitorTest.Hands h = new Z71StepMonitorTest.Hands() {
            @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {      // DC's bag: the stone pickaxe and better
                return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 3; }
                    @Override public String bestTool(String kind) { return "minecraft:iron_pickaxe"; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return 0; }
                };
            }
        };
        h.script = s -> Z71StepMonitorTest.sig(16.6, STALL, false, 0, 0, -1, 0, -1, null);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron_pickaxe", List.of(collect), 0, p);
        Z71StepMonitorTest.run(run, h, p, 40);
        assertEquals(1, p.facts.size(), "one stall: " + h.logged);
        return p.facts.get(0);
    }

    @Test
    void aStalledCollect_holdsWhereItStalled_andTheIronSeenFartherOffIsOfferedAndPriced() {
        ScenariosTest.world("wooden", "stone");
        FactChange.Fact f = stalled(new Step.Collect("raw_iron", 4));
        String near = NeedChain.nearKey("raw_iron", STALL);
        assertEquals("source " + near, f.key(), "the place, not the item");
        assertEquals(near, FactChange.ownSourceKey(f), "the order holds that key (Goals.replan)");
        assertTrue(f.text().startsWith("no progress 30 s") && f.text().endsWith("at collect raw_iron 4"), f.text());

        // DC at 01:37:05: the bot at -2 16 20; the stalled cluster beside -10 23 23 (10.8 m); the iron seen at 13 19 12 (17.3 m)
        Spot cluster = new Spot(-10, 22, 24, 10.8, "minecraft:iron_ore", 2), seen = new Spot(13, 19, 12, 17.3, "minecraft:iron_ore", 4);
        NeedChain.World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), true, true, Map.of("raw_iron", List.of(cluster, seen)));

        // run DC as it was: the item key held - no raw_iron spot at all
        Chain was = NeedChain.resolve("raw_iron", 4, w.withoutSources(Map.of("collect raw_iron", f.text())));
        assertTrue(was.steps().stream().noneMatch(s -> s instanceof Step.Collect), was.why());

        // the fix: the spot near the stall is out, the iron seen farther off is the way, priced
        Chain now = NeedChain.resolve("raw_iron", 4, w.withoutSources(Map.of(near, f.text())));
        assertTrue(now.ok() && !now.explore(), now.why());
        assertTrue(now.why().contains("seen 17 m") && !now.why().contains("seen 11 m"), now.why());
        assertTrue(now.seconds() > 0, now.why());
        Step.Collect c = (Step.Collect) now.steps().stream().filter(s -> s instanceof Step.Collect).findFirst().orElseThrow();
        assertArrayEquals(new int[]{13, 19, 12}, c.at(), "the collect is the seen iron's");

        // the other orders read it as a world fact by the same key (Goals.replan -> WorldFacts): the same way
        Chain other = NeedChain.resolve("raw_iron", 4, w.withoutSources(Map.of(near, "(a world fact: iron_pickaxe learned it at -10 23 23)")));
        assertTrue(other.why().contains("seen 17 m"), other.why());
    }

    /** Control: the same spot again is held - the cluster the collect stalled at is no way while the near key holds. */
    @Test
    void theSameSpotAgain_isHeld() {
        ScenariosTest.world("wooden", "stone");
        Spot cluster = new Spot(-10, 22, 24, 10.8, "minecraft:iron_ore", 4);
        NeedChain.World w = ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), true, true, Map.of("raw_iron", List.of(cluster)));
        assertTrue(NeedChain.resolve("raw_iron", 4, w).steps().stream().anyMatch(s -> s instanceof Step.Collect), "before: the cluster");
        FactChange.Fact f = stalled(new Step.Collect("raw_iron", 4));
        Chain again = NeedChain.resolve("raw_iron", 4, w.withoutSources(Map.of(FactChange.ownSourceKey(f), f.text())));
        assertTrue(again.steps().stream().noneMatch(s -> s instanceof Step.Collect), again.why());

        // a collect with its priced spot: held near that spot (the walk may stall far from it)
        FactChange.Fact at = stalled(new Step.Collect("raw_iron", 4, new int[]{-10, 22, 24}));
        assertEquals("source " + NeedChain.nearKey("raw_iron", new int[]{-10, 22, 24}), at.key());
        Chain held = NeedChain.resolve("raw_iron", 4, w.withoutSources(Map.of(FactChange.ownSourceKey(at), at.text())));
        assertTrue(held.steps().stream().noneMatch(s -> s instanceof Step.Collect), held.why());
    }
}
