package io.github.mojolowjo.entropybot.vocab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.commands.OldWords;
import io.github.mojolowjo.entropybot.commands.Texts;
import io.github.mojolowjo.entropybot.commands.VerbTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** V1b-4 (0.22.2): the companion and brain verbs back on the surface, camp here's free name, area candidates. */
class V1bFourTest {
    static final List<String> RESTORED = List.of("why", "deaths", "resume", "death", "corpse", "hold", "give", "carry", "unload", "restock",
            "say", "twerk", "spawn", "trust", "untrust");

    @Test
    void restoredVerbsAreOnTheSurface() {
        for (String v : RESTORED) {
            assertNotNull(VerbTable.of(v), v + " has a row");
            assertTrue(Texts.BUILTIN_VERBS.contains(v), v);
            assertFalse(OldWords.DEBUG_VERBS.contains(v), v + " is no debug verb now");
            assertNull(OldWords.removedAnswer(v, "", v), v + " runs, no hint");
        }
        assertEquals("stocking", VerbTable.of("hold").section());
        assertEquals("jobs", VerbTable.of("why").section());
        assertEquals("chests", VerbTable.of("trust").section());
        assertEquals("moving", VerbTable.of("corpse").section());
        assertNull(OldWords.removedAnswer("hold", "this", "hold this"));
        assertNull(OldWords.removedAnswer("give", "me dirt 4", "give me dirt 4"));
        assertEquals("that is now spawn", OldWords.removedAnswer("bed", "", "bed"));
        for (String v : List.of("b", "memory", "watch", "recorder", "mouse", "route", "autominer", "reconnect", "use", "caves", "ores", "poi"))
            assertTrue(OldWords.DEBUG_VERBS.contains(v), v + " stays under debug");
        assertFalse(VerbTable.of("debug").usage().contains("hold"), "debug no longer lists hold");
    }

    @Test
    void guestsKeepHoldWhyAndDeaths() {
        assertNull(Texts.guestRefusal("hold", "this", "hold this", "o"));
        assertNull(Texts.guestRefusal("why", "", "why", "o"));
        assertNull(Texts.guestRefusal("deaths", "", "deaths", "o"));
        assertNotNull(Texts.guestRefusal("give", "me dirt 4", "give me dirt 4", "o"));
        assertNotNull(Texts.guestRefusal("deaths", "policy off", "deaths policy off", "o"));
    }

    @Test
    void campNeverReusesAnArea() {
        assertEquals("camp", NeedWords.campName(n -> false));
        assertEquals("camp2", NeedWords.campName(Set.of("camp")::contains));
        assertEquals("camp3", NeedWords.campName(Set.of("camp", "camp2")::contains));
        assertNull(NeedWords.campName(n -> true));
    }

    @Test
    void areaCandidates() {
        JsonArray l = new JsonArray();
        assertTrue(AreaCandidates.list(l).startsWith("no safe-area candidates"));
        JsonObject o = new JsonObject();
        o.addProperty("dim", "minecraft:overworld");
        o.addProperty("x", 100);
        o.addProperty("y", 64);
        o.addProperty("z", -20);
        o.addProperty("built", 9);
        o.addProperty("blockEntities", 2);
        o.addProperty("named", true);
        l.add(o);
        assertTrue(AreaCandidates.list(l).contains("1. 100 64 -20 (overworld): 9 built, 2 block entities, a named mob"), AreaCandidates.list(l));
        var a = AreaCandidates.plan(l, List.of("accept", "1", "alex"));
        assertEquals("84 -36 116 -4 alex safe 56 80", a.areaCommand());
        assertEquals(0, a.remove());
        assertEquals("84 -36 116 -4 found1 safe 56 80", AreaCandidates.plan(l, List.of("accept", "1")).areaCommand());
        var r = AreaCandidates.plan(l, List.of("reject", "1"));
        assertNull(r.areaCommand());
        assertEquals(0, r.remove());
        assertEquals(-2, AreaCandidates.plan(l, List.of("reject", "all")).remove());
        assertTrue(AreaCandidates.plan(l, List.of("accept", "2")).text().startsWith("error: no candidate 2"));
        assertEquals(AreaCandidates.USAGE, AreaCandidates.plan(l, List.of("take", "1")).text());
        assertEquals(-1, AreaCandidates.plan(l, List.of()).remove());
    }
}
