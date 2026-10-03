package io.github.mojolowjo.entropybot.farm;

import java.util.Map;

/**
 * A step the farm or compact logic asks the step engine (commands/Seq) to run, game-free. The wiring maps each one to
 * a Seq.Step:
 * <ul>
 *   <li>"walk" pos, near: Seq's walk step ({@code Step.walk(pos, near)})</li>
 *   <li>"open" pos: Seq's open step, machine "no"</li>
 *   <li>"close": Seq's close step</li>
 *   <li>"take" pos, item, n: Seq's "ops" step with one op {op: take, id: item, n: n, roles: TAKE_ROLES}</li>
 *   <li>"put" keep: Seq's put step, items = keep (item -> how many the bot keeps), no fallback</li>
 *   <li>"craftitem" item, n, optional: craft n of item (Crafter.plan + its steps at the nearest table); optional =
 *       a craft that can't start is skipped instead of failing the job (the bridge's craftitem step)</li>
 *   <li>"farmgrow" "farmharvest" "farmgather" "farmcompact" "farmdone" "farmdeposit" "farmnote": {@link FarmRound}</li>
 *   <li>"compacthere" pos (the center), label (its name or "x y z"); "compactchest" pos, again; "compactdone":
 *       {@link Compact.Run}</li>
 * </ul>
 */
public record PlanStep(String type, int[] pos, boolean near, String item, int n, boolean optional, boolean again,
                       Map<String, Integer> keep, String label) {

    public static PlanStep of(String type) { return new PlanStep(type, null, false, null, 0, false, false, null, null); }

    public static PlanStep walk(int[] pos, boolean near) { return new PlanStep("walk", pos, near, null, 0, false, false, null, null); }

    public static PlanStep open(int[] pos) { return new PlanStep("open", pos, false, null, 0, false, false, null, null); }

    public static PlanStep close() { return of("close"); }

    public static PlanStep take(int[] pos, String item, int n) { return new PlanStep("take", pos, false, item, n, false, false, null, null); }

    public static PlanStep put(Map<String, Integer> keep) { return new PlanStep("put", null, false, null, 0, false, false, keep, null); }

    public static PlanStep craft(String item, int n, boolean optional) {
        return new PlanStep("craftitem", null, false, item, n, optional, false, null, null);
    }

    public static PlanStep compactHere(int[] center, String label) {
        return new PlanStep("compacthere", center, false, null, 0, false, false, null, label);
    }

    public static PlanStep compactChest(int[] pos, boolean again) {
        return new PlanStep("compactchest", pos, false, null, 0, false, again, null, null);
    }

    @Override public String toString() {
        return type + (pos != null ? " " + pos[0] + " " + pos[1] + " " + pos[2] : "") + (near ? " near" : "")
                + (item != null ? " " + item + " " + n : "") + (optional ? " optional" : "") + (again ? " again" : "")
                + (keep != null ? " keep " + keep : "") + (label != null ? " (" + label + ")" : "");
    }
}
