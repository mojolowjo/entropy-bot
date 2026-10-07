package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.route.Cell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.23.3 movement, "plan while idle": a first leg kept ready from where the bot stands to the likely destinations
 * (home, base, mine, farm, the owner's last spot, the last 5 goals, a chain's next walk). Pure: the game side
 * ({@code MovePackage}) asks {@link #due} which target to plan next, plans it on a worker thread and hands the path's
 * cells to {@link #store}; a walk asks {@link #lookup}.
 *
 * <p>Refresh rules: an entry is due when it is missing, when the bot moved {@link #MOVED} or more blocks from where it
 * was planned, when a block changed within {@link #NEAR} of its cells ({@link #blockChanged}), or when it is older than
 * {@link #MAX_AGE_MS}. At most one plan per {@link #BUDGET_MS}; the priority target (a chain's next walk) first.
 */
public final class ReadyPaths {
    public static final double MOVED = 8;
    public static final int NEAR = 2;
    public static final long BUDGET_MS = 2000;
    public static final long MAX_AGE_MS = 120_000;
    public static final int LAST_GOALS = 5;
    /** A walk's goal matches a ready entry within this distance; the bot must be within MOVED of the entry's start. */
    public static final double MATCH = 3;

    public static final class Entry {
        public final String name;
        public final Cell target;
        Cell from;
        long at = -1;
        List<Cell> cells = List.of();
        boolean dirty = true, failed;

        Entry(String name, Cell target) {
            this.name = name;
            this.target = target;
        }

        public List<Cell> cells() { return cells; }

        public Cell from() { return from; }

        public boolean failed() { return failed; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Cell> lastGoals = new ArrayList<>();
    private String priority;
    private long lastPlanMs = -BUDGET_MS;
    private long planned, hits;

    /** The fixed targets (name -> cell; null cells are dropped); entries no longer named go, kept ones keep their path. */
    public void setTargets(Map<String, Cell> targets) {
        Map<String, Cell> want = new LinkedHashMap<>();
        if (priority != null && entries.containsKey(priority)) want.put(priority, entries.get(priority).target);
        targets.forEach((k, v) -> { if (v != null) want.put(k, v); });
        for (int i = 0; i < lastGoals.size(); i++) want.put("goal" + (i + 1), lastGoals.get(i));
        entries.keySet().removeIf(k -> !want.containsKey(k) || !want.get(k).equals(entries.get(k).target));
        want.forEach((k, v) -> entries.computeIfAbsent(k, n -> new Entry(n, v)));
    }

    /** A walk was started to goal: it joins the last goals (newest first, at most 5). */
    public void rememberGoal(Cell goal) {
        lastGoals.removeIf(g -> g.dist(goal) <= MATCH);
        lastGoals.add(0, goal);
        while (lastGoals.size() > LAST_GOALS) lastGoals.remove(lastGoals.size() - 1);
    }

    public List<Cell> lastGoals() { return List.copyOf(lastGoals); }

    /** A chain's next walk: planned before the others (null clears it). */
    public void setPriority(Cell goal) {
        if (goal == null ? priority == null : priority != null && entries.containsKey(priority) && entries.get(priority).target.equals(goal)) return;
        if (priority != null) entries.remove(priority);
        priority = null;
        if (goal == null) return;
        priority = "next";
        Map<String, Entry> copy = new LinkedHashMap<>();
        copy.put(priority, new Entry(priority, goal));
        copy.putAll(entries);
        entries.clear();
        entries.putAll(copy);
    }

    /** Whether e needs planning again with the bot at me. */
    public static boolean stale(Entry e, Cell me, long nowMs) {
        return e.dirty || e.from == null || e.at < 0 || e.from.dist(me) >= MOVED || nowMs - e.at > MAX_AGE_MS;
    }

    /** The entry to plan now, or null (budget not passed, or nothing due). Marks the budget used when it returns one. */
    public Entry due(Cell me, long nowMs) {
        if (nowMs - lastPlanMs < BUDGET_MS) return null;
        for (Entry e : entries.values()) {
            if (e.target.dist(me) <= MATCH) continue;          // already there
            if (stale(e, me, nowMs)) {
                lastPlanMs = nowMs;
                return e;
            }
        }
        return null;
    }

    /** The plan for e from `from`: its cells (empty with failed = no path found). */
    public void store(Entry e, Cell from, List<Cell> cells, boolean failed, long nowMs) {
        e.from = from;
        e.cells = cells == null ? List.of() : List.copyOf(cells);
        e.failed = failed;
        e.at = nowMs;
        e.dirty = false;
        planned++;
    }

    /** A block changed: every entry with a cell within NEAR of it is planned again. */
    public int blockChanged(int x, int y, int z) {
        int n = 0;
        for (Entry e : entries.values()) {
            for (Cell c : e.cells) {
                if (Math.abs(c.x() - x) <= NEAR && Math.abs(c.y() - y) <= NEAR && Math.abs(c.z() - z) <= NEAR) {
                    if (!e.dirty) n++;
                    e.dirty = true;
                    break;
                }
            }
        }
        return n;
    }

    /** A ready, fresh entry for a walk from me to goal, or null. */
    public Entry lookup(Cell me, Cell goal, long nowMs) {
        for (Entry e : entries.values()) {
            if (e.target.dist(goal) > MATCH || e.failed || e.cells.isEmpty()) continue;
            if (stale(e, me, nowMs)) continue;
            hits++;
            return e;
        }
        return null;
    }

    public int size() { return entries.size(); }

    public long planned() { return planned; }

    public long hits() { return hits; }

    public int ready(Cell me, long nowMs) {
        int n = 0;
        for (Entry e : entries.values()) if (!e.failed && !e.cells.isEmpty() && !stale(e, me, nowMs)) n++;
        return n;
    }

    public List<String> names() { return List.copyOf(entries.keySet()); }
}
