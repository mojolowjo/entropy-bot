package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "farm [here | compact block|prudentium|off]", the bridge's startFarm without the game: it answers settings at once,
 * remembers the farm, and plans a round's steps. The caller saves {@link Reply#save} as places.farm, and starts
 * {@link Reply#start} as a Seq job ("farming"), replying {@code startReply + " (" + note + ")"} when there is a note.
 */
public final class FarmCommand {
    private FarmCommand() {}

    private static final Pattern COMPACT = Pattern.compile("^compact(?:\\s+(block|blocks|prudentium|off))?$");

    /** A round to start: Seq label, its steps, and the "(remembered the farm ...)" note or null. */
    public record Start(String label, List<PlanStep> steps, String note, FarmRound round) {}

    /**
     * The answer: {@code text} (an instant reply, null when a round starts), {@code save} (the farm to remember as
     * places.farm, null when unchanged) and {@code start} (the round, or null).
     */
    public record Reply(String text, FarmSpot save, Start start) {}

    /** "farm compact" and "farm here" are instant, even mid-job (the bridge answers them before the busy check). */
    public static boolean instant(String rest) {
        return rest != null && rest.trim().toLowerCase().matches("^(compact|here)\\b.*");
    }

    /**
     * @param text  what follows "farm"
     * @param known places.farm, or null
     */
    public static Reply handle(String text, FarmSpot known, FarmWorld w) {
        String t = text == null ? "" : text.trim().toLowerCase();
        FarmSpot f = known, save = null;
        String note = null;
        Matcher m = COMPACT.matcher(t);
        if (m.matches()) {
            if (f == null) return new Reply("error: I don't know a farm yet - stand me next to the crops and PM farm", null, null);
            if (m.group(1) == null) return new Reply("farm compacting: " + f.mode() + " (PM \"farm compact block|prudentium|off\")", null, null);
            FarmSpot nf = f.withCompact(m.group(1).equals("blocks") ? "block" : m.group(1));
            return new Reply("ok: farm compacting is now " + nf.compact()
                    + (nf.compact().equals("prudentium") ? " (needs an infusion crystal in my inventory or a base chest)" : ""), nf, null);
        }
        if (!t.isEmpty() && !t.equals("here")) return new Reply("usage: farm [here | compact block|prudentium|off]", null, null);
        int[] me = w.here();
        if (t.equals("here") || f == null) {
            List<FarmRules.Crop> crops = FarmRules.farmCrops(w, me);
            if (crops.isEmpty()) {
                return new Reply(t.equals("here") ? "error: no crops on farmland within " + FarmRules.FARM_R + " blocks of me"
                        : "error: I don't know a farm - stand me next to the crops and PM farm (or \"farm here\")", null, null);
            }
            f = FarmRules.spotFor(crops, w.dim(), f != null ? f.compact() : "block");
            save = f;
            note = "remembered the farm at " + f.fmt() + ", " + crops.size() + " crops";
            if (t.equals("here")) return new Reply("ok: " + note, save, null);
        }
        if (f.dim() != null && !f.dim().equals(w.dim())) return new Reply("error: the farm is in " + f.dim(), save, null);
        List<PlanStep> steps = new ArrayList<>();
        if (FarmRules.distSq(me, f.pos()) > 25) steps.add(PlanStep.walk(f.pos(), true));
        for (String s : FarmRound.STEPS) steps.add(PlanStep.of(s));
        Map<String, Integer> have = w.inventory();
        return new Reply(null, save, new Start("farming", steps, note, new FarmRound(f, "farming", have)));
    }
}
