package io.github.mojolowjo.entropybot.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Package D (TO-LOOK-AT-LATER 10): a craft plan's steps in the order the bot does them. In "efficient" mode every
 * furnace step starts as soon as its input is there (several furnaces at once), the crafts that don't need its output
 * run meanwhile, and its output is collected right before the first step that uses it (only as much as that step
 * needs: the rest keeps smelting). In "wait" mode (the old way) each furnace step is started and collected in place.
 * No Minecraft types.
 */
public final class FurnacePlan {
    private FurnacePlan() {}

    public sealed interface Action permits CraftRun, Start, Collect {}

    /** Crafting steps run in a row (one craft job). */
    public record CraftRun(List<Crafter.Craft> crafts) implements Action {}

    /** Put the furnace step {@code key} (its index in the plan) into a free furnace. */
    public record Start(int key, Crafter.Smelt smelt) implements Action {}

    /**
     * Collect the output of furnace step {@code key}: until the bag holds {@code need} of it (what the next step uses),
     * or, with {@code all}, the whole job.
     */
    public record Collect(int key, Crafter.Smelt smelt, int need, boolean all) implements Action {}

    /** How much of {@code item} a plan step uses (a craft: every cell that takes it, times its crafts; a furnace: its input). */
    public static int uses(Crafter.Step step, String item, Function<String, RecipeData> recipes) {
        if (step instanceof Crafter.Smelt s) return s.input().equals(item) ? s.n() : 0;
        Crafter.Craft c = (Crafter.Craft) step;
        RecipeData r = recipes.apply(c.recipeId());
        if (r == null) return 0;
        int n = 0;
        for (Crafter.Need need : r.needs()) if (need.alts().contains(item)) n += need.amount() * c.times();
        return n;
    }

