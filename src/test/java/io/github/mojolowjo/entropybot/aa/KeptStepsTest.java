package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-kept, steps (owner's yes 2026-11-16; the simulator on the mod's Steps: "step 1/7 collect cobblestone 8 skipped:
 * 32 cobblestone already in the bag" with cobblestone 32 -d waiting for its deposit - the furnace was then made from the
 * -d cobblestone and 24 of 32 reached the chest): the skip check and the dry run judge on the bag less what it holds for
 * the other orders' deposits (Steps.Hands.freeBag).
 */
class KeptStepsTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final String COBBLE = "minecraft:cobblestone";

    /** Z73RepairTest's fake hands with a kept table: freeBag is the bag less it (as StepHands over Goals.keptFromOthers). */
    static class Hands extends Z73RepairTest.Hands {
        final Map<String, Integer> kept = new HashMap<>();

        @Override public StepNeeds.Bag freeBag() { return StepNeeds.less(bag(), kept); }
    }

    static Hands furnaceHands() {
        Hands h = new Hands();
        h.needs.put("craft furnace 1", Z73RepairTest.uses(Map.of(COBBLE, 8)));
        return h;
    }

    static final List<Step> FURNACE = List.of(new Step.Collect("cobblestone", 8), new Step.Craft("furnace", 1));

    /** Nothing kept: the 32 in the bag make the collect needless, as before. */
    @Test
    void nothingKept_theCollectIsSkipped() {
        Hands h = furnaceHands();
        h.bag.put(COBBLE, 32);
        new Steps(h).start("goal.torch", FURNACE, 0, (f, fact, why) -> null);
        assertEquals(List.of("craft furnace 1"), h.started);
        assertEquals(List.of("step 1/2 collect cobblestone 8 skipped: 32 cobblestone already in the bag"), h.logged);
    }

    /** The 32 are another order's -d amount: the collect runs, the furnace takes its own 8. */
    @Test
    void anotherOrdersDepositAmount_isNotWhatTheSkipCounts() {
        Hands h = furnaceHands();
        h.bag.put(COBBLE, 32);
        h.kept.put("cobblestone", 32);           // Goals.kept's keys: the counted id
        new Steps(h).start("goal.torch", FURNACE, 0, (f, fact, why) -> null);
        assertEquals(List.of("collect cobblestone 8"), h.started);
        assertTrue(h.logged.stream().noneMatch(l -> l.contains("skipped")), h.logged.toString());
    }

    /** 40 held, 32 kept: 8 are free, the collect is skipped on those. */
    @Test
    void whatIsOverTheKeptAmount_stillSkips() {
        Hands h = furnaceHands();
        h.bag.put(COBBLE, 40);
        h.kept.put("cobblestone", 32);
        new Steps(h).start("goal.torch", FURNACE, 0, (f, fact, why) -> null);
        assertEquals(List.of("craft furnace 1"), h.started);
        assertEquals(List.of("step 1/2 collect cobblestone 8 skipped: 8 cobblestone already in the bag"), h.logged);
    }

    /** The view itself: counts and stacks less the kept amounts, by full id or path, never under 0; nothing kept is the bag itself. */
    @Test
    void less_takesTheKeptOffCountsAndStacks() {
        Hands h = new Hands();
        h.bag.putAll(Map.of(COBBLE, 32, "minecraft:stick", 4, "minecraft:dirt", 2));
        StepNeeds.Bag raw = h.bag();
        assertSame(raw, StepNeeds.less(raw, Map.of()));
        StepNeeds.Bag free = StepNeeds.less(raw, Map.of("cobblestone", 30, "minecraft:dirt", 5));
        assertEquals(2, free.count(COBBLE));
        assertEquals(0, free.count("minecraft:dirt"));
        assertEquals(4, free.count("minecraft:stick"));
        assertEquals(Map.of(COBBLE, 2, "minecraft:stick", 4), free.items());
    }
}
