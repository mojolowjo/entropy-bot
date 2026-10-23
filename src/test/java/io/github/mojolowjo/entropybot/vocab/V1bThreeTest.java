package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.commands.OldWords;
import io.github.mojolowjo.entropybot.commands.Texts;
import io.github.mojolowjo.entropybot.commands.VerbTable;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** V1b-3 (0.22.2): explore/find, base detection, needs and goals, done, sleep auto, and the hard cut of the verb surface. */
class V1bThreeTest {
    @Test
    void exploreArgsAndChunkChoice() {
        assertEquals("north", ExploreWords.parse("north 10", 5).dir());
        assertEquals(10, ExploreWords.parse("north 10", 5).minutes());
        assertEquals(30, ExploreWords.parse("99", 5).minutes(), "capped at 30");
        assertNull(ExploreWords.parse("", 5).dir());
        assertNotNull(ExploreWords.parse("up", 5).error());
        Set<String> seen = new HashSet<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) seen.add(x + " " + z);
        ExploreWords.BiPred s = (x, z) -> seen.contains(x + " " + z), none = (x, z) -> false;
        int[] any = ExploreWords.next(0, 0, 0, 0, null, s, none);
        assertEquals(2, Math.max(Math.abs(any[0]), Math.abs(any[1])), "the nearest never-seen ring");
        int[] north = ExploreWords.next(0, 0, 0, 0, "north", s, none);
        assertTrue(north[1] < 0 && Math.abs(north[0]) <= -north[1], "north = -z, in the cone: " + north[0] + " " + north[1]);
        int[] east = ExploreWords.next(0, 0, 0, 0, "east", (x, z) -> false, none);
        assertEquals(1, east[0], "east = +x, one ring out");
        assertNull(ExploreWords.next(0, 0, 0, 0, null, (x, z) -> true, none), "everything seen: nothing to do");
        int[] notRefused = ExploreWords.next(0, 0, 0, 0, "south", (x, z) -> false, (x, z) -> x == 0 && z == 1);
        assertFalse(notRefused[0] == 0 && notRefused[1] == 1, "a refused chunk (a safe area) is skipped");
    }

    @Test
    void findForms() {
        List<String> kinds = List.of("village", "mineshaft", "trial chamber");
        java.util.function.Predicate<String> biome = id -> id.equals("minecraft:cherry_grove");
        assertEquals(ExploreWords.FindKind.CAVE, ExploreWords.find("cave", kinds, biome).kind());
        assertEquals(ExploreWords.FindKind.POI, ExploreWords.find("village", kinds, biome).kind());
        assertEquals("trial chamber", ExploreWords.find("trial_chamber 20", kinds, biome).what());
        assertEquals(20, ExploreWords.find("trial_chamber 20", kinds, biome).minutes());
        assertEquals("minecraft:cherry_grove", ExploreWords.find("cherry_grove", kinds, biome).what());
        assertEquals(ExploreWords.FindKind.BLOCK, ExploreWords.find("chest", kinds, biome).kind(), "a block stays the nearby list");
        assertTrue(ExploreWords.isCave(40));
        assertFalse(ExploreWords.isCave(39));
    }

    @Test
    void needsGoalsRelease() {
        assertArrayEquals(new String[]{"torch", "64"}, NeedWords.standing("torch 64"));
        assertNull(NeedWords.standing("refinedstorage:basic_processor"), "no count: the old answer");
        assertTrue(NeedWords.list(Map.of("minecraft:torch", 64), Map.of("minecraft:torch", 70)).contains("torch 70/64 (met)"));
        assertTrue(NeedWords.list(Map.of(), Map.of()).startsWith("no standing needs"));
        assertEquals("camp here", NeedWords.goal("camp", n -> true).chain());
        assertEquals("craft stone tools", NeedWords.goal("Stone  Tools", n -> true).chain());
        assertEquals("gather iron_ingot 16", NeedWords.goal("iron 16", n -> true).chain());
        assertEquals("gather food 8", NeedWords.goal("food 8", n -> true).chain());
        assertEquals("light yard", NeedWords.goal("light yard", n -> n.equals("yard")).chain());
        assertTrue(NeedWords.goal("light nowhere", n -> false).error().contains("no area called nowhere"));
        assertTrue(NeedWords.goal("build a castle", n -> true).error().startsWith("I can't plan that yet"));
        assertTrue(NeedWords.liftsRelease("come"));
        assertFalse(NeedWords.liftsRelease("status"), "looking doesn't take it back");
        assertEquals(Boolean.FALSE, NeedWords.sleepAuto("auto off"));
        assertNull(NeedWords.sleepAuto("status"));
    }

    @Test
    void theHardCut() {
        // removed words answer with the new form, never run
        for (String w : List.of("mark", "setbase", "chop", "base")) assertTrue(OldWords.removedAnswer(w, "", w).contains("was deleted"), w);
        assertTrue(OldWords.removedAnswer("stripmine", "3 16", "stripmine 3 16").contains("aa want strip"));
        assertTrue(OldWords.removedAnswer("defend", "on", "defend on").startsWith("that is now defence on"));
        assertNull(OldWords.removedAnswer("defend", "", "defend"), "defend alone is the new job");
        assertEquals("that is now tools mode cheapest", OldWords.removedAnswer("tools", "ores cheapest", "tools ores cheapest"));

        assertEquals("removed in 0.22; use debug autominer on", OldWords.removedAnswer("autominer", "on", "autominer on"));
        assertEquals("removed in 0.22; use debug b set allowSprint true", OldWords.removedAnswer("baritone", "set allowSprint true", "baritone set allowSprint true"));
        assertEquals("that is now have iron", OldWords.removedAnswer("where", "iron", "where iron"));
        assertTrue(OldWords.removedAnswer("deposit", "", "deposit then base").contains("was deleted"), "a chain with an old step");
        assertTrue(OldWords.removedAnswer("routine", "save n chop 16 then deposit", "routine save n chop 16 then deposit").contains("was deleted"), "inside routine save");
        assertNull(OldWords.removedAnswer("routine", "save n cut 16 then deposit", "routine save n cut 16 then deposit"));
        // the surface: exactly the table, no removed word in it, every removed word reserved
        for (String w : OldWords.REMOVED) assertNull(VerbTable.of(w), "removed but still in the table: " + w);
        for (String w : Texts.BUILTIN_VERBS) assertNotNull(VerbTable.of(w), "surface word not in the table: " + w);
        for (VerbTable.Verb v : VerbTable.all()) assertTrue(Texts.BUILTIN_VERBS.contains(v.name()), "table verb not in the surface list: " + v.name());
        for (String w : List.of("dismiss", "done", "free", "defence", "defend", "guard", "queue", "kinds", "tools", "needs", "goals",
                "camp", "marker", "places", "find")) assertNotNull(VerbTable.of(w), w);
        // guests: the list the owner gave
        for (String g : List.of("status", "inv", "help", "queue", "places", "have", "dismiss")) assertNull(Texts.guestRefusal(g, "", g, "o"), g);
        assertNull(Texts.guestRefusal("find", "chest", "find chest", "o"));
        assertNull(Texts.guestRefusal("area", "list", "area list", "o"));
        assertNull(Texts.guestRefusal("fence", "", "fence", "o"));
        for (String g : List.of("stock", "recipe", "routines", "rules")) assertNotNull(Texts.guestRefusal(g, "", g, "o"), g);
    }
}
