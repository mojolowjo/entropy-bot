package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.move.Profiles;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HungerConserveTest {
    @Test void decision() {
        assertTrue(HungerConserve.decide(true, true, 5, 30));
        assertFalse(HungerConserve.decide(true, false, 5, 30), "a source is known");
        assertFalse(HungerConserve.decide(true, true, 30, 30), "the bag covers the need");
        assertFalse(HungerConserve.decide(false, true, 5, 30), "creative: no drain");
        assertTrue(HungerConserve.covered(40, 30));
    }

    @Test void flagLapses() {
        HungerConserve.set(true, 1000);
        assertTrue(HungerConserve.active(1000 + HungerConserve.LAPSE_MS - 1));
        assertFalse(HungerConserve.active(1000 + HungerConserve.LAPSE_MS));
        HungerConserve.set(false, 1000);
        assertFalse(HungerConserve.active(1001));
    }

    static final class Fake implements Profiles.Access {
        final Map<String, Object> m = new HashMap<>(Map.of("allowsprint", true, "allowparkour", true));
        public Object get(String n) { return m.get(n); }
        public void set(String n, Object v) { m.put(n, v); }
    }

    @Test void conserveAndRestore() {
        Profiles p = new Profiles();
        Fake a = new Fake();
        p.conserve(true, false, a);
        assertEquals(false, a.m.get("allowsprint"));
        assertEquals(false, a.m.get("allowparkour"));
        p.conserve(true, true, a);                       // fleeing: sprint stays allowed, parkour off
        assertEquals(true, a.m.get("allowsprint"));
        assertEquals(false, a.m.get("allowparkour"));
        p.conserve(false, false, a);
        assertEquals(true, a.m.get("allowsprint"));
        assertEquals(true, a.m.get("allowparkour"));
        assertFalse(p.conserving());
    }

    @Test void profileSaveKeepsOriginals() {
        Profiles p = new Profiles();
        Fake a = new Fake();
        p.conserve(true, false, a);                      // settings now false
        p.apply(Profiles.Profile.WALK, a);               // saves the originals, not the conserved false
        p.conserve(true, false, a);
        assertEquals(false, a.m.get("allowsprint"));
        p.conserve(false, false, a);
        p.apply(Profiles.Profile.NONE, a);
        assertEquals(true, a.m.get("allowsprint"));
        assertEquals(true, a.m.get("allowparkour"));
    }
}
