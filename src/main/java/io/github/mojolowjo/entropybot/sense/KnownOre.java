package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.strip.OreSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.28.0: the jobs' words over the ore index: the family match ("iron" takes iron_ore and deepslate_iron_ore; "nickel"
 * takes oritech:nickel_ore), the "ores known" answer and the end line when the index had nothing. rm-mining: the old
 * mine job's picks (the vein of index hits, the side tunnel, the dug route, the 5-target cap and its texts) are gone;
 * the per-ore step's vein is MineRules.vein over the loaded world. Plain Java, JUnit. Loader notes: none.
 */
public final class KnownOre {
    private KnownOre() {}

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

    /** The end line of a mine when no cell of the family is known (seen, under fair play) in reach. */
    public static String noneText(String family, int chunks) {
        return "no " + family + " ore known (I have seen " + chunks + " chunk" + (chunks == 1 ? "" : "s") + " here) - explore or mine cave";
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

}
