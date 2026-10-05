package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.route.RouteStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRulesTest {
    static RouteStats stats(long errors, long timeouts, long nopath, long stuck, String last) {
        return new RouteStats(0, 0, 0, 0, errors, timeouts, nopath, stuck, 0, 0, 0, last);
    }

    @Test
    void onlyTestIsAJob() {
        assertTrue(RouteRules.isTest("test base farm 6"));
        assertTrue(RouteRules.isTest(" TEST base farm"));
        assertFalse(RouteRules.isTest("status"));
        assertFalse(RouteRules.isTest(""));
        assertFalse(RouteRules.isTest(null));
        assertFalse(RouteRules.isTest("testing"));
    }

    @Test
    void testArgs() {
        RouteRules.TestArgs a = RouteRules.testArgs("test Base farm 12");
        assertNull(a.error());
        assertEquals("base", a.a());
        assertEquals("farm", a.b());
        assertEquals(12, a.trips());
        assertEquals(RouteRules.DEFAULT_TRIPS, RouteRules.testArgs("test base farm").trips());
        assertTrue(RouteRules.testArgs("test base").error().startsWith("usage"));
        assertTrue(RouteRules.testArgs("test").error().startsWith("usage"));
        assertTrue(RouteRules.testArgs("test base farm x").error().startsWith("usage"));
        assertTrue(RouteRules.testArgs("test base farm 0").error().contains("1-30"));
        assertTrue(RouteRules.testArgs("test base farm 31").error().contains("1-30"));
        assertTrue(RouteRules.testArgs("test base base 6").error().contains("differ"));
    }

    @Test
    void coords() {
        assertArrayEquals(new int[]{-26, 53, 187}, RouteRules.coords("-26 53 187"));
        assertNull(RouteRules.coords("base"));
        assertNull(RouteRules.coords("1 2"));
    }

    @Test
    void statusText() {
        String s = RouteRules.status(true, "goal", false, 3, 7, "plain: short walk", stats(0, 1, 2, 0, ""), stats(0, 0, 0, 0, ""));
        assertTrue(s.startsWith("route: on, mode goal, planner not running (every walk is plain)"), s);
        assertTrue(s.contains("walks: 3 asked the planner, 7 plain; planning timeouts 1, fallbacks nopath 2 stuck 0"), s);
        assertTrue(s.contains("last walk: plain: short walk"));
        assertTrue(s.contains("\nplanner: boxes 0"));
        String e = RouteRules.status(false, "legs", true, 0, 0, null, stats(2, 0, 0, 0, "route begin: x"), null);
        assertTrue(e.startsWith("route: off, mode legs, planner running"));
        assertTrue(e.contains("errors 2 (last: route begin: x)"));
        assertTrue(e.contains("last walk: none yet"));
        assertFalse(e.contains("planner: boxes"));
    }

    @Test
    void checkFindings() {
        assertTrue(RouteRules.check(false, false, stats(0, 0, 0, 0, ""), null).isEmpty(), "off: nothing to say");
        List<SelfCheck.Finding> f = RouteRules.check(true, false, stats(0, 0, 0, 0, ""), null);
        assertEquals(1, f.size());
        assertEquals("route", f.get(0).key());
        assertTrue(RouteRules.check(true, true, stats(0, 0, 0, 0, ""), stats(0, 0, 0, 0, "")).isEmpty());
        List<SelfCheck.Finding> g = RouteRules.check(true, true, stats(0, 0, 3, 2, ""), stats(1, 0, 0, 0, "worker: boom"));
        assertEquals(2, g.size());
        assertEquals("routeerrors", g.get(0).key());
        assertTrue(g.get(0).text().contains("1 error (last: worker: boom)"), g.get(0).text());
        assertEquals("routefallbacks", g.get(1).key());
    }

    @Test
    void checkReportsTheHookAndOnlyRealOutages() {
        RouteStats z = stats(0, 0, 0, 0, "");
        // review M2 (a): the hook not applied, said even with routing off
        List<SelfCheck.Finding> f = RouteRules.check(false, false, z, null, new RouteRules.MapHealth(false, 0, 0, false, "the route map is off"));
        assertEquals(1, f.size());
        assertEquals("routehook", f.get(0).key());
        // (b): in but silent after enough Baritone events
        f = RouteRules.check(true, true, z, z, new RouteRules.MapHealth(true, 0, RouteRules.SILENT_HOOK_EVENTS, true, null));
        assertEquals(1, f.size());
        assertEquals("routehooksilent", f.get(0).key());
        assertTrue(RouteRules.check(true, true, z, z, new RouteRules.MapHealth(true, 0, RouteRules.SILENT_HOOK_EVENTS - 1, true, null)).isEmpty(),
                "too few events to tell");
        assertTrue(RouteRules.check(true, true, z, z, new RouteRules.MapHealth(true, 3, 500, true, null)).isEmpty(), "the hook speaks");
        // the false positive: not running in the Nether, during a mine job, while loading = no finding
        assertTrue(RouteRules.check(true, false, z, null, new RouteRules.MapHealth(true, 0, 0, false, "breaking or placing is on")).isEmpty());
        f = RouteRules.check(true, false, z, null, new RouteRules.MapHealth(true, 0, 0, true, "route core not built: x"));
        assertEquals(1, f.size());
        assertEquals("route", f.get(0).key());
        assertTrue(f.get(0).text().contains("(route core not built: x)"), f.get(0).text());
    }

    @Test
    void routeBuildIsCappedAt2000Blocks() {
        assertNull(RouteRules.buildTooFar(new int[]{0, 64, 0}, new int[]{1999, 64, 0}, 2000));
        String s = RouteRules.buildTooFar(new int[]{0, 64, 0}, new int[]{3000, 64, 4000}, 2000);
        assertTrue(s.startsWith("error: that is 5000 blocks away; route build covers at most 2000"), s);
    }

    @Test
    void verbIsDocumented() {
        VerbTable.Verb v = VerbTable.of("route");
        assertTrue(v != null && v.who() == VerbTable.Who.OWNER);
        assertTrue(v.usage().contains("route test <placeA> <placeB> [trips]"));
        assertTrue(Texts.BUILTIN_VERBS.contains("route"));
        assertTrue(Texts.MOD_JOB_VERBS.contains("route"));
        assertFalse(Texts.GUEST_VERBS.contains("route"));
    }
}
