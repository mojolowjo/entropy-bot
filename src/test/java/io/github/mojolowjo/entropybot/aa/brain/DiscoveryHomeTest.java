package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owner 2026-10-20 (no explore leash): the explore row prices the way home, night-weighted, within the horizon. */
class DiscoveryHomeTest {
    static Discovery.Facts h(double home, double horizon, long day) {
        return new Discovery.Facts(-1, 1, false, 100, 70, 200, 16, 0.002, 0.4, false, null, null, 1, home, horizon, day);
    }

    /** The explore row (the strip row stands beside it: the world is open), or null. */
    static Discovery.Opt explore(List<Discovery.Opt> r) {
        return r.stream().filter(o -> o.kind() == Discovery.Kind.EXPLORE).findFirst().orElse(null);
    }

    @Test
    void theWayHomeIsPricedAndNightWeighted() {
        List<String> dd = new ArrayList<>();
        double near = explore(Discovery.rank(h(0, 0, 1000), "iron", 3, dd)).cost();
        double far = explore(Discovery.rank(h(200, 0, 1000), "iron", 3, dd)).cost();
        assertEquals(200, far - near, 1e-6);
        double dusk = explore(Discovery.rank(h(200, 0, 12000), "iron", 3, dd)).cost();
        assertEquals(2 * (far - near + 100) - 100, dusk - near, 1e-6, "dusk doubles the way home");
        assertTrue(explore(Discovery.rank(h(200, 0, 12000), "iron", 3, dd)).why().contains("night"));
        assertEquals(3, Discovery.nightWeight(15000));
        assertEquals(1, Discovery.nightWeight(-1));
        // past the horizon: dropped with the reason
        dd.clear();
        assertNull(explore(Discovery.rank(h(2000, 1000, 1000), "iron", 3, dd)));
        assertTrue(dd.stream().anyMatch(d -> d.contains("past the horizon")), dd.toString());
    }
}
