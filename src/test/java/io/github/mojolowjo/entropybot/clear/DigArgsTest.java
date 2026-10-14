package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.29.0: the "via baritone" words of dig. */
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
        DigArgs a = DigArgs.parse("0 83 -3 5 83 2 VIA Baritone junk drop water ores");
        assertTrue(a.viaBaritone());
        assertTrue(a.junkDrop());
        assertTrue(a.water());
        assertTrue(a.ores());
        assertTrue(DigArgs.parse("0 83 -3 5 83 2 force via baritone").force());
    }

    @Test
    void plainDigIsNotVia() {
        assertFalse(DigArgs.parse("0 83 -3 5 83 2").viaBaritone());
        assertFalse(DigArgs.parse("0 83 -3 5 83 2 junk drop").viaBaritone());
    }

    @Test
    void viaAloneOrViaSomethingElseIsNoDig() {
        assertNull(DigArgs.parse("0 83 -3 5 83 2 via"));
        assertNull(DigArgs.parse("0 83 -3 5 83 2 via engine"));
    }

    @Test
    void floorBlockIsNotTakenFromTheViaWords() {
        DigArgs a = DigArgs.parse("0 83 -3 5 83 2 floor via baritone");
        assertTrue(a.floor());
        assertNull(a.floorBlock());
        assertTrue(a.viaBaritone());
        assertEquals("dirt", DigArgs.parse("0 83 -3 5 83 2 floor dirt baritone").floorBlock());
    }

    @Test
    void surfaceFormParsesTheWordToo() {
        DigArgs a = DigArgs.parse("0 0 5 5 -3 via baritone");
        assertTrue(a.surfaceForm());
        assertTrue(a.viaBaritone(), "parsed; the verb refuses it for the surface form");
    }

}
