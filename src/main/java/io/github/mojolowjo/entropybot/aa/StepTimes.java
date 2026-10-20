package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AA_STEP3 B (TIME_MODEL): the predicted seconds of each step of a way. A step the game's numbers can price alone is
 * "known": {@code goto x y z} = straight line x 1.3 / walk speed + 0.3 s per block climbed, {@code wait n} = n,
 * {@code craft} = 0.5, {@code place} = 0.25. The rest of the way's price (the option's total in seconds, which the option
 * set built from the same terms) is shared equally between the steps that cannot be priced alone, flagged unknown
 * ({@code known == false}); with no total they stay NaN. The prediction ledger then shows where this split is wrong.
 * Pure (JUnit: SlowModeTest). Loader notes: none.
 */
public final class StepTimes {
    private StepTimes() {}

    /** TIME_MODEL: path length = straight line x 1.3 when no plan exists yet. */
    public static final double PATH_FACTOR = 1.3, CLIMB_S = 0.3, CRAFT_S = 0.5, PLACE_S = 0.25;

    /** One step with its predicted seconds (NaN: none) and whether the game's numbers priced it alone. */
    public record Est(String step, double seconds, boolean known) {}

    private static final Pattern GOTO = Pattern.compile("^goto\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\b.*");
    private static final Pattern WAIT = Pattern.compile("^wait\\s+(\\d+(?:\\.\\d+)?)\\b.*");

    /** A step without the soft-step prefix and case: how the ledger and the plan name it. */
    public static String clean(String step) {
        if (step == null) return "";
        String s = step.trim();
        if (s.startsWith("soft ")) s = s.substring(5).trim();
        return s;
    }

    /** The first word of a step: the ledger groups its error by it ("goto", "mine", "cut"). */
    public static String key(String step) {
        String s = clean(step);
        int k = s.indexOf(' ');
        return (k < 0 ? s : s.substring(0, k)).toLowerCase(Locale.ROOT);
    }

    /**
     * @param steps  the way's steps in order
     * @param from   where the bot stands (x, y, z), null unknown
     * @param totalS the way's price in seconds (the option's total), NaN or 0 when unknown
     */
    public static List<Est> estimate(List<String> steps, int[] from, double totalS) {
        double[] sec = new double[steps.size()];
        boolean[] known = new boolean[steps.size()];
        int[] at = from;
        double knownSum = 0;
        int unknown = 0;
        for (int i = 0; i < steps.size(); i++) {
            String s = clean(steps.get(i)).toLowerCase(Locale.ROOT);
            Matcher g = GOTO.matcher(s);
            Matcher w = WAIT.matcher(s);
            if (g.matches()) {
                int[] to = {Integer.parseInt(g.group(1)), Integer.parseInt(g.group(2)), Integer.parseInt(g.group(3))};
                sec[i] = at == null ? Double.NaN : walkS(at, to);
                known[i] = at != null;
                at = to;
            } else if (w.matches()) {
                sec[i] = Double.parseDouble(w.group(1));
                known[i] = true;
            } else if (s.startsWith("craft ")) {
                sec[i] = CRAFT_S;
                known[i] = true;
            } else if (s.startsWith("place ")) {
                sec[i] = PLACE_S;
                known[i] = true;
            }
            if (known[i]) knownSum += sec[i];
            else unknown++;
        }
        double share = unknown > 0 && totalS > 0 && Double.isFinite(totalS) ? Math.max(0, totalS - knownSum) / unknown : Double.NaN;
        List<Est> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) out.add(new Est(steps.get(i), known[i] ? sec[i] : share, known[i]));
        return out;
    }

    /** TIME_MODEL walk: straight line x 1.3 at walking speed, + 0.3 s per block climbed. */
    public static double walkS(int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[2] - a[2];
        double blocks = Math.sqrt(dx * dx + dz * dz) * PATH_FACTOR;
        double climb = Math.max(0, b[1] - a[1]);
        return Unit.seconds(blocks) + CLIMB_S * climb;
    }

    /** The points the way visits that the steps name (every {@code goto x y z}), from the bot's place; plus the site when given. */
    public static List<int[]> route(List<String> steps, int[] from, int[] site) {
        List<int[]> out = new ArrayList<>();
        if (from != null) out.add(from);
        for (String st : steps) {
            Matcher g = GOTO.matcher(clean(st).toLowerCase(Locale.ROOT));
            if (g.matches()) out.add(new int[]{Integer.parseInt(g.group(1)), Integer.parseInt(g.group(2)), Integer.parseInt(g.group(3))});
        }
        if (site != null) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last == null || last[0] != site[0] || last[1] != site[1] || last[2] != site[2]) out.add(site);
        }
        return out;
    }
}
