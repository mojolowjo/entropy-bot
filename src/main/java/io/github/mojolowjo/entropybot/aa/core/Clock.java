package io.github.mojolowjo.entropybot.aa.core;

/** AA v0: injected time (ms), so the nodes run in JUnit without a game. */
@FunctionalInterface
public interface Clock {
    long now();

    /** A test clock that moves only when told. */
    final class Fake implements Clock {
        public long t;

        public Fake(long start) { t = start; }

        @Override public long now() { return t; }

        public void advance(long ms) { t += ms; }
    }
}
