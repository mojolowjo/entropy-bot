package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.34.0: picks remembered block spots (Baritone's chunk cache) - pure, no Minecraft classes, so it is unit-tested.
 * A spot whose chunk is loaded is checked live: still that block -> kept, gone -> dropped (the caller logs it).
 */
public final class MemoryPick {
    private MemoryPick() {}

    /** Where an answer came from, for the reply. */
    public enum Source { LIVE, BARITONE, INDEX }

    public record Spot(String id, int x, int y, int z, Source src) {
        public long d2(int[] me) {
            long dx = x - me[0], dy = y - me[1], dz = z - me[2];
            return dx * dx + dy * dy + dz * dz;
        }
        public String at() { return x + " " + y + " " + z; }
    }

    /** The live view of one cell: LOADED_SAME (still there), LOADED_GONE, UNLOADED. */
    public enum Live { LOADED_SAME, LOADED_GONE, UNLOADED }

    public interface Check { Live at(String id, int x, int y, int z); }
    public interface Filter { boolean ok(int x, int y, int z); }

    public record Result(List<Spot> kept, List<Spot> dropped) {}

    /** Nearest first; the filter (areas, tried) first, then the live check; at most max kept. */
    public static Result pick(List<Spot> spots, int[] me, Filter filter, Check check, int max) {
        List<Spot> sorted = new ArrayList<>(spots);
        sorted.sort((a, b) -> Long.compare(a.d2(me), b.d2(me)));
        List<Spot> kept = new ArrayList<>(), dropped = new ArrayList<>();
        for (Spot s : sorted) {
            if (kept.size() >= max) break;
            if (filter != null && !filter.ok(s.x(), s.y(), s.z())) continue;
            if (check != null && check.at(s.id(), s.x(), s.y(), s.z()) == Live.LOADED_GONE) {
                dropped.add(s);
                continue;
            }
            kept.add(s);
        }
        return new Result(kept, dropped);
    }

    /** "remembered oak at 10 64 -3, 180 blocks" (Baritone's memory) or "found ... " (loaded) or "known ... " (the mod's index). */
    public static String text(Spot s, int[] me) {
        String word = switch (s.src()) { case LIVE -> "found"; case BARITONE -> "remembered"; case INDEX -> "known"; };
        String name = shortName(s.id());
        return word + " " + name + " at " + s.at() + ", " + Math.round(Math.sqrt(s.d2(me))) + " blocks";
    }

    /** "minecraft:oak_log" -> "oak", "iron_ore" -> "iron_ore". */
    public static String shortName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.endsWith("_log")) n = n.substring(0, n.length() - 4);
        return n;
    }

    /** Baritone's cache spells vanilla blocks without the namespace. */
    public static String cacheName(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
