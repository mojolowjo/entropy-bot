package io.github.mojolowjo.entropybot.aa.door;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoorTest {
    private boolean aa;
    private long now = 1_000_000;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Door.reset();
        Door.wire(() -> aa, log::add);
        Door.clock(() -> now);
    }

    @AfterEach
    void tearDown() {
        Door.reset();
        Door.wire(null, null);
        Door.clock(System::currentTimeMillis);
    }

    @Test
    void aaOffEverythingPasses() {
        for (Driver d : Driver.values()) assertTrue(Door.allow(d, "x"));
        assertTrue(Door.allow("unscoped"));
        assertEquals("refused starts: none", Door.text());
        assertTrue(log.isEmpty());
    }

    @Test
    void aaOnOnlyAaOwnerReflexPass() {
        aa = true;
        assertTrue(Door.allow(Driver.AA, "chain aa"));
        assertTrue(Door.allow(Driver.OWNER, "travel goto"));
        assertTrue(Door.allow(Driver.REFLEX, "x"));
        for (Driver d : List.of(Driver.DANGER, Driver.RULE, Driver.AI, Driver.OTHER)) assertFalse(Door.allow(d, "x"), d.name());
        assertEquals(1, Door.refused(Driver.RULE));
    }

    @Test
    void noScopeIsOther() {
        aa = true;
        assertNull(Door.current());
        assertFalse(Door.allow("chain escape stairs"));
        assertEquals(1, Door.refused(Driver.OTHER));
        assertTrue(Door.text().contains("other 1 (last: chain escape stairs)"), Door.text());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("refused other: chain escape stairs"));
    }

    @Test
    void scopesNestAndRestore() {
        aa = true;
        try (var a = Door.scope(Driver.AA)) {
            assertTrue(Door.allow("job"));
            try (var b = Door.scope(Driver.RULE)) {
                assertFalse(Door.allow("chain rule #1"));
                try (var c = Door.scope(null)) {
                    assertNull(Door.current());
                }
                assertEquals(Driver.RULE, Door.current());
            }
            assertEquals(Driver.AA, Door.current());
        }
        assertNull(Door.current());
    }

    @Test
    void repeatsCountedOncePerWindow() {
        aa = true;
        for (int i = 0; i < 50; i++) Door.allow(Driver.OTHER, "travel back to defending");
        assertEquals(1, Door.refused(Driver.OTHER));
        Door.allow(Driver.OTHER, "job pick up my table");
        assertEquals(2, Door.refused(Driver.OTHER));
        now += Door.REPEAT_MS;
        Door.allow(Driver.OTHER, "travel back to defending");
        assertEquals(3, Door.refused(Driver.OTHER));
        assertEquals(3, log.size());
    }

    @Test
    void textNamesEachDriver() {
        aa = true;
        Door.allow(Driver.DANGER, "retreat home from zombie");
        Door.allow(Driver.AI, "goto");
        assertEquals("refused starts: danger 1 (last: retreat home from zombie), ai 1 (last: goto)", Door.text());
    }

    @Test
    void refusalTextNamesTheDriver() {
        try (var s = Door.scope(Driver.RULE)) {
            assertEquals("error: refused: aa is on, rule may not start or stop a job (chain brain)", Door.refusal("chain brain"));
        }
    }

    @Test
    void aaSwitchThatThrowsCountsAsOff() {
        Door.wire(() -> { throw new IllegalStateException("no world"); }, log::add);
        assertTrue(Door.allow(Driver.OTHER, "x"));
    }

    @Test
    void aiOnlyTranslatesUnderAa() {
        assertTrue(Door.aiMayUnderAa("aa want 10 iron_ingot"));
        assertTrue(Door.aiMayUnderAa("  AA   drop iron"));
        assertTrue(Door.aiMayUnderAa("aa status"));
        assertTrue(Door.aiMayUnderAa("status"));
        assertFalse(Door.aiMayUnderAa("goto 1 2 3"));
        assertFalse(Door.aiMayUnderAa("stop"));
        assertFalse(Door.aiMayUnderAa("aa off"));
        assertFalse(Door.aiMayUnderAa("aa wanted"));
        assertFalse(Door.aiMayUnderAa(null));
    }
}
