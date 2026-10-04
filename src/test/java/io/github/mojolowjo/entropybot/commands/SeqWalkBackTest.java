package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Package E review fix 2: after a fight the altar run goes back to its exact walk first (the run keeps its state). */
class SeqWalkBackTest {
    @Test
    void theAltarRunWalksBackOntoItsSpot() {
        Seq.Step walk = Seq.Step.walk(new int[]{-22, 53, 157}, false);
        walk.exact = true;
        Seq.Step run = new Seq.Step("infuse");
        List<Seq.Step> steps = List.of(new Seq.Step("altarfind"), walk, run);
        assertEquals(1, Seq.Step.walkBackTo(steps, 2), "back to the exact walk");
        assertEquals(1, Seq.Step.walkBackTo(steps, 1), "the walk itself stays");
        Seq.Step plain = Seq.Step.walk(new int[]{0, 60, 0}, false);
        assertEquals(1, Seq.Step.walkBackTo(List.of(plain, run), 1), "only after an exact walk");
        assertEquals(1, Seq.Step.walkBackTo(List.of(walk, new Seq.Step("craft")), 1), "other steps are left alone");
    }
}
