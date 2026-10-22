package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * rm-execution2: the planner's side of the boundary. The planner's routine (its own text, e.g. "craft iron_pickaxe 1",
 * "smelt raw_iron 3") is typed ONCE here into {@link Step}s when AA asks for a goal (AaRuntime.resolveState, StepHands'
 * Plan step); AA never reads or builds the routine's text. The owner's typed verbs (hunt) read their words here too. A
 * line with no typed step is named (the plan is NONE with it: no silent fallback). Pure (JUnit: TypedOptionsTest).
 */
public final class PlanLines {
    private PlanLines() {}

    /** The typed steps, or null with {@code bad[0]} = the first line that has none. */
    public static List<Step> of(List<String> lines, String[] bad) {
        List<Step> out = new ArrayList<>();
        for (String l : lines) {
            Step s = one(l);
            if (s == null) {
                if (bad != null && bad.length > 0) bad[0] = l;
                return null;
            }
            out.add(s);
        }
        return out;
    }

    /** One line as a typed step, or null. */
    public static Step one(String line) {
        if (line == null) return null;
        String t = line.trim();
        String[] w = t.split("\\s+");
        String v = w[0].toLowerCase(Locale.ROOT);
        try {
            switch (v) {
                case "goto" -> {
                    if (w.length == 4) return new Step.Walk(i(w[1]), i(w[2]), i(w[3]), 0);
                }
                case "craft" -> {
                    if (w.length == 3) return new Step.Craft(w[1], i(w[2]));
                    if (w.length == 2) return new Step.Craft(w[1], 1);
                }
                case "get" -> {
                    if (w.length == 3) return new Step.Fetch(w[1], i(w[2]));
                }
                case "shelter" -> {
                    if (w.length == 1) return new Step.Shelter();
                }
                case "deposit" -> {
                    if (w.length == 1) return new Step.Deposit();
                }
                case "sleep" -> {
                    if (w.length == 1) return new Step.Sleep();
                }
                case "explore" -> {
                    if (w.length == 2) return new Step.Explore(i(w[1]));
                    if (w.length == 4 && w[2].equals("until")) return new Step.Explore(i(w[1]), w[3]);     // live-facts
                }
                case "drop" -> {
                    if (w.length == 3) return new Step.DropJunk(w[1], i(w[2]));
                }
                case "smelt" -> {
                    if (t.equals("smelt collect all")) return new Step.SmeltCollect();
                    if (w.length == 3) return new Step.Smelt(w[1], i(w[2]), false);
                    if (w.length == 4 && w[3].equals("here")) return new Step.Smelt(w[1], i(w[2]), true);
                }
                case "cook" -> {
                    if (w.length == 3) return new Step.Cook(w[1], i(w[2]));
                }
                case "climb" -> {
                    if (w.length == 1) return new Step.Climb();
                }
                case "scan" -> {
                    if (w.length == 1) return new Step.Scan();
                    if (w.length == 2 && w[1].equals("base")) return new Step.Scan(true);
                }
                case "wait" -> {
                    if (w.length == 2) return new Step.Wait(i(w[1]));
                }
                case "find" -> {
                    if (w.length == 3 && w[1].equals("cave")) return new Step.FindCave(i(w[2]));
                }
                case "restore" -> {
                    if (t.equals("restore keep pillar")) return new Step.Restore(true, 0);
                    if (w.length == 3 && w[1].equals("placed")) return new Step.Restore(false, i(w[2]));
                }
                case "dig" -> {
                    if (w.length >= 7) {
                        boolean ores = false, via = false;
                        for (int k = 7; k < w.length; k++) {
                            if (w[k].equals("ores")) ores = true;
                            else if (w[k].equals("via") && k + 1 < w.length && w[k + 1].equals("engine")) { via = true; k++; }
                            else return null;
                        }
                        return new Step.Dig(new int[]{i(w[1]), i(w[2]), i(w[3])}, new int[]{i(w[4]), i(w[5]), i(w[6])}, ores, via);
                    }
                }
                case "place" -> {
                    if (w.length >= 3 && w[1].equals("mine")) {
                        if (w.length == 3) return new Step.MarkMine(null, w[2]);
                        if (w.length == 6) return new Step.MarkMine(new int[]{i(w[2]), i(w[3]), i(w[4])}, w[5]);
                        return null;
                    }
                    if (w.length == 5) return Step.Build.one(w[1], i(w[2]), i(w[3]), i(w[4]));
                }
                case "gather" -> {
                    if (w.length == 3 && (w[1].equals("logs") || w[1].equals("wood"))) return new Step.ChopTree(null, i(w[2]), null);
                    if (w.length == 3) return new Step.Collect(w[1], i(w[2]));
                }
                case "cut" -> {
                    // the planner's "cut N logs" (any log); a log block id names the kind
                    if (w.length == 2 || (w.length == 3 && (w[2].equals("logs") || w[2].equals("wood")))) return new Step.ChopTree(null, i(w[1]), null);
                    if (w.length == 3) return new Step.ChopTree(null, i(w[1]), w[2].contains(":") ? w[2] : "minecraft:" + w[2]);
                }
                case "mine" -> { return mine(w); }
                case "hunt" -> { return hunt(w); }
                default -> { }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static Step mine(String[] w) {
        // mine F 1 at x y z dig|walk | mine cave F n | mine strip F n | mine F n [dig]
        if (w.length == 8 && w[3].equals("at") && (w[7].equals("dig") || w[7].equals("walk")))
            return new Step.MineAt(w[1], i(w[4]), i(w[5]), i(w[6]), w[7].equals("dig"));
        if (w.length == 4 && (w[1].equals("cave") || w[1].equals("strip")))
            return new Step.Mine(w[2], i(w[3]), w[1].equals("cave") ? Step.Mine.How.CAVE : Step.Mine.How.STRIP);
        if (w.length == 3) return new Step.Mine(w[1], i(w[2]), Step.Mine.How.KNOWN);
        if (w.length == 4 && w[3].equals("dig")) return new Step.Mine(w[1], i(w[2]), Step.Mine.How.DIG);
        return null;
    }

    private static Step hunt(String[] w) {
        // hunt n animal [for item count] [at x z]
        if (w.length < 3) return null;
        int n = i(w[1]);
        String type = w[2].contains(":") ? w[2] : "minecraft:" + w[2];
        int k = 3;
        String forItem = null;
        int forCount = 0;
        if (k + 2 < w.length && w[k].equals("for")) {
            forItem = w[k + 1];
            forCount = i(w[k + 2]);
            k += 3;
        }
        int[] at = null;
        if (k < w.length) {
            if (k + 3 != w.length || !w[k].equals("at")) return null;
            at = new int[]{i(w[k + 1]), i(w[k + 2])};
        }
        return new Step.HuntAt(type, n, at, forItem, forCount);
    }

    private static int i(String s) { return Integer.parseInt(s); }
}
