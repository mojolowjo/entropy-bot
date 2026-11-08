package io.github.mojolowjo.entropybot.aa.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** job-tick2: the item data build runs on its worker (a fake executor here) and is handed to the tick once. */
class OffThreadBuildTest {
    /** A worker that runs its jobs only when told. */
    static final class FakeWorker implements java.util.concurrent.Executor {
        final List<Runnable> jobs = new ArrayList<>();
        @Override public void execute(Runnable r) { jobs.add(r); }
        void runAll() { List<Runnable> l = new ArrayList<>(jobs); jobs.clear(); l.forEach(Runnable::run); }
    }

    @Test
    void buildsOffTheTickAndPublishesOnce() {
        FakeWorker w = new FakeWorker();
        OffThreadBuild<String> b = new OffThreadBuild<>(w);
        AtomicInteger built = new AtomicInteger();
        assertTrue(b.start("k", () -> { built.incrementAndGet(); return "tree"; }, 0));
        assertEquals(0, built.get(), "start never builds on the caller's thread");
        assertFalse(b.start("k", () -> "again", 5), "a build of the same key is not started twice");
        assertNull(b.poll("k"));
        assertTrue(b.building());
        assertTrue(b.building("k"), "job-tick3: the key being built");
        assertFalse(b.building("other"));
        w.runAll();
        var d = b.poll("k");
        assertNotNull(d);
        assertEquals("tree", d.value());
        assertNull(b.poll("k"), "handed over once");
        assertFalse(b.building());
        assertEquals(1, built.get());
    }

    @Test
    void anOldKeysResultIsDropped() {
        FakeWorker w = new FakeWorker();
        OffThreadBuild<String> b = new OffThreadBuild<>(w);
        b.start("old", () -> "old tree", 0);
        w.runAll();
        assertNull(b.poll("new"));
    }

    @Test
    void anErrorComesBack() {
        FakeWorker w = new FakeWorker();
        OffThreadBuild<String> b = new OffThreadBuild<>(w);
        b.start("k", () -> { throw new IllegalStateException("boom"); }, 0);
        w.runAll();
        var d = b.poll("k");
        assertNull(d.value());
        assertInstanceOf(IllegalStateException.class, d.error());
    }

    @Test
    void theBeingBuiltLineIsSaidOnce() {
        FakeWorker w = new FakeWorker();
        OffThreadBuild<String> b = new OffThreadBuild<>(w);
        assertNull(b.buildingLine(0));
        b.start("k", () -> "t", 100);
        assertEquals("item data: the tech tree is being built (40 ms so far)", b.buildingLine(140));
        assertNull(b.buildingLine(200));
    }
}
