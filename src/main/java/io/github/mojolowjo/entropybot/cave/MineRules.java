package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.guard.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Baritone mine job's rules ("mine &lt;ore&gt; [n] [dig]"; the bridge's startMine and package A wave 1: protectNear,
 * boxMinus, mineLeases, minePickSlot, mineCount, mineTrail/mineLegs and the texts), game-free so JUnit drives them.
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
    public static final long MAX_TICKS = 20L * 60 * 20, STALL_TICKS = 20L * 60;
    public static final int PROTECT_MARGIN = 16, SEEKS = 3, RETURN_R = 8, TRAIL_STEP = 12, LEG = 40, TRAIL_MAX = 300, TOOL_TRIPS = 2;
    /** The walks: to new land 90 s, back to the spot 120 s; at least 40 ticks before "arrived" counts. */
    public static final long SEEK_TICKS = 20 * 90, RETURN_TICKS = 20 * 120;
    /** A lease covers at most this many blocks (GuardCore.MAX_LEASE_VOLUME): bigger boxes are sliced. */
    public static final long LEASE_MAX = 4096;
    static final Map<String, Integer> DROPS_PER = Map.of("redstone", 4, "lapis", 4, "copper", 3);
    public static final Map<String, Integer> PICK_TIER = Map.of("stone", 2, "iron", 4, "diamond", 5);

    /** "mine iron_ore 10 dig": the block id (normalised), the count (1 without), dig. */
    public record Args(String id, String word, int count, boolean dig) {}

    public static Args args(String text) {
        String[] p = String.valueOf(text).trim().split("\\s+");
        String w = p[0].toLowerCase();
        String id = w.contains(":") ? w : "minecraft:" + w;
        int count = p.length > 1 && p[1].matches("^\\d+$") ? safeInt(p[1]) : 1;
        boolean dig = false;
        for (String s : p) if (s.equals("dig")) dig = true;
        return new Args(id, p[0], count, dig);
    }

    public static String notABlock(String word) { return "error: \"" + word + "\" is not a block id"; }

    public static String notAnOre(String id) {
        return "error: \"mine\" only takes ores (and sand, gravel, clay, grass): " + shortId(id) + " could be part of a build. \"dig x1 y1 z1 x2 y2 z2\" clears a box";
    }

    /** The areas' names for the refusal outside them: "my areas (map, home)" / "my areas (none set - hint)". */
    public static String areasText(List<String> names, String hint) {
        return "my areas (" + (names.isEmpty() ? "none set - " + hint : String.join(", ", names)) + ")";
    }

    public static String outsideAreas(List<String> names, String hint) {
        return "error: I only \"mine\" inside " + areasText(names, hint) + "; elsewhere use stripmine";
    }

    public static String shortId(String id) { return String.valueOf(id).replaceFirst("^minecraft:", ""); }

    public static String anA(String w) { return (w.matches("(?i)^[aeiou].*") ? "an " : "a ") + w; }

    /** The status while mining: "mining 4 minecraft:iron_ore (exposed only)" (+ " - look 2" after a walk to new land). */
    public static String status(int count, String id, boolean dig, int seeks) {
        return "mining " + count + " " + id + (dig ? " (digging allowed)" : " (exposed only)") + (seeks > 0 ? " - look " + (seeks + 1) : "");
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
     * The lease boxes for box b (the bridge's takeLeaseClipped with minusProtect): the part inside each area of this
     * dimension, minus every protect box, sliced. Null when no area of this dimension overlaps the box at all.
     */
    public static List<int[]> leaseBoxes(int[] b, List<Box> areas, List<Box> protect, String dim) {
        List<int[]> out = new ArrayList<>();
        boolean any = false;
        for (Box a : areas) {
            if (!a.dim.equals(dim)) continue;
            int[] c = {Math.max(b[0], a.x1), b[1], Math.max(b[2], a.z1), Math.min(b[3], a.x2), b[4], Math.min(b[5], a.z2)};
            if (!a.allY()) {
                c[1] = Math.max(c[1], a.y1);
                c[4] = Math.min(c[4], a.y2);
            }
            if (c[0] > c[3] || c[1] > c[4] || c[2] > c[5]) continue;
            any = true;
            List<int[]> parts = new ArrayList<>();
            parts.add(c);
            for (Box p : protect) {
                if (!p.dim.equals(dim)) continue;
                List<int[]> q = new ArrayList<>();
                for (int[] x : parts) q.addAll(minus(x, p));
                parts = q;
            }
            for (int[] part : parts) out.addAll(slices(part, LEASE_MAX));
        }
        return any ? out : null;
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
        int best = -1, bestT = 99, iron = -1, ironT = 99;
        boolean ores = !"cheapest".equals(toolOres);
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

    // ---- the way back (review fix 3) ----

    static long d2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** A point every TRAIL_STEP blocks while it mines (the first point, where it was asked to mine, stays). */
    public static void trail(List<int[]> trail, int[] me) {
        if (d2(me, trail.get(trail.size() - 1)) < (long) TRAIL_STEP * TRAIL_STEP) return;
        trail.add(me.clone());
        if (trail.size() > TRAIL_MAX) trail.remove(1);
    }

    /** The legs from me back to the anchor: straight there when close, else along the trail from its point nearest me. */
    public static List<int[]> legs(List<int[]> trail, int[] anchor, int[] me) {
        List<int[]> legs = new ArrayList<>();
        long leg2 = (long) LEG * LEG;
        if (d2(me, anchor) <= leg2 || trail == null || trail.isEmpty()) {
            legs.add(anchor.clone());
            return legs;
        }
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < trail.size(); i++) {
            long d = d2(me, trail.get(i));
            if (d < bestD) { bestD = d; best = i; }
        }
        int[] last = me;
        long step2 = (long) (LEG - 10) * (LEG - 10);
        for (int i = best; i < trail.size(); i++) {
            if (d2(trail.get(i), anchor) <= leg2) break;
            if (i == best || d2(trail.get(i), last) >= step2) {
                legs.add(trail.get(i));
                last = trail.get(i);
            }
        }
        legs.add(anchor.clone());
        return legs;
    }

    // ---- the ends ----

    public static String doneText(String target, String gains, String toolNote) {
        return "done: mining " + shortId(target) + " stopped, breaking turned back off" + gains + (toolNote != null ? "; " + toolNote : "");
    }

    public static String shortText(String target, int got, int count, String why, String gains, String toolNote) {
        return "done: mining " + shortId(target) + ": got " + got + " of " + count + " - " + why + ", breaking turned back off" + gains + (toolNote != null ? "; " + toolNote : "");
    }

    public static String stalledText(String target, String at, String gains) {
        return "stopped: no progress for a minute mining " + shortId(target) + " at " + at + ", twice" + gains;
    }

    public static String noPickLeftText(String target, int trips, String gains) {
        return "stopped: I have no pickaxe left that can mine " + shortId(target) + " (fetched or made " + trips + " already)" + gains;
    }

    public static String stuckBackText(String target, String anchor, String me, String leg, String gains) {
        return "stopped: I couldn't get back to where I was mining " + shortId(target) + " (" + anchor + ") - stuck at " + me + " on the way to " + leg + gains;
    }

    public static String leftAreasText(String at) { return "I left my areas at " + at; }

    public static String timeUpText() { return "time is up (" + MAX_TICKS / 1200 + " min)"; }

    /** Digits as an int, capped (a 10-digit count would overflow parseInt). */
    static int safeInt(String s) {
        return s.length() > 9 ? 999_999_999 : Integer.parseInt(s);
    }
}
