package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** job-refusal-fact: worlds run 3, Tester 2 w072 replayed (8,115 refused breaks on rails, two cells). */
class RefusedBreaksTest {
    static final String OW = "minecraft:overworld", RAIL = "minecraft:rail", WHY = "structure block outside my areas";

    @Test
    void w072SaidOncePerCellAndCounted() {
        RefusedBreaks r = new RefusedBreaks();
        int said = 0;
        for (int i = 0; i < 2303; i++) if (r.note(OW, -29, -33, -143, RAIL, WHY)) said++;
        for (int i = 0; i < 5804; i++) if (r.note(OW, -25, -32, -123, RAIL, WHY)) said++;
        assertEquals(2, said, "one line per cell, not 8,115");
        String t = r.take();
        assertTrue(t.contains("minecraft:rail at -25 -32 -123 5804 times"), t);
        assertTrue(t.contains("at -29 -33 -143 2303 times"), t);
        assertNull(r.take(), "the counts are said once");
        r.note(OW, -25, -32, -123, RAIL, WHY);
        assertTrue(r.take().contains("-25 -32 -123 1 time ("));
    }

    /** job-review-leftovers (8): the store is bounded; the oldest cell goes out first and its unsaid count is still said. */
    @Test
    void boundedOldestOutWithItsCountSaid() {
        RefusedBreaks r = new RefusedBreaks();
        r.cap(2);
        for (int i = 0; i < 3; i++) r.note(OW, 1, 2, 3, RAIL, WHY);
        r.note(OW, 4, 5, 6, RAIL, WHY);
        r.note(OW, 7, 8, 9, RAIL, WHY);
        assertEquals(2, r.size());
        String t = r.take();
        assertTrue(t.contains("at 1 2 3 3 times") && t.contains("let go past 2 cells"), t);
        assertTrue(t.contains("at 7 8 9 1 time"), t);
        assertTrue(r.note(OW, 1, 2, 3, RAIL, WHY), "a let-go cell refused again is said again");
    }

    /** Review: a refusal tied to its moment (the travel cap, no job, water) is its own record and never a held block. */
    @Test
    void aDifferentReasonOrBlockIsANewRecordAndTheOldCountIsKept() {
        RefusedBreaks r = new RefusedBreaks();
        assertTrue(r.note(OW, 1, 2, 3, "minecraft:stone", "travel: over the surface cap"));
        assertFalse(r.note(OW, 1, 2, 3, "minecraft:stone", "travel: over the surface cap"));
        assertTrue(r.note(OW, 1, 2, 3, "minecraft:stone", "water beside the cell"), "a new reason is said");
        assertTrue(r.note(OW, 1, 2, 3, "minecraft:air", "water beside the cell"), "a new block is said");
        String t = r.take();
        assertTrue(t.contains("minecraft:stone at 1 2 3 2 times (travel: over the surface cap)"), t);
        assertTrue(t.contains("minecraft:stone at 1 2 3 1 time (water beside the cell)"), t);
        assertTrue(t.contains("minecraft:air at 1 2 3 1 time"), t);
    }

    @Test
    void aWorldChangeSaysTheUnsaidCountsAndForgets() {
        RefusedBreaks r = new RefusedBreaks();
        r.note(OW, 1, 2, 3, RAIL, WHY);
        r.note(OW, 1, 2, 3, RAIL, WHY);
        assertTrue(r.clear().contains("2 times"));
        assertEquals(0, r.size());
        assertNull(r.clear());
        assertTrue(r.note(OW, 1, 2, 3, RAIL, WHY), "a new world: said again");
    }

    @Test
    void theStepEndFactCarriesTheRefusal() {
        FactChange.Fact f = new FactChange.Fact("climb up", "no progress 30 s at climb");
        FactChange.Fact g = Steps.withRefused(f, "the guard refused the break of minecraft:rail at -25 -32 -123 5804 times");
        assertEquals("climb up", g.key());
        assertTrue(g.text().endsWith("5804 times"));
        assertSame(g, Steps.withRefused(g, "the guard refused the break of minecraft:rail at -25 -32 -123 5804 times"));
        assertNull(Steps.withRefused(null, "x"));
    }

    /** Fake hands: the guard's store behind {@code refusedBreaks}, as StepHands wires it. */
    static final class Hands implements Steps.Hands {
        final RefusedBreaks store = new RefusedBreaks();
        final List<String> log = new ArrayList<>();
        final boolean fail;
        Hands(boolean fail) { this.fail = fail; }
        @Override public String start(Step s) {
            for (int i = 0; i < 5804; i++) store.note(OW, -25, -32, -123, RAIL, WHY);
            return null;
        }
        @Override public Car.Status status(Step s) {
            if (!fail) return Car.Status.done("there");
            FactChange.unreachable("climb", "at -25 -32 -123", "no progress");
            return Car.Status.failed("walk: no progress");
        }
        @Override public void cancel(String why) {}
        @Override public void log(String line) { log.add(line); }
        @Override public String refusedBreaks() { return store.take(); }
    }

    /** Review (fault 4): a step that ended well says its refusals at its end; nothing is left for a later step. */
    @Test
    void aStepThatEndsWellSaysItsCount() {
        Hands h = new Hands(false);
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Walk(5, 64, 5, 1)), 0, null);
        for (int t = 1; t < 5 && run.running(); t++) run.tick(t);
        assertTrue(h.log.stream().anyMatch(l -> l.startsWith("step 1 ended") && l.contains("5804 times")), h.log.toString());
        assertNull(h.store.take(), "said once, nothing pinned on a later step");
    }

    /** The failing step's fact names the refusal for the planner. */
    @Test
    void aFailingStepCarriesItsCountOnItsFact() {
        Hands h = new Hands(true);
        Steps run = new Steps(h);
        String[] text = new String[1];
        run.start("owner goal", List.of(new Step.Walk(5, 64, 5, 1)), 0, (failed, fact, why) -> {
            text[0] = fact.text();
            return null;
        });
        for (int t = 1; t < 5 && run.running(); t++) run.tick(t);
        assertNotNull(text[0], run.why());
        assertTrue(text[0].contains("minecraft:rail at -25 -32 -123 5804 times"), text[0]);
    }

    /** Review sent back: a record replaced at the same cell (a new reason) keeps pending within the cap too. */
    @Test
    void aReplacedRecordKeepsPendingWithinTheCap() {
        RefusedBreaks rb = new RefusedBreaks();
        rb.cap(2);
        for (int i = 0; i < 10; i++) {
            rb.note("overworld", 1, 2, 3, "minecraft:stone", "reason " + i);
            rb.note("overworld", 1, 2, 3, "minecraft:stone", "reason " + i);
        }
        String said = rb.take();
        assertNotNull(said);
        assertEquals(4, said.split("; ").length, said);   // two kept lines, one folded total, the live record
        assertTrue(said.contains("more breaks at 7 more cells"), said);
    }
}
