package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.threat.ThreatFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AA_ROUND4 B3: the hand numbers of the brain, the night and the danger desire in one place, each with its name, its
 * default, the run that set it and why. {@code aa tune <name> <value>} changes one for this world (the adapter saves the
 * changed ones as {@code aa-tuning.json} in the world's folder) so a three-game round can try a number without a build;
 * {@code aa tune} lists them. Only the numbers listed here are read through it (the code reads {@link #get} where the
 * number is used). Pure; loader notes: none (the file is the adapter's).
 */
public final class Tuning {
    private Tuning() {}

    /** One number: name, default, value now, the run that set it, why. */
    public record Entry(String name, double def, double value, String run, String why) {}

    private static final Map<String, Entry> ALL = new LinkedHashMap<>();

    static {
        def("flow", Curves.FLOW, "AA_ROUND2 4", "a prerequisite is worth at least this share of what it unlocks");
        def("shelterAt1", Costs.SHELTER_AT_1, "aa-costs", "the full-night shelter's score at night danger 1 (the one anchor)");
        def("torchCut", Costs.TORCH_CUT, "AA_ROUND4 B2", "each torch within 16 cuts the night danger by this share");
        def("lightFloor", Costs.LIGHT_FLOOR, "AA_ROUND4 B2", "the most the torches may cut the night danger to");
        def("swordValue", Costs.SWORD, "aa-night2 P0 2", "a weapon's worth once a zombie was seen, added to the tools need");
        def("chestMin", 35, "aa-kit camp", "the camp chest's least worth");
        def("hidePerHp", 7, "aa-night2 P0 1", "the hide's score per missing health point x the night danger");
        def("danger.hpS", 6, "AA_CLEANUP 4", "seconds one health point is worth at full health (x max/health below it)");
        def("danger.deathS", 300, "AA_CLEANUP 4", "seconds a likely death costs (the kit, the walk back, the night lost)");
        def("danger.horizonS", 10, "AA_CLEANUP 4", "the seconds of staying put the danger desire looks at");
        def("danger.fleeBack", 0.6, "AA_CLEANUP 4", "the share of the staying damage a flee still takes later (they follow)");
        def("danger.minS", 30, "AA_CLEANUP 4", "seconds a way must save over staying before the danger desire wants it");
        def("danger.holdS", 30, "AA_CLEANUP 4", "seconds a dug-in or pillared bot waits before it works again");
        def("danger.coverS", 15, "AA_CLEANUP 4", "seconds the cover from a ranged mob is held");
        def("danger.perS", 0.25, "AA_CLEANUP 4", "the danger desire's score per second the best way saves (300 s = urgent 75)");
        // AA_STEP2 1: the night ways (round 5: night.hide and night.shelter cancelled each other; one danger desire now)
        def("danger.fightHit", ThreatFacts.ARMED, "AA_STEP2 1", "the least weapon hit the fight way needs (a wooden sword 4)");
        def("danger.boxBlocks", 12, "AA_STEP2 1", "blocks the built shell (box) needs in the bag (ShelterPlan: 12 cells)");
        def("danger.boxS", 20, "AA_STEP2 1", "seconds to build the box (12 blocks, the torch inside)");
        def("danger.burrowS", 6, "AA_STEP2 1", "seconds to dig down two and seal over the head");
        def("danger.bedS", 20, "AA_STEP2 1", "seconds to reach a known bed and lie down (no bed distance in the facts)");
        def("danger.nightHpS", 0.05, "AA_STEP2 1", "health per second the open night costs at night danger 1 (fresh spawns, while a way is set up)");
        def("danger.openS", 60, "AA_STEP2 1", "seconds a pillar top stays open to arrows and phantoms at night");
    }

    private static void def(String name, double v, String run, String why) { ALL.put(name, new Entry(name, v, v, run, why)); }

    /** The value now (the default unless tuned); an unknown name is a bug: NaN. */
    public static double get(String name) {
        Entry e = ALL.get(name);
        return e == null ? Double.NaN : e.value();
    }

    /** Sets one; returns the reply line ("ok: ..." or "error: ..."). */
    public static String set(String name, double v) {
        Entry e = ALL.get(name);
        if (e == null) return "error: no tuning \"" + name + "\" - aa tune lists them";
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return "error: " + name + " must be a number 0 or more";
        ALL.put(name, new Entry(e.name(), e.def(), v, e.run(), e.why()));
        return "ok: " + name + " " + fmt(v) + (v == e.def() ? " (the default)" : " (default " + fmt(e.def()) + ")");
    }

    /** All back to the defaults (a new world). */
    public static void reset() {
        for (Entry e : List.copyOf(ALL.values())) ALL.put(e.name(), new Entry(e.name(), e.def(), e.def(), e.run(), e.why()));
    }

    /** The tuned (not default) values, for the world's file. */
    public static Map<String, Double> changed() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Entry e : ALL.values()) if (e.value() != e.def()) m.put(e.name(), e.value());
        return m;
    }

    /** Loads saved values; unknown names and bad numbers are returned as errors (the rest load). */
    public static List<String> load(Map<String, Double> saved) {
        reset();
        List<String> errors = new ArrayList<>();
        if (saved == null) return errors;
        for (var e : saved.entrySet()) {
            String r = set(e.getKey(), e.getValue() == null ? Double.NaN : e.getValue());
            if (r.startsWith("error")) errors.add(r.substring(7));
        }
        return errors;
    }

    /** "flow 0.9 (AA_ROUND2 4: why)" per number; a tuned one shows its default. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Entry e : ALL.values())
            out.add(e.name() + " " + fmt(e.value()) + (e.value() != e.def() ? " (tuned, default " + fmt(e.def()) + ")" : "") + " - " + e.run() + ": " + e.why());
        return out;
    }

    public static java.util.Set<String> names() { return ALL.keySet(); }

    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", ""); }
}
