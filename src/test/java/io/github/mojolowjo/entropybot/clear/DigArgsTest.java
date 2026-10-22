package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words of dig: via baritone | engine, force, ores; the water, junk and floor words are gone. */
class DigArgsTest {
    @Test
    void viaBaritone() {
        DigArgs a = DigArgs.parse("0 83 -3 5 83 2 via baritone");
        assertTrue(a.viaBaritone());
        assertArrayEquals(new int[]{0, 83, -3, 5, 83, 2}, a.n());
        assertFalse(a.force());
    }

    @Test
    void baritoneAloneAndAnyOrderAndCase() {
        assertTrue(DigArgs.parse("0 83 -3 5 83 2 baritone").viaBaritone());
        DigArgs a = DigArgs.parse("0 83 -3 5 83 2 VIA Baritone ores");
        assertTrue(a.viaBaritone());
        assertTrue(a.ores());
        assertTrue(DigArgs.parse("0 83 -3 5 83 2 force via baritone").force());
    }

    @Test
    void plainDigIsNotVia() {
        assertFalse(DigArgs.parse("0 83 -3 5 83 2").viaBaritone());
    }

    @Test
    void viaAloneOrViaSomethingElseIsNoDig() {
        assertNull(DigArgs.parse("0 83 -3 5 83 2 via"));
        assertTrue(DigArgs.parse("0 83 -3 5 83 2 via engine").viaEngine());          // 0.30.0
    }

    @Test
    void theRemovedWordsAreNoDig() {
        assertNull(DigArgs.parse("0 83 -3 5 83 2 floor"));
        assertNull(DigArgs.parse("0 83 -3 5 83 2 junk drop"));
        assertNull(DigArgs.parse("0 83 -3 5 83 2 water"));
        assertNull(DigArgs.parse("0 83 -3 5 83 2 water large"));
        assertNull(DigArgs.parse("~ ~ ~ ~ ~ ~"), "no ~ coordinates");
        assertTrue(DigArgs.USAGE.endsWith("[force] [ores] [via engine]"));
    }

    @Test
    void surfaceFormParsesTheWordToo() {
        DigArgs a = DigArgs.parse("0 0 5 5 -3 via baritone");
        assertTrue(a.surfaceForm());
        assertTrue(a.viaBaritone(), "parsed; the verb refuses it for the surface form");
    }

    // 0.30.0 (P2): the builder is the default; "via engine" the old path
    @Test
    void builderIsTheDefault() {
        assertTrue(DigArgs.parse("0 83 -3 5 83 2").builder());
        assertTrue(DigArgs.parse("0 83 -3 5 83 2 via baritone").builder());
        assertTrue(DigArgs.parse("0 0 5 5 -3").builder(), "the surface form too");
        assertFalse(DigArgs.parse("0 83 -3 5 83 2 via engine").builder());
        assertFalse(DigArgs.parse("0 83 -3 5 83 2 engine").builder());
        assertFalse(DigArgs.parse("0 0 5 5 -3 ores via engine").builder());
        assertNull(DigArgs.parse("0 83 -3 5 83 2 via baritone via engine"), "one or the other");
        assertNull(DigArgs.parse("0 83 -3 5 83 2 via"));
        assertTrue(DigArgs.useBuilder(false));
        assertFalse(DigArgs.useBuilder(true));
    }
}