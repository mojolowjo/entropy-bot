package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.guard.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Baritone mine job's rules ("mine &lt;ore&gt; [n] [dig]"; the bridge's startMine and package A wave 1: protectNear,
 * boxMinus, mineLeases, minePickSlot, mineCount and the texts; rm-mining: the per-ore step's command and its Failures cell key), game-free so JUnit drives them.
 */
public final class MineRules {
    private MineRules() {}

    // ---- S1: the guard policy, read fail-closed (a policy or box it can't read stops mining instead of ignoring it) ----

    /** The policy (or one of its boxes) can't be read: the mine, caves and explore refuse with this message. */
    public static final class BadPolicy extends RuntimeException {
        public BadPolicy(String msg) { super(msg); }
    }

    /** The policy JSON as an object; BadPolicy when it isn't one. */
    public static com.google.gson.JsonObject parsePolicy(String json) {
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(json == null ? "" : json);
            if (!e.isJsonObject()) throw new BadPolicy("the guard policy can't be read (not a JSON object)");
            return e.getAsJsonObject();
        } catch (RuntimeException ex) {
            if (ex instanceof BadPolicy b) throw b;
            throw new BadPolicy("the guard policy can't be read (" + ex.getMessage() + ")");
        }
    }

    /** The boxes under key ("areas", "protect"); none when the key is missing; BadPolicy for anything malformed. */
    public static List<Box> policyBoxes(com.google.gson.JsonObject pol, String key, String defaultDim) {
        List<Box> out = new ArrayList<>();
        if (pol == null) throw new BadPolicy("the guard policy can't be read (none)");
        if (!pol.has(key) || pol.get(key).isJsonNull()) return out;
        if (!pol.get(key).isJsonArray()) throw new BadPolicy("the guard policy's " + key + " list is malformed (not a list)");
        int i = 0;
        for (com.google.gson.JsonElement e : pol.getAsJsonArray(key)) {
            i++;
            try {
                out.add(Box.fromJson(e.getAsJsonObject(), defaultDim));
            } catch (RuntimeException ex) {
                String name = "";
                try { if (e.isJsonObject() && e.getAsJsonObject().has("name")) name = " \"" + e.getAsJsonObject().get("name").getAsString() + "\""; } catch (RuntimeException ignored) {}
                throw new BadPolicy("box " + i + name + " in the guard policy's " + key + " list is malformed (" + ex.getMessage() + ")");
            }
        }
        return out;
    }

    /** The reply when the policy can't be read: refuse, never mine blind. */
    public static String badPolicyText(BadPolicy e) {
        return "I won't mine while " + e.getMessage() + " - check \"guard\", \"area list\" and \"protect\"";
    }

    /** Plain "mine" takes ores (the c:ores tag) and these loose natural blocks only. */
    public static final Pattern EXTRA = Pattern.compile("^minecraft:(sand|red_sand|gravel|clay|short_grass|tall_grass|fern|large_fern)$");
    public static final Pattern BLOCK_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    /** The job's time limit and the no-progress stop, in ticks (aa/brain/Tuning: mine.maxMin, mine.stallS). */
    public static long maxTicks() { return (long) (20 * 60 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("mine.maxMin")); }

    public static long stallTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("mine.stallS")); }

    /** Policy: the distance Baritone's free mine keeps from protect boxes (the guard's no-break). */
    public static final int PROTECT_MARGIN = 16;
    /** A lease covers at most this many blocks (GuardCore.MAX_LEASE_VOLUME): bigger boxes are sliced. */
    public static final long LEASE_MAX = 4096;
    static final Map<String, Integer> DROPS_PER = Map.of("redstone", 4, "lapis", 4, "copper", 3);
    /** The pickaxe tier a need asks of the tool, from the block tags (MiningTiers.needTier): the one scale; the caller's default is for an unreadable need and is replaced by tools.unknownNeedTier. */
    public static final NeedTiers PICK_TIER = new NeedTiers();

    public static final class NeedTiers {
        public int getOrDefault(String need, int unused) { return io.github.mojolowjo.entropybot.aa.brain.MiningTiers.needTier(need); }
    }

    /** "mine iron_ore 10 dig": the block id (normalised), the count (1 without), dig. */
    public record Args(String id, String word, int count, boolean dig) {}

    public static Args args(String text) {
        At at = parseAt(text);
        if (at != null && at.error() == null) text = at.plain();
        String[] p = String.valueOf(text).trim().split("\\s+");
        String w = p[0].toLowerCase();
        String id = w.contains(":") ? w : "minecraft:" + w;
        int count = p.length > 1 && p[1].matches("^\\d+$") ? safeInt(p[1]) : 1;
        boolean dig = false;
        for (String s : p) if (s.equals("dig")) dig = true;
        return new Args(id, p[0], count, dig);
    }

    // ---- AA round 2 item 5: "mine <ore> [n] at x y z [dig|walk]", one named target and its vein ----

    /**
     * The at form: the ore word, the count (1 without; shown only, the job mines the target's vein), the cell, dig (walk
     * without the word). {@code error} set (the rest unset) when the text has "at" but is not this form.
     */
    public record At(String word, int count, int x, int y, int z, boolean dig, String error) {
        /** The plain mine text for the rest of the job ("iron 1 dig"). */
        public String plain() { return word + " " + count + (dig ? " dig" : ""); }

        public String cell() { return x + " " + y + " " + z; }
    }

    public static final String AT_FORM = "mine <ore> 1 at x y z dig|walk";
    private static final Pattern AT = Pattern.compile("^(\\S+)(?:\\s+(\\d+))?\\s+at\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)(?:\\s+(dig|walk))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_AT = Pattern.compile("(?i)(^|\\s)at(\\s|$)");

    /** The at form of the text after "mine", or null when it has no "at" word (or is "mine cave/strip ..."). Pure. */
    public static At parseAt(String text) {
        String t = String.valueOf(text).trim();
        if (!HAS_AT.matcher(t).find() || t.matches("(?i)^(cave|strip)\\b.*")) return null;
        java.util.regex.Matcher m = AT.matcher(t);
        if (!m.matches()) return new At(null, 0, 0, 0, 0, false, "error: the at form is \"" + AT_FORM + "\"");
        int n = m.group(2) == null ? 1 : safeInt(m.group(2));
        if (n < 1) return new At(null, 0, 0, 0, 0, false, "error: the count must be 1 or more (\"" + AT_FORM + "\")");
        try {
            return new At(m.group(1).toLowerCase(), n, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    "dig".equalsIgnoreCase(m.group(6)), null);
        } catch (NumberFormatException e) {
            return new At(null, 0, 0, 0, 0, false, "error: a coordinate is out of range (\"" + AT_FORM + "\")");
        }
    }

    /** The at form's vein: at most this many cells, the target included. */
    public static final int AT_VEIN = 8;

    /** The vein from start: start, then face-touching cells {@code sameOre} accepts, breadth first, at most max. Pure. */
    public static List<int[]> vein(int[] start, java.util.function.Predicate<int[]> sameOre, int max) {
        List<int[]> out = new ArrayList<>();
        java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        todo.add(start.clone());
        seen.add(start[0] + " " + start[1] + " " + start[2]);
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!todo.isEmpty() && out.size() < max) {
            int[] c = todo.poll();
            out.add(c);
            for (int[] d : sides) {
                int[] n = {c[0] + d[0], c[1] + d[1], c[2] + d[2]};
                if (seen.add(n[0] + " " + n[1] + " " + n[2]) && sameOre.test(n)) todo.add(n);
            }
        }
        return out;
    }

    /** The at job's end: "done: mined 3 iron at 1 2 3" (+ gains). */
    public static String atDoneText(int n, String family, String cell, String gains) {
        return "done: mined " + n + " " + family + " at " + cell + gains;
    }

    /** The at job's failure; the words keep it a mining end AA may switch on ("couldn't get to"). */
    public static String atFailText(String id, String cell, String why, String gains) {
        return "stopped: couldn't get to the " + shortId(id) + " at " + cell + " (" + why + ")" + gains;
    }

    public static String notABlock(String word) { return "error: \"" + word + "\" is not a block id"; }

    public static String notAnOre(String id) {
        return "error: \"mine\" only takes ores (and sand, gravel, clay, grass): " + shortId(id) + " could be part of a build. \"dig x1 y1 z1 x2 y2 z2\" clears a box";
    }

    public static String shortId(String id) { return String.valueOf(id).replaceFirst("^minecraft:", ""); }

    public static String anA(String w) { return (w.matches("(?i)^[aeiou].*") ? "an " : "a ") + w; }

    /** The status while mining: "mining 4 minecraft:iron_ore (exposed only)". */
    public static String status(int count, String id, boolean dig) {
        return "mining " + count + " " + id + (dig ? " (digging allowed)" : " (exposed only)");
    }

    // ---- the protect boxes (wave 1 item 8): Baritone's mine digs where it likes ----

    public record Near(Box box, int gap) {}

    /** Cells between x y z and box b (0 inside); y counts only when the box has one. */
    public static int gap(Box b, int x, int y, int z) {
        int g = Math.max(Math.max(b.x1 - x, 0), Math.max(x - b.x2, Math.max(b.z1 - z, z - b.z2)));
        if (!b.allY()) g = Math.max(g, Math.max(b.y1 - y, y - b.y2));
        return g;
    }

    /** The nearest protect box in dim within margin of x y z, or null. */
    public static Near protectNear(List<Box> protect, String dim, int x, int y, int z, int margin) {
        Near best = null;
        for (Box b : protect) {
            if (!b.dim.equals(dim)) continue;
            int g = gap(b, x, y, z);
            if (g <= margin && (best == null || g < best.gap())) best = new Near(b, g);
        }
        return best;
    }

    public static String protectNearText(Near n) {
        String name = n.box().name != null ? n.box().name : "box";
        return "I won't mine here: " + (n.gap() == 0 ? "I am inside the protected " + name : "the protected " + name + " is " + n.gap() + " blocks away")
                + " (Baritone's mine digs where it likes, so I keep " + PROTECT_MARGIN + " blocks away from protect boxes)";
    }

    // ---- the leases: 32 x 33 x 32 around the bot, clipped to the areas, minus the protect boxes, sliced ----

    /** Box b (y given) without box p (p may have no y: every height): up to 6 boxes (the bridge's boxMinus). */
    public static List<int[]> minus(int[] b, Box p) {
        int py1 = p.allY() ? Integer.MIN_VALUE / 2 : p.y1, py2 = p.allY() ? Integer.MAX_VALUE / 2 : p.y2;
        int ix1 = Math.max(b[0], p.x1), ix2 = Math.min(b[3], p.x2), iy1 = Math.max(b[1], py1), iy2 = Math.min(b[4], py2),
                iz1 = Math.max(b[2], p.z1), iz2 = Math.min(b[5], p.z2);
        List<int[]> out = new ArrayList<>();
        if (ix1 > ix2 || iy1 > iy2 || iz1 > iz2) {
            out.add(b);
            return out;
        }
        int x1 = b[0], x2 = b[3], z1 = b[2], z2 = b[5];
        if (x1 < ix1) { out.add(new int[]{x1, b[1], z1, ix1 - 1, b[4], z2}); x1 = ix1; }
        if (x2 > ix2) { out.add(new int[]{ix2 + 1, b[1], z1, x2, b[4], z2}); x2 = ix2; }
        if (z1 < iz1) { out.add(new int[]{x1, b[1], z1, x2, b[4], iz1 - 1}); z1 = iz1; }
        if (z2 > iz2) { out.add(new int[]{x1, b[1], iz2 + 1, x2, b[4], z2}); z2 = iz2; }
        if (b[1] < iy1) out.add(new int[]{x1, b[1], z1, x2, iy1 - 1, z2});
        if (b[4] > iy2) out.add(new int[]{x1, iy2 + 1, z1, x2, b[4], z2});
        return out;
    }

    /** One or more boxes of at most max blocks each: a bigger box is halved along its longest side (leaseSlices). */
    public static List<int[]> slices(int[] b, long max) {
        List<int[]> out = new ArrayList<>();
        long dx = b[3] - b[0] + 1, dy = b[4] - b[1] + 1, dz = b[5] - b[2] + 1;
        if (dx * dy * dz <= max) {
            out.add(b);
            return out;
        }
        int[] a = b.clone(), c = b.clone();
        if (dy >= dx && dy >= dz) { a[4] = b[1] + (int) (dy / 2) - 1; c[1] = a[4] + 1; }
        else if (dx >= dz) { a[3] = b[0] + (int) (dx / 2) - 1; c[0] = a[3] + 1; }
        else { a[5] = b[2] + (int) (dz / 2) - 1; c[2] = a[5] + 1; }
        out.addAll(slices(a, max));
        out.addAll(slices(c, max));
        return out;
    }

    /** The mine job's box around the bot (32 x 33 x 32): {x1, y1, z1, x2, y2, z2}. */
    public static int[] mineBox(int x, int y, int z) {
        return new int[]{x - 16, y - 16, z - 16, x + 15, y + 16, z + 15};
    }

    /**
     * The lease boxes for box b (the bridge's takeLeaseClipped with minusProtect): AREAS (0.39.0) the world is open, so
     * the whole box minus every protect box (keep-out, no-break) of this dimension, sliced; {@code areas} is unused.
     * Null when nothing is left (the box lies wholly in protected areas).
     */
    public static List<int[]> leaseBoxes(int[] b, List<Box> areas, List<Box> protect, String dim) {
        List<int[]> out = new ArrayList<>();
        List<int[]> parts = new ArrayList<>(List.of(b.clone()));
        for (Box p : protect) {
            if (!p.dim.equals(dim)) continue;
            List<int[]> q = new ArrayList<>();
            for (int[] x : parts) q.addAll(minus(x, p));
            parts = q;
        }
        for (int[] part : parts) out.addAll(slices(part, LEASE_MAX));
        return out.isEmpty() ? null : out;
    }

    // ---- the pickaxe ----

    /** One bag slot as the pickaxe choice sees it. */
    public record Slot(int index, String id, boolean correctForDrops) {}

    /**
     * The tier the ore needs ("stone" | "iron" | "diamond", from its needs_*_tool tag: tagNeed), "stone" for digging
     * through stone, or null (the bridge's minePickNeed).
     */
    public static String pickNeed(boolean requiresCorrectTool, String tagNeed, boolean dig) {
        if (requiresCorrectTool) return tagNeed;
        return dig ? "stone" : null;
    }

    /** Does the pickaxe do: the right tool for the drops, else its tier (minePickOk). */
    static boolean pickOk(Slot s, boolean requiresCorrectTool, String need) {
        if (!Tools.isPickaxe(s.id())) return false;
        return requiresCorrectTool ? s.correctForDrops() : Tools.toolTier(s.id()) >= PICK_TIER.getOrDefault(need, 2);
    }

    /**
     * The slot of the pickaxe to mine with: under "tools ores iron" the cheapest of iron or better, else the cheapest that
     * does the job; -1 with none (minePickSlot).
     */
    public static int pickSlot(List<Slot> slots, boolean requiresCorrectTool, String need, String toolOres) {
        // V1b: tools mode best|stone pick by tier among the pickaxes that do (cheapest: the lowest)
        if (io.github.mojolowjo.entropybot.vocab.ToolMode.BEST.equals(toolOres) || io.github.mojolowjo.entropybot.vocab.ToolMode.STONE.equals(toolOres)) {
            List<Slot> ok = new ArrayList<>();
            for (Slot s : slots) if (pickOk(s, requiresCorrectTool, need)) ok.add(s);
            int[] tiers = new int[ok.size()];
            for (int i = 0; i < tiers.length; i++) tiers[i] = Tools.toolTier(ok.get(i).id());
            int i = io.github.mojolowjo.entropybot.vocab.ToolMode.pick(tiers, toolOres);
            return i < 0 ? -1 : ok.get(i).index();
        }
        int best = -1, bestT = 99, iron = -1, ironT = 99;
        boolean ores = "iron".equals(toolOres);
        for (Slot s : slots) {
            if (!pickOk(s, requiresCorrectTool, need)) continue;
            int t = Tools.toolTier(s.id());
            if (t < bestT) { best = s.index(); bestT = t; }
            if (ores && t >= Tools.ORE_TIER && t < ironT) { iron = s.index(); ironT = t; }
        }
        return iron >= 0 ? iron : best;
    }

    /** The best pickaxe tier among item ids (stone 2, copper 3, iron 4...), 0 without one (bestPickTier). */
    public static int bestPickTier(Iterable<String> ids) {
        int best = 0;
        for (String id : ids) if (Tools.isPickaxe(id)) best = Math.max(best, Tools.toolTier(id));
        return best;
    }

    /** The pickaxes that do for a tier, in the bridge's order: the tier's own, diamond, netherite. */
    public static List<String> pickIds(String need) {
        int tier = PICK_TIER.getOrDefault(need, 4);
        List<String> out = new ArrayList<>();
        for (String id : new String[]{"minecraft:" + need + "_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"}) {
            if (Tools.toolTier(id) >= tier && !out.contains(id)) out.add(id);
        }
        return out;
    }

    // ---- the count ----

    /** How many of the target it mined, from what it picked up: the block, its raw ore, its drop (redstone and lapis drop ~4). */
    public static int count(String target, Map<String, Integer> gained) {
        String path = target.replaceFirst("^.*:", "");
        String base = path.replaceFirst("^(deepslate|nether)_", "").replaceFirst("_ore$", "");
        int n = 0;
        for (Map.Entry<String, Integer> e : gained.entrySet()) {
            String k = e.getKey(), nm = k.replaceFirst("^.*:", "");
            if (k.equals(target) || nm.equals("raw_" + base) || nm.equals(base) || nm.equals(base + "_lazuli")) n += e.getValue();
        }
        return n / DROPS_PER.getOrDefault(base, 1);
    }

    /** What the bag gained since before (countsDiff's gained). */
    public static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        after.forEach((id, n) -> {
            int d = n - before.getOrDefault(id, 0);
            if (d > 0) out.put(id, d);
        });
        return out;
    }

    /** " - got 9 raw_iron, 2 cobblestone" or " - got nothing" (mineGains; topItems with 4). */
    public static String gains(Map<String, Integer> gained) {
        if (gained.isEmpty()) return " - got nothing";
        List<Map.Entry<String, Integer>> l = new ArrayList<>(gained.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(4, l.size()); i++) out.add(l.get(i).getValue() + " " + shortId(l.get(i).getKey()));
        if (l.size() > 4) out.add("+" + (l.size() - 4) + " more");
        return " - got " + String.join(", ", out);
    }

    // ---- the ends ----

    public static String doneText(String target, String gains, String toolNote) {
        return "done: mining " + shortId(target) + " stopped, breaking turned back off" + gains + (toolNote != null ? "; " + toolNote : "");
    }

    public static String shortText(String target, int got, int count, String why, String gains, String toolNote) {
        return "done: mining " + shortId(target) + ": got " + got + " of " + count + " - " + why + ", breaking turned back off" + gains + (toolNote != null ? "; " + toolNote : "");
    }

    public static String stalledText(String target, String at, String gains) {
        return "stopped: stall: no progress for " + stallTicks() / 20 + " s mining " + shortId(target) + " at " + at + gains;
    }

    public static String noPickText(String target, String gains) {
        return "stopped: tool: I have no pickaxe left that can mine " + shortId(target) + gains;
    }

    public static String timeUpText() { return "time is up (" + maxTicks() / 1200 + " min)"; }

    /** Digits as an int, capped (a 10-digit count would overflow parseInt). */
    static int safeInt(String s) {
        return s.length() > 9 ? 999_999_999 : Integer.parseInt(s);
    }

    // ---- rm-mining: the per-ore step and its failures (the old known-target list, its 5 tries and water skips are gone) ----

    /** The Failures desire a failed ore cell is charged under (a prefix no AA desire uses). */
    public static final String CELL_DESIRE = "site.mine";

    private static volatile io.github.mojolowjo.entropybot.aa.brain.Failures failures = new io.github.mojolowjo.entropybot.aa.brain.Failures();

    /** The AA brain's failure record (so aa why shows the holds); a private one until bound. */
    public static void bind(io.github.mojolowjo.entropybot.aa.brain.Failures f) { if (f != null) failures = f; }

    public static io.github.mojolowjo.entropybot.aa.brain.Failures failures() { return failures; }

    /** The cell key: "iron 10 -20 30". */
    public static String cellKey(String family, int x, int y, int z) { return family + " " + x + " " + y + " " + z; }

    /** A per-ore step that failed: charged under its cell with the stage and the reason; never retried in place. */
    public static io.github.mojolowjo.entropybot.aa.brain.Failures.Rec charge(String family, int[] cell, String stage, String reason, long nowMs) {
        return failures.failed(CELL_DESIRE, cellKey(family, cell[0], cell[1], cell[2]), stage + ": " + reason, nowMs, null);
    }

    /** The cell is held (it failed and its hold has not lapsed): no pick takes it. */
    public static boolean held(String family, int x, int y, int z, long nowMs) {
        return failures.blocked(CELL_DESIRE, cellKey(family, x, y, z), nowMs);
    }

    /** The per-ore step's command: "mine iron_ore 1 at 10 -20 30 dig" (walk without dig); parseAt reads it back. */
    public static String perOre(String ore, int[] cell, boolean dig) {
        return "mine " + shortId(ore) + " 1 at " + cell[0] + " " + cell[1] + " " + cell[2] + (dig ? " dig" : " walk");
    }
}
