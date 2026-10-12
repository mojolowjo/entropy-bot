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

    /** The no-family answer: each family's count within r, nearest first by its nearest spot. */
    public static String summary(int r, Map<String, Integer> totals, Map<String, OreIndex.Hit> nearest, int chunks, int stale) {
        String seen = chunks + " chunk" + (chunks == 1 ? "" : "s") + " seen within " + r + (stale > 0 ? " (" + stale + " not loaded now)" : "");
        if (totals.isEmpty()) return "no ores known within " + r + " of me (" + seen + ") - explore or mine cave";
        List<String> fams = new ArrayList<>(totals.keySet());
        fams.sort((a, b) -> {
            OreIndex.Hit ha = nearest.get(a), hb = nearest.get(b);
            long da = ha == null ? Long.MAX_VALUE : ha.d2(), db = hb == null ? Long.MAX_VALUE : hb.d2();
            return da != db ? Long.compare(da, db) : a.compareTo(b);
        });
        List<String> parts = new ArrayList<>();
        for (String f : fams) {
            OreIndex.Hit h = nearest.get(f);
            parts.add(f + " " + totals.get(f) + (h == null ? "" : " (nearest " + h.x() + " " + h.y() + " " + h.z() + ", " + (int) Math.round(Math.sqrt(h.d2())) + " away)"));
        }
        return "ores known within " + r + ": " + String.join(", ", parts) + " | " + seen;
    }

    /** The one-family answer: the count and up to 8 nearest positions. */
    public static String family(String fam, int r, int count, List<OreIndex.Hit> hits, int chunks) {
        String seen = chunks + " chunk" + (chunks == 1 ? "" : "s") + " seen within " + r;
        if (count == 0 && hits.isEmpty()) return "no " + fam + " ore known within " + r + " of me (" + seen + ") - explore or mine cave";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < hits.size() && i < 8; i++) {
            OreIndex.Hit h = hits.get(i);
            parts.add(h.x() + " " + h.y() + " " + h.z() + " (" + shortId(h.id()) + ", " + (int) Math.round(Math.sqrt(h.d2())) + " away" + (h.stale() ? ", not loaded" : "") + ")");
        }
        return fam + " ore known within " + r + ": " + count + " in all" + (parts.isEmpty() ? "" : "; nearest: " + String.join(", ", parts)) + " | " + seen;
    }
}
