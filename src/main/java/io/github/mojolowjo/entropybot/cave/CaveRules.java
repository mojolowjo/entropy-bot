package io.github.mojolowjo.entropybot.cave;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The decisions of "mine cave" (B4 and wave 1 item 2; the bridge's caveStop, caveStep's vein choice, caveMove and the
 * caveend note), game-free so JUnit can drive them. The game side (commands/Mining) asks these and does the walking,
 * lighting, searching and clearing.
 */
public final class CaveRules {
    private CaveRules() {}

    public static final int MINUTES = 30, FROM_ENTRANCE = 160, FREE = 4, MOVES = 3, MISSES = 4, VEIN_CELLS = 8, VEIN_REACH = 16, VEIN_SPREAD = 4;
    /** The walk to a frontier: until Baritone is done or this many ticks (60 s). */
    public static final long WALK_TICKS = 1200;
    static final Pattern FOOD = Pattern.compile("bread|cooked|apple|carrot|potato|stew|berries|melon|pie|cookie");

    /** How the bot is doing: a reason to stop caving, or null (the bridge's caveStop). */
    public static String stopReason(OreSpec spec, int got, int target, long now, long until, int bagRoom, float health, int food,
                                    Map<String, Integer> inv) {
        if (target > 0 && got >= target) return "that makes " + spec.word(target);
        if (now > until) return "the time is up";
        if (bagRoom <= FREE) return "my bag is nearly full";
        if (health < 10) return "my health is low (" + Math.round(health) + ")";
        int edible = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) if (FOOD.matcher(e.getKey()).find()) edible += e.getValue();
        if (food < 8 && edible == 0) return "I am hungry and carry no food";
        return null;
    }

    /** Light the spot? Monsters spawn at block light 0: a torch at block light 0-1 with no sky light. */
    public static boolean needsTorch(int blockLight, int skyLight) {
        return blockLight <= 1 && skyLight == 0;
    }

    /**
     * One vein at a time (the bridge's caveStep): the nearest ore it can mine (VEIN_REACH steps away at most) and the
     * ones within VEIN_SPREAD blocks of it, VEIN_CELLS at most. An ore tried twice, or the guard would refuse, is passed
     * over; one its pickaxes can't mine is noted in tooHard (short id) for the report and not chased again.
     *
     * @param ores    the search's ores, nearest first
     * @param tried   "x y z" -> tries (updated)
     * @param refused the guard refuses walking to it (goalAllowed != null)
     * @param canMine a pickaxe in the bag gets its drops
     * @param tooHard short ids left for a better pickaxe (updated)
     */
    public static List<CaveSearch.Ore> pickVein(List<CaveSearch.Ore> ores, Map<String, Integer> tried, Predicate<CaveSearch.Ore> refused,
                                                Predicate<CaveSearch.Ore> canMine, Set<String> tooHard) {
        List<CaveSearch.Ore> cells = new ArrayList<>();
        CaveSearch.Ore first = null;
        for (int i = 0; i < ores.size() && cells.size() < VEIN_CELLS; i++) {
            CaveSearch.Ore o = ores.get(i);
            String n = o.x() + " " + o.y() + " " + o.z();
            if (tried.getOrDefault(n, 0) >= 2 || refused.test(o)) continue;
            if (first == null && o.dist() > VEIN_REACH) break;
            if (first != null && Math.max(Math.abs(o.x() - first.x()), Math.max(Math.abs(o.y() - first.y()), Math.abs(o.z() - first.z()))) > VEIN_SPREAD) continue;
            if (!canMine.test(o)) {
                tooHard.add(o.id().replaceFirst("^minecraft:", ""));
                tried.put(n, 2);
                continue;
            }
            if (first == null) first = o;
            tried.merge(n, 1, Integer::sum);
            cells.add(o);
        }
        return cells;
    }

    /** A cave the bot knows, as the move after an empty spot sees it. */
    public record Known(String name, String dim, int x, int y, int z, boolean frontierLeft) {}

    /**
     * Where to look next when this spot has no cave (the bridge's caveMove): the nearest known cave in this dimension
     * with frontier left that this job hasn't closed and the guard lets it walk to; null = none (then unexplored land).
     */
    public static Known nearestKnown(List<Known> caves, String dim, int x, int y, int z, Set<String> closed, Predicate<Known> refused) {
        Known best = null;
        long bestD = Long.MAX_VALUE;
        for (Known k : caves) {
            if (!k.frontierLeft() || (k.dim() != null && !k.dim().equals(dim)) || closed.contains(k.name()) || refused.test(k)) continue;
            long d = (long) (k.x() - x) * (k.x() - x) + (long) (k.y() - y) * (k.y() - y) + (long) (k.z() - z) * (k.z() - z);
            if (best == null || d < bestD) {
                best = k;
                bestD = d;
            }
        }
        return best;
    }

    /** The end of the moves: "there is no cave I can get into near here (cave_1 (x y z), ...; last: why)". */
    public static String noCaveText(List<String> noCaveAt, String why) {
        return "there is no cave I can get into near here (" + String.join(", ", noCaveAt) + "; last: " + why + ")";
    }

    /** What a run in a cave says at its end (the bridge's caveend note). */
    public static String endNote(OreSpec spec, int got, String cave, String why, boolean finished, boolean noCave, int torches,
                                 Set<String> tooHard, List<String> noCaveAt) {
        StringBuilder sb = new StringBuilder();
        if (noCave) sb.append("mined ").append(spec.word(got)).append(" - ").append(why);
        else {
            sb.append("mined ").append(spec.word(got)).append(" in ").append(cave).append(" (").append(why).append(")");
            if (finished) sb.append("; ").append(cave).append(" is finished");
        }
        if (torches > 0) sb.append(", placed ").append(torches).append(" torches");
        if (tooHard != null && !tooHard.isEmpty()) sb.append("; left ").append(String.join(", ", tooHard)).append(" (needs a better pickaxe)");
        if (noCaveAt != null && !noCaveAt.isEmpty() && !noCave) sb.append("; no cave at ").append(String.join(", ", noCaveAt)).append(", so I moved");
        return sb.toString();
    }

    /** The job's label: "caving in cave_1 for iron (5), 30 min". */
    public static String label(String cave, OreSpec spec, int target, int minutes) {
        return "caving in " + cave + " for " + spec.label + (target > 0 ? " (" + target + ")" : "") + ", " + minutes + " min";
    }

    /** The label after a move to another cave. */
    public static String relabel(String label, String cave) {
        return label.replaceFirst("^caving in [a-z0-9_-]+", "caving in " + cave);
    }

    /** "cave_1 at 20 40 0 (got 12 blocks in)" | "... (finished)" (the bridge's cavesCommand line). */
    public static String listLine(String name, int x, int y, int z, boolean frontierLeft, int furthest) {
        return name + " at " + x + " " + y + " " + z + " (" + (frontierLeft ? "got " + furthest + " blocks in" : "finished") + ")";
    }

    /** The names noted in noCaveAt ("cave_2 (x y z)") -> the cave names, for {@link #nearestKnown}'s closed set. */
    public static Set<String> closedNames(List<String> noCaveAt) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : noCaveAt) out.add(s.replaceFirst(" .*$", ""));
        return out;
    }
}
