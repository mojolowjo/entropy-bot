package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Package D: the texts and the game-free parts of the "smelt" sub-verbs (jobs, collect, mode, forget). */
final class SmeltTexts {
    private SmeltTexts() {}

    static final String MODE_USAGE = "usage: smelt mode efficient|wait";

    /** "smelt collect due": the automatic pickup (idle, or between a chain's steps): due jobs only, never a stalled one. */
    static final String AUTO = "due";

    /** The smelt verb's label when the job ends once the furnace runs: "ok: done starting the furnace for 40 iron_ingot; ...". */
    static String startLabel(boolean fetches, String label) {
        return (fetches ? "getting materials, then " : "") + "starting the furnace for " + label;
    }

    /** The sub-verbs that answer at once and never start a job (the dispatcher may run them mid-job). */
    static boolean instant(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        return t.equals("jobs") || t.equals("status") || t.equals("list") || t.equals("mode") || t.startsWith("mode ") || t.startsWith("forget");
    }

    static String modeReply(String mode) {
        return "efficient".equals(mode)
                ? "smelt mode efficient: furnaces run while I do other things; a craft collects their output when it needs it, \"smelt\" picks it up when it is due (\"smelt mode wait\" to stand by the furnace)"
                : "smelt mode wait: I stand by the furnace until it is done (\"smelt mode efficient\" to do other things meanwhile)";
    }

    /** "smelt forget <#id|all>": the bot stops tracking a job (its items stay in the furnace). */
    static String forget(FurnaceJobs fj, String arg) {
        String a = arg.replace("#", "").trim();
        if (a.isEmpty()) return "usage: smelt forget <job number>|all (\"smelt jobs\" lists them)";
        if (a.equals("all")) {
            int n = fj.all().size();
            for (FurnaceJobs.Job j : fj.all()) fj.forget(j);
            return "ok: forgot " + n + " furnace job" + (n == 1 ? "" : "s") + " (whatever is in the furnaces stays there)";
        }
        int id;
        try {
            id = Integer.parseInt(a);
        } catch (NumberFormatException e) {
            return "usage: smelt forget <job number>|all";
        }
        FurnaceJobs.Job j = fj.get(id);
        if (j == null) return "error: I have no furnace job #" + id + " - " + fj.list(System.currentTimeMillis());
        fj.forget(j);
        return "ok: forgot furnace job #" + id + " (" + j.remaining() + " " + CraftPlanner.shortId(j.item) + " at " + j.where() + " stay in the furnace)";
    }

    static String allBusy(int furnaces, List<String> why) {
        return "all " + furnaces + " furnace" + (furnaces == 1 ? "" : "s") + " near me and the base are busy" + (why.isEmpty() ? "" : " (" + String.join("; ", why) + ")")
                + " - I never take out someone else's smelting; try again later";
    }

    /** " (my chests or the RS network have 40 iron_ingot - maybe someone put them away)" when storage has the item. */
    static String storageHint(String item, Map<String, Integer> storage) {
        int n = storage.getOrDefault(item, 0);
        return n > 0 ? " (my chests or the RS network have " + n + " " + CraftPlanner.shortId(item) + " - maybe someone put them away)" : "";
    }
}
