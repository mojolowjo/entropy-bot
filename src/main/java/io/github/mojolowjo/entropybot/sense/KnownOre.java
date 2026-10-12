package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.strip.OreSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.28.0: the jobs' words and picks over the ore index: a vein of known positions round the nearest one (for the clear
 * engine), the family match ("iron" takes iron_ore and deepslate_iron_ore; "nickel" takes oritech:nickel_ore), the
 * "ores known" answer and the end line when the index had nothing. Plain Java, JUnit. Loader notes: none.
 */
public final class KnownOre {
    private KnownOre() {}

    /** Positions further apart than this (each axis) are not one vein. */
    public static final int VEIN_GAP = 2, VEIN_MAX = 24;

    /** Does a block id belong to the family word ("iron", "oritech:nickel", "nickel")? */
    public static boolean family(String id, String word) {
        if (word == null || word.isEmpty()) return true;
        String f = OreSpec.family(id), w = word.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "").replaceFirst("_ore$", "").replaceFirst("^deepslate_", "");
        return f.equals(w) || f.endsWith(":" + w);
    }

    /** The family word of a "mine" target or gather family ("iron_ore" -> "iron", "oritech:nickel_ore" -> "oritech:nickel"). */
    public static String familyOf(String target) {
        if (target == null) return "?";
        return target.endsWith("_ore") || target.contains(":") ? OreSpec.family(target.contains(":") ? target : "minecraft:" + target) : target;
    }

    /** The hits chained to {@code start} (each within VEIN_GAP of one already in), start first, at most max. */
    public static List<OreIndex.Hit> vein(List<OreIndex.Hit> hits, OreIndex.Hit start, int max) {
        List<OreIndex.Hit> out = new ArrayList<>();
        out.add(start);
        List<OreIndex.Hit> rest = new ArrayList<>(hits);
        rest.remove(start);
        boolean grew = true;
        while (grew && out.size() < max) {
            grew = false;
            for (int i = 0; i < rest.size() && out.size() < max; i++) {
                OreIndex.Hit h = rest.get(i);
                for (OreIndex.Hit o : out) {
                    if (Math.abs(h.x() - o.x()) <= VEIN_GAP && Math.abs(h.y() - o.y()) <= VEIN_GAP && Math.abs(h.z() - o.z()) <= VEIN_GAP) {
                        out.add(h);
                        rest.remove(i--);
                        grew = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** The end line of a mine (or the seek's reason) when the index knows none in reach. */
    public static String noneText(String family, int chunks) {
        return "no " + family + " ore known in my areas (I have seen " + chunks + " chunk" + (chunks == 1 ? "" : "s") + " here) - explore or mine cave";
    }

    /** The seek's reason (shorter: the job walks on, then ends with {@link #noneText}). */
    public static String noneWhy(String family) {
        return "no " + family + " ore known near";
    }

    /** "ores known [family] [r]": the words after "known" -> {family or null, r}. Default r 64, at most 256. */
    public static Object[] args(String rest) {
        String fam = null;
        int r = 64;
        for (String w : (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+")) {
            if (w.isEmpty() || w.equals("known")) continue;
            if (w.matches("\\d+")) r = Math.max(1, Math.min(256, Integer.parseInt(w)));
            else fam = w;
        }
        return new Object[]{fam, r};
    }

    private static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    static final String CAPPED_NOTE = " (+: some chunk lists only " + io.github.mojolowjo.entropybot.memory.Limits.SENSE_SPOTS
            + " positions of a family, so there are more)";

    private static String n(OreIndex.Count c) { return c.n() + (c.capped() ? "+" : ""); }

    /** The no-family answer: each family's positions within r (counted from the listed positions), nearest first. */
    public static String summary(int r, Map<String, OreIndex.Count> counts, Map<String, OreIndex.Hit> nearest, int chunks, int stale) {
        String seen = chunks + " chunk" + (chunks == 1 ? "" : "s") + " seen within " + r + (stale > 0 ? " (" + stale + " not loaded now)" : "");
        List<String> fams = new ArrayList<>();
        for (Map.Entry<String, OreIndex.Count> e : counts.entrySet()) if (e.getValue().n() > 0 || nearest.containsKey(e.getKey())) fams.add(e.getKey());
        if (fams.isEmpty()) return "no ores known within " + r + " of me (" + seen + ") - explore or mine cave";
        fams.sort((a, b) -> {
            OreIndex.Hit ha = nearest.get(a), hb = nearest.get(b);
            long da = ha == null ? Long.MAX_VALUE : ha.d2(), db = hb == null ? Long.MAX_VALUE : hb.d2();
            return da != db ? Long.compare(da, db) : a.compareTo(b);
        });
        List<String> parts = new ArrayList<>();
        boolean capped = false;
        for (String f : fams) {
            OreIndex.Hit h = nearest.get(f);
            OreIndex.Count c = counts.get(f);
            capped |= c.capped();
            parts.add(f + " " + n(c) + (h == null ? "" : " (nearest " + h.x() + " " + h.y() + " " + h.z() + ", " + (int) Math.round(Math.sqrt(h.d2())) + " away)"));
        }
        return "ores known within " + r + ": " + String.join(", ", parts) + " | " + seen + (capped ? CAPPED_NOTE : "");
    }

    /** The one-family answer: its positions within r (from the listed positions) and up to 8 nearest. */
    public static String family(String fam, int r, OreIndex.Count count, List<OreIndex.Hit> hits, int chunks) {
        String seen = chunks + " chunk" + (chunks == 1 ? "" : "s") + " seen within " + r;
        if (count.n() == 0 && hits.isEmpty()) return "no " + fam + " ore listed within " + r + " of me (" + seen + ")" + (count.capped() ? CAPPED_NOTE : " - explore or mine cave");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < hits.size() && i < 8; i++) {
            OreIndex.Hit h = hits.get(i);
            parts.add(h.x() + " " + h.y() + " " + h.z() + " (" + shortId(h.id()) + ", " + (int) Math.round(Math.sqrt(h.d2())) + " away" + (h.stale() ? ", not loaded" : "") + ")");
        }
        return fam + " ore known within " + r + ": " + n(count) + (parts.isEmpty() ? "" : "; nearest: " + String.join(", ", parts)) + " | " + seen + (count.capped() ? CAPPED_NOTE : "");
    }

    /** The end line when ore is known in reach but none could be reached (never "no ore known" then). */
    public static String unreachableText(String family, int known, int tried) {
        return known + " " + family + " ore known within " + io.github.mojolowjo.entropybot.memory.Limits.SENSE_MINE_R + " but none I could reach (tried " + tried
                + ") - next: mine strip " + family + ", mine cave " + family + ", or dig a box to it (dig x1 y1 z1 x2 y2 z2 ores)";
    }

    /** The most targets one mine run tries before it gives up on the index. */
    public static final int MAX_TARGETS = 5, ROUTE_MAX = 64;

    /**
     * 0.28.0 fix: the cells to dig from the feet (fx fy fz) to stand next to the ore (ox oy oz), like "dig stairs": a 1-wide
     * staircase down (3-high per column) or up (2-high plus head room), then a 2-high tunnel to the cell beside the ore.
     * Each column: {x, yLow, z, yHigh, standY}. Null when it would take more than ROUTE_MAX columns (or the ore is the feet).
     */
    public static List<int[]> route(int fx, int fy, int fz, int ox, int oy, int oz) {
        // the stand cell: beside the ore on the side facing the bot (its longer axis); straight above/below: east of it
        int sx = ox, sz = oz;
        if (fx == ox && fz == oz) sx = ox + 1;
        else if (Math.abs(ox - fx) >= Math.abs(oz - fz)) sx = ox - Integer.signum(ox - fx);
        else sz = oz - Integer.signum(oz - fz);
        List<int[]> out = new ArrayList<>();
        int x = fx, y = fy, z = fz, ldx = 1, ldz = 0;
        while (y != oy) {
            if (out.size() >= ROUTE_MAX) return null;
            int dx = 0, dz = 0;
            if (Math.abs(sx - x) >= Math.abs(sz - z) && sx != x) dx = Integer.signum(sx - x);
            else if (sz != z) dz = Integer.signum(sz - z);
            else {
                // over the stand already: step away from the ore and come back on the flat
                dx = sx != ox ? Integer.signum(sx - ox) : ldx;
                dz = sz != oz ? Integer.signum(sz - oz) : (dx == 0 ? ldz : 0);
            }
            ldx = dx;
            ldz = dz;
            int px = x, pz = z;
            x += dx;
            z += dz;
            if (x == ox && z == oz) return null;
            if (y > oy) {
                y--;
                out.add(new int[]{x, y, z, y + 2, y});
            } else {
                y++;
                out.add(new int[]{x, y, z, y + 1, y});
                out.add(new int[]{px, y + 1, pz, y + 1, Integer.MIN_VALUE});      // head room over the step before
            }
        }
        while (x != sx || z != sz) {
            if (out.size() >= ROUTE_MAX) return null;
            if (sx != x) x += Integer.signum(sx - x);
            else z += Integer.signum(sz - z);
            if (x == ox && z == oz) return null;
            out.add(new int[]{x, y, z, y + 1, y});
        }
        return out;
    }
}