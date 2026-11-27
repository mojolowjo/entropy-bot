/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's QueuePump (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import java.util.ArrayList;
import java.util.List;

/**
 * Entropy z58 (run Q 02:29:32-02:33:13: all slots held by re-searches while the bot explored): one tick of the pricing queue,
 * the loop PricedPath runs once a tick (z58 review 6: pulled out of PricedPath so its tests drive this very code). Every queued
 * ask that may start does (highest class first): a goal the bot already stands in leaves the queue (its next ask answers 0),
 * a goal whose own chunk is not loaded keeps that answer without a search ({@link Starter#start} false); while a due ask has
 * no slot for its class, a running re-search / rest search is cancelled for it (never the running step's). The cancelled
 * searches' askers are answered "queued" only after the loop, so their re-asks find the slots already taken by the higher
 * class and queue again (rule 15: computing, never a price). Pure (JUnit: PathQueueTest). Game thread only.
 */
public final class QueuePump {
    private QueuePump() {}

    /** The caller's side: the world and the searches. */
    public interface Starter<K, R> {
        /** The bot stands in the goal now (no search: the next ask answers 0). */
        boolean inGoal(K goal);

        /** Starts the goal's search (registered with {@link PathMemory#started}); false: an answer was kept without a search. */
        boolean start(K goal);

        /** Stops a running search (its landing is ignored: the memory no longer holds its handle). */
        void cancel(R handle);

        /** Answers the cancelled search's askers "queued" (they ask again). */
        void answerQueued(R handle);
    }

    /** structural: the loop's bound - about one start and one cancel per slot per tick (64 caps a "no limit" maxRunning) */
    static int bound(IPricedPath.Limits lim) {
        return 2 * Math.min(lim.maxRunning(), 64) + 2;
    }

    /** One tick of the queue; returns the answers that came without a search for an asker (the mod re-prices on them). */
    public static <K, R, S> int pump(PathMemory<K, R, S> m, long tick, IPricedPath.Limits lim, Starter<K, R> s) {
        List<R> cancelled = new ArrayList<>();
        int noSearch = 0;
        for (int guard = 0; guard < bound(lim); guard++) {
            K g = m.nextToStart(tick, lim);
            if (g != null) {
                if (s.inGoal(g)) {
                    m.unqueue(g);
                    noSearch++;
                } else if (!s.start(g)) {
                    noSearch++;                  // "chunks not loaded" kept: the mod asks again and reads it
                }
                continue;
            }
            R victim = m.cancelForPriority(tick, lim);
            if (victim == null) {
                break;
            }
            s.cancel(victim);
            cancelled.add(victim);
        }
        for (R r : cancelled) {
            s.answerQueued(r);
        }
        return noSearch;
    }
}
