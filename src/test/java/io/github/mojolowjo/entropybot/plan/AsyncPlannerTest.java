package io.github.mojolowjo.entropybot.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.1: the planner's search on a worker; the answer comes back only through drain (the client tick). */
class AsyncPlannerTest {

    @Test
    void theSearchRunsOffTheCallerAndTheAnswerComesThroughDrain() throws Exception {
        AsyncPlanner a = new AsyncPlanner();
        Thread caller = Thread.currentThread();
        CountDownLatch go = new CountDownLatch(1), ran = new CountDownLatch(1);
        Thread[] searchThread = new Thread[1];
        List<String> got = new ArrayList<>();
        assertTrue(a.submit("goal:bed", () -> {
            searchThread[0] = Thread.currentThread();
            try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            ran.countDown();
            return "goal_bed";
        }, d -> got.add(Thread.currentThread() == caller ? d.result() : "wrong thread")));
        assertFalse(a.submit("goal:bed", () -> "again", d -> got.add("twice")), "one search per key");
        assertTrue(a.busy("goal:bed"));
        assertEquals(0, a.drain(), "nothing yet: the caller is never blocked");
        go.countDown();
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        long until = System.currentTimeMillis() + 5000;
        while (a.drain() == 0 && System.currentTimeMillis() < until) Thread.sleep(5);
        assertNotSame(caller, searchThread[0], "the search ran on the worker");
        assertEquals(List.of("goal_bed"), got, "the callback ran on the draining thread");
        assertFalse(a.busy("goal:bed"));
    }

    @Test
    void aSearchThatThrowsIsDeliveredAsAnError() throws Exception {
        AsyncPlanner a = new AsyncPlanner();
        List<String> got = new ArrayList<>();
        a.<String>submit("x", () -> { throw new IllegalStateException("boom"); }, d -> got.add(d.result() == null ? d.error() : d.result()));
        long until = System.currentTimeMillis() + 5000;
        while (a.drain() == 0 && System.currentTimeMillis() < until) Thread.sleep(5);
        assertEquals(1, got.size());
        assertTrue(got.get(0).contains("boom"), got.toString());
        // a callback that throws is caught and counted
        a.submit("y", () -> "ok", d -> { throw new RuntimeException("cb"); });
        until = System.currentTimeMillis() + 5000;
        while (a.drain() == 0 && System.currentTimeMillis() < until) Thread.sleep(5);
        assertEquals(1, a.callbackErrors());
    }
}
