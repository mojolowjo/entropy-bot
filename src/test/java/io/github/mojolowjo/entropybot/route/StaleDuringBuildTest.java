package io.github.mojolowjo.entropybot.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Review S3: a block change while a box is being built leaves the stored box stale. */
class StaleDuringBuildTest {
    static final SectionKey K = new SectionKey(0, 1, 4, 1);

    static SectionRecord rec(SectionKey k) {
        return new SectionRecord(k, SectionRecord.Quality.LIVE, 1, 7, List.of(), new char[0]);
    }

    @Test
    void aChangeDuringTheFirstBuildKeepsTheBoxStale() {
        RouteStore s = RouteCore.memoryStore();
        long stamp = s.beginBuild(K);
        assertNull(s.get(K));
        assertTrue(s.noteChangeWhileBuilding(K), "the box isn't stored yet, but its build is running");
        s.put(rec(K), stamp);
        assertNotNull(s.get(K));
        assertTrue(s.isStale(K), "built from terrain older than the change");
    }

    @Test
    void aChangeDuringARebuildOfAStaleBoxKeepsItStale() {
        RouteStore s = RouteCore.memoryStore();
        s.put(rec(K));
        s.markStale(K);
        long stamp = s.beginBuild(K);
        s.markStale(K);                // already stale: still noted for the build
        s.put(rec(K), stamp);
        assertTrue(s.isStale(K));
    }

    @Test
    void noChangeMeansFreshAndNothingLingers() {
        RouteStore s = RouteCore.memoryStore();
        s.put(rec(K));
        s.markStale(K);
        long stamp = s.beginBuild(K);
        s.put(rec(K), stamp);
        assertFalse(s.isStale(K), "a clean rebuild clears the mark");
        assertFalse(s.noteChangeWhileBuilding(K), "no build in flight any more");
        long again = s.beginBuild(K);
        s.put(rec(K), again);
        assertFalse(s.isStale(K), "an old change stamp doesn't leak into the next build");
    }

    @Test
    void aChangeBeforeTheBuildStartedDoesNotCount() {
        RouteStore s = RouteCore.memoryStore();
        s.put(rec(K));
        s.markStale(K);
        long stamp = s.beginBuild(K);
        s.put(rec(K), stamp);
        assertFalse(s.isStale(K));
    }

    @Test
    void aSettingsChangeDuringABuildCountsToo() {
        RouteStore s = RouteCore.memoryStore();
        long stamp = s.beginBuild(K);
        s.markAllStale();
        s.put(rec(K), stamp);
        assertTrue(s.isStale(K));
    }

    @Test
    void anEndedBuildForgetsItsChanges() {
        RouteStore s = RouteCore.memoryStore();
        long stamp = s.beginBuild(K);
        assertTrue(s.noteChangeWhileBuilding(K));
        s.endBuild(K, stamp);
        assertFalse(s.noteChangeWhileBuilding(K));
        long next = s.beginBuild(K);
        s.put(rec(K), next);
        assertFalse(s.isStale(K));
    }

    @Test
    void twoBuildsOfOneBoxEachSeeTheirOwnChanges() {
        RouteStore s = RouteCore.memoryStore();
        long a = s.beginBuild(K);
        s.noteChangeWhileBuilding(K);
        long b = s.beginBuild(K);      // a newer build, after the change
        s.put(rec(K), a);
        assertTrue(s.isStale(K), "the older build missed the change");
        s.put(rec(K), b);
        assertFalse(s.isStale(K), "the newer build saw it");
    }
}
