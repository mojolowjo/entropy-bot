package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** aa-r1fix: the idle stock_iron ore step and the camp chest's cell. */
class R1FixTest {
    // ---- idle stock_iron: the ore step comes first, through the iron options ----

    @Test void idleIronMinesBeforeItSmelts() {
        String[] why = new String[1];
        Options.Option o = IronOptions.idleOption((java.util.function.Supplier<IronOptions.Facts>) () -> WoodOptionsTest.facts(0),
                io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, why);
        // either an ore way runs (mine before smelt) or nothing runs; never the bare smelt
        if (o == null) assertNotNull(why[0]);
        else {
            String chain = o.command();
            assertTrue(o.typed(), "rm-execution: typed steps");
            assertTrue(chain.indexOf("mine") >= 0 && chain.indexOf("mine") < chain.indexOf("smelt"), chain);
        }
    }

    @Test void idleIronEnoughRawIronKeepsTheSmelt() {
        Options.Option o = IronOptions.idleOption((java.util.function.Supplier<IronOptions.Facts>) () -> WoodOptionsTest.facts(16),
                io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, new String[1]);
        assertNotNull(o);
        assertTrue(o.steps().stream().anyMatch(s -> s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Smelt), o.command());
    }

    @Test void idleIronNoFactsRunsNothing() {
        String[] why = new String[1];
        assertNull(IronOptions.idleOption(null, io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 0, why), "rm-execution: no planner chain text runs bare");
        assertEquals("no iron facts", why[0]);
    }

}
