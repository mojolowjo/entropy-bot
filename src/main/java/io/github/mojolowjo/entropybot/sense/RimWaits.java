package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * job-planner-stick, review round 3 (rule 15): the chunks whose ravine rim ({@link CaveIndex#rimmed}) was read while a
 * neighbour chunk was not loaded. For those the wall search ended at the border on "not known", which is not "no wall":
 * a chunk read before its neighbour kept its floor columns as open ground for as long as it stayed loaded, and the same
 * ravine was indexed differently by load order. Here the chunk WAITS on each neighbour it could not read; when that
 * neighbour arrives the chunk is due for one more rim read (ChunkPass.rimAgain). Bounded: one re-read per neighbour
 * that arrives, eight neighbours at most, and a wait is only set again by a read that still finds a neighbour missing.
 * Keys are {@link CaveIndex#chunkKey}. Pure; the game thread only.
 */
final class RimWaits {
    /** Neighbour not loaded -> the chunks whose rim read ended on it. */
    private final Map<Long, Set<Long>> byNeighbour = new HashMap<>();
    /** The chunks due for their rim read again, oldest first. */
    private final LinkedHashSet<Long> due = new LinkedHashSet<>();
    /** Counts for "sense status": rim reads that ended on a neighbour not loaded, re-reads done, re-reads that changed the ground. */
    int waited, reread, changed;

    /** {@code chunk}'s rim read found these neighbours not loaded: it waits on each. */
    void waiting(long chunk, Collection<Long> missing) {
        if (missing.isEmpty()) return;
        waited++;
        for (long n : missing) byNeighbour.computeIfAbsent(n, k -> new HashSet<>()).add(chunk);
    }

    /** {@code neighbour} is loaded now: every chunk that waited on it is due for its rim read again. */
    void arrived(long neighbour) {
        Set<Long> w = byNeighbour.remove(neighbour);
        if (w != null) due.addAll(w);
    }

    /** {@code chunk} unloaded: it waits on nothing and is due for nothing (its next load is a first read). */
    void gone(long chunk) {
        due.remove(chunk);
        for (Iterator<Set<Long>> it = byNeighbour.values().iterator(); it.hasNext(); ) {
            Set<Long> s = it.next();
            s.remove(chunk);
            if (s.isEmpty()) it.remove();
        }
    }

    /** Up to {@code n} chunks due, oldest first; they leave the due list. */
    List<Long> take(int n) {
        List<Long> out = new ArrayList<>();
        for (Iterator<Long> it = due.iterator(); it.hasNext() && out.size() < n; ) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    /** Back on the due list (the tick's budget ran out before its turn). */
    void again(long chunk) { due.add(chunk); }

    int waitingChunks() {
        Set<Long> all = new HashSet<>();
        for (Set<Long> s : byNeighbour.values()) all.addAll(s);
        return all.size();
    }

    int due() { return due.size(); }

    /** A new world or dimension: nothing waits, the counts and the once-said line start again. */
    void clear() {
        byNeighbour.clear();
        due.clear();
        waited = reread = changed = 0;
    }

    String status() {
        return "ravine rims: " + waitingChunks() + " chunk(s) wait on a neighbour not loaded, " + due.size() + " due, " + reread + " read again (" + changed + " changed), "
                + waited + " read(s) ended on a neighbour not loaded";
    }
}
