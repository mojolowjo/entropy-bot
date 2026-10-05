package io.github.mojolowjo.entropybot.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoutePoolTest {

    @Test
    void threadsAreDaemonMinPriorityAndNamed() {
        RoutePool p = new RoutePool(3, "entropybot-route", (w, t) -> {});
        try {
            assertEquals(3, p.threads().size());
            for (int i = 0; i < 3; i++) {
                Thread t = p.threads().get(i);
                assertTrue(t.isDaemon());
                assertEquals(Thread.MIN_PRIORITY, t.getPriority());
                assertEquals("entropybot-route-" + (i + 1), t.getName());
            }
        } finally {
            assertTrue(p.stop(2000));
        }
    }

    @Test
    void neverTakesMoreThanItsThreads() throws Exception {
        RoutePool p = new RoutePool(2, "t", (w, t) -> {});
        CountDownLatch hold = new CountDownLatch(1);
        try {
            assertTrue(p.submit(() -> await(hold)));
            assertTrue(p.submit(() -> await(hold)));
            assertEquals(0, p.free());
            assertFalse(p.submit(() -> {}), "a third task must be refused, not queued");
            hold.countDown();
            waitFree(p, 2);
            assertEquals(2, p.free());
        } finally {
            p.stop(2000);
        }
    }

    @Test
    void exceptionsReachTheSinkAndTheThreadLives() throws Exception {
        List<String> errors = new CopyOnWriteArrayList<>();
        RoutePool p = new RoutePool(1, "t", (w, t) -> errors.add(w + " " + t.getMessage()));
        try {
            assertTrue(p.submit(() -> { throw new IllegalStateException("boom"); }));
            waitFree(p, 1);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("boom"));
            CountDownLatch ran = new CountDownLatch(1);
            assertTrue(p.submit(ran::countDown));
            assertTrue(ran.await(2, TimeUnit.SECONDS), "the worker must survive an exception");
        } finally {
            p.stop(2000);
        }
    }

    @Test
    void stopsCleanlyWithABusyWorker() {
        RoutePool p = new RoutePool(3, "t", (w, t) -> {});
        // one interruptible, one that ignores interrupts for a while
        p.submit(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
            }
        });
        p.submit(() -> {
            long end = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < end) Thread.onSpinWait();
        });
        long t0 = System.currentTimeMillis();
        assertTrue(p.stop(2000), "every thread must end");
        assertTrue(System.currentTimeMillis() - t0 < 2000);
        for (Thread t : p.threads()) assertFalse(t.isAlive());
        assertFalse(p.submit(() -> {}), "no work after stop");
        assertEquals(0, p.free());
    }

    @Test
    void rateGate() {
        RateGate g = new RateGate(3, 1000);
        assertTrue(g.take(0));
        assertTrue(g.take(10));
        assertTrue(g.take(20));
        assertFalse(g.take(30));
        assertFalse(g.available(999));
        assertTrue(g.take(1000));
        assertFalse(g.take(1005));
    }

    static void await(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    static void waitFree(RoutePool p, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (p.free() < n && System.currentTimeMillis() < end) Thread.sleep(5);
    }
}
