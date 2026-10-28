package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 dw (run DW 17:58:32-18:07:59, to-do/dw-strip-loop.md): the strip for raw_iron 11 priced 3895 s was split into 7
 * legs (steps.stepMaxS 600 s) of iron 2,2,2,2,1,1,1; each leg counted only its own finds against its own share, so after
 * the third leg had found 13 the last four still dug (7 min). Now a leg's finds past its share carry to the next leg of the
 * same strip (StripOrder.carriedInto / need / surplus), and a leg they cover ends met at its start - not started, its
 * deposit leg and walk back passing as met. This replays the run's leg sequence with StripStep's rule (pure).
 */
class DwStripLegsTest {
    static final List<String> ORES = List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore");

    @AfterEach
    void clear() { Tuning.reset(); }

    /** One leg's outcome: started (dug) or covered at its start, and its end words. */
    record Leg(long serial, Map<String, Integer> amounts, boolean started, String end) {}

    /**
     * StripStep's leg rule over the chain's strip steps: {@code finds} - per started leg, the drops its tunnel yields once
     * started (it digs until its need is met, the end rule then stops it with what it picked).
     */
    static List<Leg> run(List<Step> steps, List<Integer> finds) {
        List<Leg> out = new ArrayList<>();
        Map<String, Integer> carried = Map.of();
        String lastKey = null;
        long lastSerial = 0;
        int f = 0;
        for (Step st : steps) {
            if (!(st instanceof Step.Strip s)) continue;
            carried = StripOrder.carriedInto(s.key(), s.serial(), lastKey, lastSerial, carried);
            lastKey = s.key();
            lastSerial = s.serial();
            Car.Status covered = StripOrder.metAtStart(s.amounts(), carried);
            if (covered != null) {
                carried = StripOrder.surplus(s.amounts(), carried);
                out.add(new Leg(s.serial(), s.amounts(), false, covered.why()));
                continue;
            }
            Map<String, Integer> need = StripOrder.need(s.amounts(), carried);
            Map<String, Integer> picked = Map.of("iron", finds.get(f++));
            Car.Status end = StripOrder.endRule(need, picked, false);
            assertNotNull(end, "the leg's finds meet what it needs: " + need + " vs " + picked);
            carried = StripOrder.orderMet(end.why()) ? StripOrder.surplus(need, picked) : Map.of();
            out.add(new Leg(s.serial(), s.amounts(), true, end.why()));
        }
        return out;
    }

    static Step.Strip whole(int iron) { return new Step.Strip(ORES, Map.of("iron", iron), 16, "south", 0, null); }

    /** The manager's scenario: a batch lacking 14 raw iron priced at 2000 s, the first leg's yield meets all 14. */
    @Test
    void aBatchLacking14_theFirstLegFindsAll_oneStripDug_theRestMetAtTheirStart() {
        List<Step> steps = NeedChain.stripLegs(whole(14), 2000);
        List<Step.Strip> legs = steps.stream().filter(s -> s instanceof Step.Strip).map(s -> (Step.Strip) s).toList();
        assertEquals(4, legs.size(), "2000 s over steps.stepMaxS 600 s: the step watch is the only reason for legs");
        assertEquals(14, legs.stream().mapToInt(l -> l.amounts().get("iron")).sum(), "the shares sum to the batch's lack");

        List<Leg> ran = run(steps, List.of(14));
        assertEquals(1, ran.stream().filter(Leg::started).count(), "one strip dug: " + ran);
        assertTrue(ran.get(0).end().startsWith("ok: strip: amounts met (iron 14/"), ran.get(0).end());
        for (Leg l : ran.subList(1, ran.size())) {
            assertFalse(l.started(), "leg " + l.serial() + " is never started");
            assertTrue(l.end().startsWith("ok: strip: amounts met by the earlier legs' finds"), l.end());
            assertTrue(StripOrder.orderMet(l.end()), "met: its deposit leg and walk back pass (no deposit, no walk)");
        }
    }

    /** Run DW's own legs (iron 2,2,2,2,1,1,1 for raw_iron 11; finds 5, 2 from drops, 6, ...): two legs dig, not seven. */
    @Test
    void runDW_theLegsAfterTheWholeIsFoundAreNotDug() {
        List<Step> steps = NeedChain.stripLegs(whole(11), 3895.0);
        List<Integer> shares = steps.stream().filter(s -> s instanceof Step.Strip).map(s -> ((Step.Strip) s).amounts().get("iron")).toList();
        assertEquals(List.of(2, 2, 2, 2, 1, 1, 1), shares, "the run's 17:58:32 legs");
        // leg 1 found 5 (18:00:03 "iron 5/2"); leg 2 is covered by its surplus 3; leg 3 needs 1 and found 6 (18:00:40)
        List<Leg> ran = run(steps, List.of(5, 6));
        assertEquals(List.of(true, false, true, false, false, false, false), ran.stream().map(Leg::started).toList(), ran.toString());
        assertEquals("ok: strip: amounts met (iron 6/1)", ran.get(2).end(), "leg 3 needed 1 (its 2 less the 1 carried)");
    }

    /** No carry across strips or into a whole / an owner's order: only the next leg of the same strip. */
    @Test
    void theSurplusCarriesOnlyIntoTheNextLegOfTheSameStrip() {
        String k = whole(4).key();
        Map<String, Integer> c = Map.of("iron", 3);
        assertEquals(c, StripOrder.carriedInto(k, 2, k, 1, c));
        assertEquals(Map.of(), StripOrder.carriedInto(k, 1, k, 4, c), "a new set of legs (a re-plan: the bag holds the finds)");
        assertEquals(Map.of(), StripOrder.carriedInto(k, 3, k, 1, c), "not the next leg");
        assertEquals(Map.of(), StripOrder.carriedInto("other|16|east", 2, k, 1, c), "another strip");
        assertEquals(Map.of(), StripOrder.carriedInto(k, 0, k, -1, c), "a whole strip (serial 0)");
        assertEquals(Map.of("iron", 0), StripOrder.need(Map.of("iron", 2), Map.of("iron", 5)));
        assertEquals(Map.of("iron", 3), StripOrder.surplus(Map.of("iron", 2), Map.of("iron", 5)));
        assertNull(StripOrder.metAtStart(Map.of("iron", 2), Map.of("iron", 1)), "not covered: it digs for the 1 left");
        assertNull(StripOrder.metAtStart(Map.of(), Map.of("iron", 9)), "an order with no amounts runs until stopped");
    }
}