    /**
     * The plan's actions. {@code collectTargets}: a furnace step whose output no later step uses (the asked-for item)
     * is collected at the end (a craft job ends with the item in the bag); without it, it is left for the pickup (the
     * "smelt" verb in efficient mode: the job ends once the furnace runs).
     */
    public static List<Action> order(List<? extends Crafter.Step> plan, Function<String, RecipeData> recipes, boolean efficient, boolean collectTargets) {
        List<Action> out = new ArrayList<>();
        int n = plan.size();
        if (!efficient) {
            for (int i = 0; i < n; i++) {
                Crafter.Step s = plan.get(i);
                if (s instanceof Crafter.Smelt sm) {
                    out.add(new Start(i, sm));
                    boolean consumed = false;
                    for (int j = i + 1; j < n && !consumed; j++) consumed = uses(plan.get(j), sm.item(), recipes) > 0;
                    if (consumed || collectTargets) out.add(new Collect(i, sm, sm.want(), true));
                } else {
                    addCraft(out, (Crafter.Craft) s);
                }
            }
            return out;
        }
        // producers of each furnace step's input, and every step's consumers
        Map<Integer, List<Integer>> producers = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (!(plan.get(i) instanceof Crafter.Smelt sm)) continue;
            List<Integer> p = new ArrayList<>();
            for (int j = 0; j < i; j++) if (plan.get(j).item().equals(sm.input())) p.add(j);
            producers.put(i, p);
        }
        boolean[] emitted = new boolean[n];          // crafts done / furnace steps started
        Set<Long> fed = new HashSet<>();             // (smelt, consumer) pairs whose collect is in
        while (true) {
            boolean progress = false;
            // 1. every furnace step whose input is ready starts now (plan order)
            for (int i = 0; i < n; i++) {
                if (emitted[i] || !(plan.get(i) instanceof Crafter.Smelt sm)) continue;
                boolean ready = true;
                for (int p : producers.get(i)) {
                    if (plan.get(p) instanceof Crafter.Smelt) ready &= fed.contains(pair(p, i));
                    else ready &= emitted[p];
                }
                if (ready) {
                    out.add(new Start(i, sm));
                    emitted[i] = true;
                    progress = true;
                }
            }
            // 2. the next craft, once the furnace steps it uses have started (their output collected right before it)
            int next = -1;
            for (int j = 0; j < n && next < 0; j++) if (!emitted[j] && plan.get(j) instanceof Crafter.Craft) next = j;
            if (next >= 0) {
                boolean blocked = false;
                for (int i = 0; i < next; i++) {
                    if (plan.get(i) instanceof Crafter.Smelt && !emitted[i] && uses(plan.get(next), plan.get(i).item(), recipes) > 0) blocked = true;
                }
                if (!blocked) {
                    for (int i = 0; i < next; i++) {
                        if (!(plan.get(i) instanceof Crafter.Smelt sm)) continue;
                        int u = uses(plan.get(next), sm.item(), recipes);
                        if (u > 0 && fed.add(pair(i, next))) out.add(new Collect(i, sm, u, false));
                    }
                    addCraft(out, (Crafter.Craft) plan.get(next));
                    emitted[next] = true;
                    continue;
                }
            }
            // 3. nothing to craft yet: a furnace step waiting on another one's output gets it collected
            boolean fedOne = false;
            for (int i = 0; i < n && !fedOne; i++) {
                if (emitted[i] || !(plan.get(i) instanceof Crafter.Smelt sm)) continue;
                for (int p : producers.get(i)) {
                    if (plan.get(p) instanceof Crafter.Smelt psm && emitted[p] && fed.add(pair(p, i))) {
                        out.add(new Collect(p, psm, sm.n(), false));
                        fedOne = true;
                    }
                }
            }
            if (!progress && !fedOne) break;
        }
        // never drop a step: anything still left runs the old way, in plan order (not expected to happen)
        for (int i = 0; i < n; i++) {
            if (emitted[i]) continue;
            if (plan.get(i) instanceof Crafter.Smelt sm) {
                out.add(new Start(i, sm));
                out.add(new Collect(i, sm, sm.want(), true));
            } else {
                addCraft(out, (Crafter.Craft) plan.get(i));
            }
        }
        // the asked-for items: furnace steps nothing later uses
        if (collectTargets) {
            for (int i = 0; i < n; i++) {
                if (!(plan.get(i) instanceof Crafter.Smelt sm)) continue;
                boolean consumed = false;
                for (int j = i + 1; j < n && !consumed; j++) consumed = uses(plan.get(j), sm.item(), recipes) > 0;
                if (!consumed) out.add(new Collect(i, sm, sm.want(), true));
            }
        }
        return out;
    }

    private static long pair(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private static void addCraft(List<Action> out, Crafter.Craft c) {
        if (!out.isEmpty() && out.get(out.size() - 1) instanceof CraftRun run) {
            List<Crafter.Craft> l = new ArrayList<>(run.crafts());
            l.add(c);
            out.set(out.size() - 1, new CraftRun(List.copyOf(l)));
        } else {
            out.add(new CraftRun(List.of(c)));
        }
    }

    /**
     * A rough time for the actions in ticks (for comparing the two modes): {@code walk} ticks to a furnace and back to
     * work, {@code craftTicks} per craft step, a furnace item every {@code smeltTicks}; a collect waits for the whole
     * job (pessimistic for an early collect).
     */
    public static long estimateTicks(List<Action> actions, int walk, int craftTicks, int smeltTicks) {
        long t = 0;
        Map<Integer, Long> ready = new HashMap<>();
        for (Action a : actions) {
            if (a instanceof Start s) {
                t += walk + 20;
                ready.put(s.key(), t + (long) s.smelt().n() * smeltTicks);
            } else if (a instanceof Collect c) {
                t += walk;
                t = Math.max(t, ready.getOrDefault(c.key(), t)) + 10;
            } else if (a instanceof CraftRun r) {
                t += (long) r.crafts().size() * craftTicks;
            }
        }
        return t;
    }
}
