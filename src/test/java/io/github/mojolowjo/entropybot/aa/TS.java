package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.List;

/**
 * rm-execution2 test helper: a routine the tests write as the planner would ("craft x 1 then smelt y 2") typed the way the
 * planner's side types it (commands.PlanLines, via FakePort.steps). Tests only; AA never parses text.
 */
public final class TS {
    private TS() {}

    /** The routine as typed steps; a line with no typed step fails the test at once. */
    public static List<Step> s(String routine) {
        List<Step> out = io.github.mojolowjo.entropybot.aa.core.FakePort.steps(routine.replace(" > ", " then "));
        if (out == null) throw new AssertionError("no typed step in: " + routine);
        return out;
    }

    /** One planner goal as a need's goal steps. */
    public static List<Step> plan(String goal) { return List.of(new Step.Plan(goal)); }

    /** The steps with every compound step replaced by its parts (as aa/moves/Steps runs them), as their label. */
    public static String flat(List<Step> steps) {
        List<Step> out = new java.util.ArrayList<>();
        for (Step s : steps) if (s instanceof Step.Compound c) out.addAll(c.parts()); else out.add(s);
        return Step.label(out);
    }

    /** The steps' label (what AA logs). */
    public static String label(List<Step> steps) { return steps == null ? null : Step.label(steps); }
}
