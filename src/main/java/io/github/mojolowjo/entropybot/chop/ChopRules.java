package io.github.mojolowjo.entropybot.chop;

import io.github.mojolowjo.entropybot.surface.SurfaceColumns;
import io.github.mojolowjo.entropybot.surface.SurfaceFamily;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P3 (SURVIVAL_PLAN P3.2-P3.4): the pure rules of "chop": the grammar, tree candidates from the surface columns (the RTS
 * export's model: the first ground block under the canopy is a log), their order, the sapling of a wood, the replant
 * cells, the stop conditions and the report. JUnit in {@code ChopTest}. Loader notes: none (plain Java).
 */
public final class ChopRules {
    private ChopRules() {}

    /** No log in the column (y can be negative, so never -1). */
    public static final int NONE = Integer.MIN_VALUE;
    public static final int DEFAULT_LOGS = 16, MAX_LOGS = 1024, MAX_TREES = 64;
    /** 20 minutes (the owner's cap), in ticks. */
    public static final long MAX_TICKS = 20 * 60 * 20L;
    /** Trees that failed in a row before it gives up; break rounds per tree; how far it looks for trees. */
    public static final int FAILS = 3, ROUNDS = 8, RADIUS = 48;
    public static final String USAGE = "usage: cut <n> [logs|<log type>] | cut trees <n> [log type] | cut status (e.g. cut 16, cut 16 logs, cut trees 3 birch)";
    /** V1b: the type filter "any log except these" (the logs kind's exclusions, '.'-separated paths). */
    public static final String EXCEPT = "except:";

    /** n: logs wanted (trees = false) or trees wanted (trees = true); type: a log filter or null; error: the usage. */
    public record Args(int n, boolean trees, String type, boolean status, String error) {}

    public static Args parse(String rest) {
        String[] w = rest == null || rest.isBlank() ? new String[0] : rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length == 1 && w[0].equals("status")) return new Args(0, false, null, true, null);
        int i = 0;
        boolean trees = false;
        if (i < w.length && (w[i].equals("trees") || w[i].equals("tree"))) {
            trees = true;
            i++;
        }
        int n = trees ? 1 : DEFAULT_LOGS;
        if (i < w.length && w[i].matches("\\d{1,5}")) {
            n = Integer.parseInt(w[i]);
            i++;
            int max = trees ? MAX_TREES : MAX_LOGS;
            if (n < 1 || n > max) return new Args(0, trees, null, false, "error: " + (trees ? "trees" : "logs") + " must be 1-" + max + " - " + USAGE);
        } else if (trees) {
            return new Args(0, true, null, false, "error: how many trees? - " + USAGE);
        }
        String type = null;
        if (i < w.length) {
            type = w[i++];
            if (!type.matches("[a-z0-9_.:-]+")) return new Args(0, trees, null, false, "error: \"" + type + "\" is no log type - " + USAGE);
        }
        if (i < w.length) return new Args(0, trees, null, false, "error: too many words - " + USAGE);
        return new Args(n, trees, type, false, null);
    }

    /** Does the log id match the filter ("oak" is oak_log, never dark_oak_log; "minecraft:birch_log" exactly)? */
    public static boolean matches(String type, String logId) {
        if (type == null) return true;
        if (logId == null) return false;
        String path = logId.indexOf(':') >= 0 ? logId.substring(logId.indexOf(':') + 1) : logId;
        // V1b: "cut ... logs" with the logs kind's exclusions: any log but these ("except:cherry_log.pale_oak_log")
        if (type.startsWith(EXCEPT)) return !java.util.Arrays.asList(type.substring(EXCEPT.length()).split("\\.")).contains(path);
        if (type.indexOf(':') >= 0) return type.equals(logId);
        String t = type.endsWith("_log") || type.endsWith("_stem") ? type : type + "_log";
        return path.equals(t) || path.equals(type + "_stem");
    }

    /**
     * The column's tree candidate: walking down from the top over air, plants and leaves (the surface export's rule), the
     * first ground block is a log (SurfaceColumns' {@code l} flag). Returns its y, or {@link #NONE} (y may be negative). depth: how far down it looks.
     * The leaves are checked by {@link TreeFinder} (a trunk may stick out above its crown). Only y from {@code high} down to
     * {@code low} is walked (around the bot: the client's height map can be off, and a tree far below is no tree to walk to).
     */
    public static int logTop(SurfaceColumns.Source src, int x, int z, int high, int low) {
        int top = Math.min(src.top(x, z), high);
        for (int y = top; y >= low; y--) {
            int k = src.kind(x, y, z);
            if (k == SurfaceColumns.GROUND) return src.family(x, y, z) == SurfaceFamily.LOG ? y : NONE;
        }
        return NONE;
    }

    /** Candidates nearest first: horizontal distance, a level up or down counting double; columns next to a nearer one dropped. */
    public static List<int[]> order(List<int[]> cands, int mx, int my, int mz) {
        List<int[]> sorted = new ArrayList<>(cands);
        sorted.sort(Comparator.comparingLong(c -> cost(c, mx, my, mz)));
        List<int[]> out = new ArrayList<>();
        for (int[] c : sorted) {
            boolean near = false;
            for (int[] o : out) if (Math.abs(o[0] - c[0]) <= 1 && Math.abs(o[2] - c[2]) <= 1) near = true;
            if (!near) out.add(c);
        }
        return out;
    }

    static long cost(int[] c, int mx, int my, int mz) {
        long dx = c[0] - mx, dz = c[2] - mz, dy = 2L * (c[1] - my);
        return dx * dx + dz * dz + dy * dy;
    }

    /** The sapling a log drops ("minecraft:oak_log" -> "minecraft:oak_sapling"; mangrove: the propagule); null for stems. */
    public static String sapling(String logId) {
        if (logId == null) return null;
        String ns = logId.indexOf(':') >= 0 ? logId.substring(0, logId.indexOf(':') + 1) : "minecraft:";
        String path = logId.substring(logId.indexOf(':') + 1);
        if (!path.endsWith("_log")) return null;
        String wood = path.substring(0, path.length() - 4);
        if (wood.equals("mangrove")) return ns + "mangrove_propagule";
        return ns + wood + "_sapling";
    }

    /** The woods whose big trees grow only from 4 saplings in a square. */
    static final Set<String> SQUARE = Set.of("dark_oak");

    /**
     * Where the saplings go: every base cell when the bag holds enough (a 2x2 trunk: 4), else one; none without a sapling.
     * The note says what fell short (null: nothing).
     */
    public record Replant(List<int[]> cells, String note) {}

    public static Replant replant(List<int[]> bases, String sapling, int have) {
        if (sapling == null) return new Replant(List.of(), "no sapling for that wood - not replanted");
        if (have <= 0 || bases.isEmpty()) return new Replant(List.of(), "no " + shortId(sapling) + " - not replanted");
        if (have >= bases.size()) return new Replant(new ArrayList<>(bases), null);
        String wood = shortId(sapling).replace("_sapling", "");
        String note = "only " + have + " " + shortId(sapling) + " for a " + bases.size() + "-log trunk - planted 1"
                + (SQUARE.contains(wood) ? " (it needs 4 to grow)" : "");
        return new Replant(new ArrayList<>(bases.subList(0, 1)), note);
    }

    /** Why the job stops now, or null: the count reached, the time up, too many failed trees in a row. */
    public static String stopReason(Args a, int logs, int trees, long now, long deadline, int failsInRow) {
        if (a.trees() ? trees >= a.n() : logs >= a.n()) return "";
        if (now >= deadline) return "the 20 minutes are up";
        if (failsInRow >= FAILS) return FAILS + " trees in a row went wrong";
        return null;
    }

    /** "23 oak_log, 4 birch_log" (logs gained per id). */
    public static String logs(Map<String, Integer> got) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : got.entrySet()) if (e.getValue() > 0) parts.add(e.getValue() + " " + shortId(e.getKey()));
        return String.join(", ", parts);
    }

    public static int total(Map<String, Integer> got) {
        int n = 0;
        for (int v : got.values()) n += Math.max(0, v);
        return n;
    }

    /**
     * The end line. why "" or null = the count was reached: "ok: chopped 4 trees, 23 oak_log, replanted 4"; else "ok:
     * chopped 2 trees, 9 oak_log - no more trees in my areas (replanted 2)"; no tree at all: "error: ... - next: ...".
     * notes (skipped trees, logs left, axe) follow after "; ".
     */
    public static String endText(int trees, Map<String, Integer> got, int replanted, String why, List<String> notes, String next) {
        String l = logs(got);
        String tail = notes == null || notes.isEmpty() ? "" : "; " + String.join("; ", notes);
        String head = "chopped " + trees + (trees == 1 ? " tree" : " trees") + (l.isEmpty() ? "" : ", " + l);
        if (trees == 0 && total(got) == 0)
            return "error: chopped nothing - " + (why == null || why.isEmpty() ? "no tree" : why) + tail + (next != null ? " - next: " + next : "");
        if (why == null || why.isEmpty()) return "ok: " + head + ", replanted " + replanted + tail;
        return "ok: " + head + " - " + why + " (replanted " + replanted + ")" + tail;
    }

    /** What a status line says about the tree mods. */
    /**
     * P2: the index of the "chopaxecheck" step that closes the axe phase the failing step {@code idx} belongs to, or
     * -1 (the failing step is not part of an axe fetch/craft: a "chopstep" comes first, or there is no check).
     */
    public static int axeCheckAfter(java.util.List<String> types, int idx) {
        for (int j = Math.max(0, idx); j < types.size(); j++) {
            String t = types.get(j);
            if (t.equals("chopaxecheck")) return j > idx || !t.equals(types.get(idx)) ? j : -1;
            if (t.equals("chopstep")) return -1;
        }
        return -1;
    }

    /** P2: the end note when fetching or crafting the axe failed and the chop went on by hand. */
    public static String axeFailedNote(String what, String why) {
        String w = why == null ? "" : why.replaceFirst("^(error|partial): ", "");
        return "no axe (" + ("fetched".equals(what) ? "fetching" : "making") + " one failed: " + w + ") - chopped by hand";
    }

    public static String modsLine(boolean fallingTrees, boolean treeChop) {
        return "fallingtrees: " + (fallingTrees ? "yes" : "no") + ", treechop: " + (treeChop ? "yes" : "no");
    }

    /** Logs gained between two bag counts, per log id (a deposit in between can only lower it: never negative). */
    public static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after, java.util.function.Predicate<String> isLog) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (!isLog.test(e.getKey())) continue;
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) out.put(e.getKey(), d);
        }
        return out;
    }

    public static String shortId(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
