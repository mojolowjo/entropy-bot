package io.github.mojolowjo.entropybot.route;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** The {@link BuildQueue}: one FIFO per priority, a key held once at its most urgent priority. */
final class PriorityBuildQueue implements BuildQueue {
    private final Map<SectionKey, Priority> where = new HashMap<>();
    private final EnumMap<Priority, ArrayDeque<SectionKey>> lanes = new EnumMap<>(Priority.class);
    private final int[] live = new int[Priority.values().length];

    PriorityBuildQueue() {
        for (Priority p : Priority.values()) lanes.put(p, new ArrayDeque<>());
    }

    @Override
    public synchronized boolean offer(SectionKey key, Priority p) {
        Priority cur = where.get(key);
        if (cur != null && cur.ordinal() <= p.ordinal()) return false;
        if (cur != null) live[cur.ordinal()]--; // the old entry stays in its lane and is skipped on poll
        where.put(key, p);
        lanes.get(p).addLast(key);
        live[p.ordinal()]++;
        return true;
    }

    @Override
    public synchronized SectionKey poll(boolean idleAllowed) {
        for (Priority p : Priority.values()) {
            if (p != Priority.NOW && !idleAllowed) return null;
            ArrayDeque<SectionKey> lane = lanes.get(p);
            SectionKey k;
            while ((k = lane.pollFirst()) != null) {
                if (where.get(k) == p) {
                    where.remove(k);
                    live[p.ordinal()]--;
                    return k;
                }
            }
        }
        return null;
    }

    @Override
    public synchronized int nowSize() {
        return live[Priority.NOW.ordinal()];
    }

    @Override
    public synchronized int idleSize() {
        return live[1] + live[2] + live[3];
    }

    @Override
    public synchronized void clearIdle() {
        for (Priority p : Priority.values()) {
            if (p == Priority.NOW) continue;
            lanes.get(p).clear();
            live[p.ordinal()] = 0;
        }
        where.values().removeIf(p -> p != Priority.NOW);
    }
}
