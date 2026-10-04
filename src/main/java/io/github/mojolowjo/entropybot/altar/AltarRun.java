package io.github.mojolowjo.entropybot.altar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.mojolowjo.entropybot.altar.AltarPlan.fmt;
import static io.github.mojolowjo.entropybot.altar.AltarPlan.same;
import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Package E: the infusion altar, round by round, as a tick loop over an {@link AltarWorld} (the Seq step "infuse"
 * calls {@link #tick} every 2 ticks while the bot stands by the altar). A round: look (anything that isn't the bot's
 * own: stop, untouched), take back its own leftovers that don't fit this round, put the base on the altar and one item
 * on each pedestal (each click checked a few ticks later), press the button, wait for the output, take it with an empty
 * hand. Every click is noted in {@link AltarMemory} first, so a stop, a crash or a failure never leaves an item the bot
 * wouldn't know as its own; a failure takes the bot's own items back before it ends.
 */
public final class AltarRun {
    /** Ticks after a click before the slot is looked at, and the most a click may take to show. */
    public static final int VERIFY = 8, CLICK_WAIT = 40;
    /** Ticks for the altar to start after the button (then it is pressed again, {@link #PRESSES} times in all). */
    public static final int START_WAIT = 400;
    /** The most a craft may take once it runs (the hand run: ~3 s). */
    public static final int MAX_WAIT = 3600;
    public static final int PRESSES = 2;
    /** Tries per click (the place, the take, the take-back). */
    public static final int TRIES = 2;

    public enum State { WAIT, DONE, FAIL }

    /** {@code text}: the end note (DONE) or why it failed (FAIL, without "error: "); {@code status}: for the job's status line. */
    public record Out(State state, String text, String status) {}

    private final AltarPlan.Layout layout;
    private final List<AltarPlan.Target> targets;
    private final String seed;
    private final int n;
    private final AltarMemory mem;
    private final String label;

    private int made, recovered;
    private String lost, failWhy, status;
    private String stage = "check", after;
    private long stageTick = Long.MIN_VALUE / 4, pressTick;
    private int tries, presses, bagBefore, ti = -1;
    private String takeId;
    private boolean recovering;
    private final Set<Integer> placed = new HashSet<>();
    private final List<int[]> todo = new ArrayList<>();

    public AltarRun(AltarPlan.Layout layout, AltarPlan.Pick pick, String seed, int n, AltarMemory mem) {
        this.layout = layout;
        this.targets = AltarPlan.targets(layout, pick);
        this.seed = seed;
        this.n = n;
        this.mem = mem;
        this.label = AltarPlan.label(n, seed);
    }

    public int made() { return made; }

    public int recovered() { return recovered; }

    public String stage() { return stage; }

    /**
     * A reflex (a fight, a meal) held the job: look again from the start of the round (what is placed stays placed and
     * is found again), unless the altar is crafting or its output is being taken (that carries on).
     */
    public void interrupted() {
        if (stage.equals("wait") || stage.equals("taken") || stage.equals("take")) return;
        if (stage.equals("retrieve") || stage.equals("retrieved")) {
            stage = "retrieve";
            return;
        }
        if (!stage.equals("failed") && !stage.equals("finished")) stage = "check";
    }

    private Out waitOut() { return new Out(State.WAIT, null, status); }

    private void go(String s, long now) {
        stage = s;
        stageTick = now;
    }

    /** One step. */
    public Out tick(AltarWorld w, long now) {
        switch (stage) {
            case "check": return check(w, now);
            case "place": return place(w, now);
            case "verify": return verify(w, now);
            case "press": return press(w, now);
            case "wait": return waitFor(w, now);
            case "take": return take(w, now);
            case "taken": return taken(w, now);
            case "retrieve": return retrieve(w, now);
            case "retrieved": return retrieved(w, now);
            case "end": return end(w, now);
            case "finished": return new Out(State.DONE, AltarPlan.done(made, seed, layout.altar(), recovered, lost), status);
            case "failed": return new Out(State.FAIL, failText(), status);
            default: return new Out(State.FAIL, "unknown altar stage " + stage, status);
        }
    }

    private String failText() {
        return failWhy + (made > 0 ? " (made " + made + " of " + n + " " + shortId(seed) + " first; they are in my bag)" : "");
    }

    /** Fails after taking back what is the bot's own on the altar and its pedestals. */
    private Out fail(String why, AltarWorld w, long now) {
        if (failWhy != null) {
            // a failure while taking things back after a failure: say both, stop
            failWhy = failWhy + "; and " + why;
            stage = "failed";
            return new Out(State.FAIL, failText(), status);
        }
        failWhy = why;
        todo.clear();
        todo.addAll(ownOnIt(w));
        after = "failed";
        tries = 0;
        go("retrieve", now);
        status = label + " - taking my things back off the altar";
        return waitOut();
    }

    /** Every slot holding exactly what the memory says the bot put there. */
    private List<int[]> ownOnIt(AltarWorld w) {
        List<int[]> out = new ArrayList<>();
        List<int[]> all = new ArrayList<>();
        all.add(layout.altar());
        all.addAll(layout.pedestals());
        for (int[] p : all) {
            Map<Integer, AltarWorld.Stack> it = w.items(p);
            AltarWorld.Stack s = it == null ? null : it.get(0);
            String mine = mem.placed(p);
            if (s != null && s.id().equals(mine)) out.add(p);
            else if (s == null && mine != null) mem.forget(p);
        }
        return out;
    }

    private Out check(AltarWorld w, long now) {
        if (!w.ready()) return waitOut();
        status = label + " - looking at the altar";
        AltarPlan.Survey sv = AltarPlan.survey(layout, targets, w, mem);
        if (sv.foreign() != null) return fail(sv.foreign(), w, now);
        if (sv.ownOutput()) {
            recovering = true;
            takeId = sv.outputId();
            tries = 0;
            go("take", now);
            return waitOut();
        }
        if (!sv.retrieve().isEmpty()) {
            todo.clear();
            todo.addAll(sv.retrieve());
            after = "check";
            tries = 0;
            go("retrieve", now);
            return waitOut();
        }
        placed.clear();
        for (int i = 0; i < targets.size(); i++) for (int[] r : sv.ready()) if (same(r, targets.get(i).pos())) placed.add(i);
        // the whole round in the bag before the first click (a short bag never leaves half a round on the pedestals)
        Map<String, Integer> need = new LinkedHashMap<>();
        for (int i = 0; i < targets.size(); i++) if (!placed.contains(i)) need.merge(targets.get(i).item(), 1, Integer::sum);
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int have = w.bag(e.getKey());
            if (have < e.getValue()) {
                return fail("I have " + have + " " + shortId(e.getKey()) + " and seed " + (made + 1) + " needs " + e.getValue() + " more", w, now);
            }
        }
        if (w.freeSlots() < 1) return fail("my inventory is full - no room for the seed", w, now);
        tries = 0;
        go("place", now);
        return waitOut();
    }

    private Out place(AltarWorld w, long now) {
        if (!w.ready()) return waitOut();
        ti = -1;
        for (int i = 0; i < targets.size() && ti < 0; i++) if (!placed.contains(i)) ti = i;
        if (ti < 0) {
            presses = 0;
            go("press", now);
            return waitOut();
        }
        AltarPlan.Target t = targets.get(ti);
        String where = t.altar() ? "the altar at " + fmt(t.pos()) : "the pedestal at " + fmt(t.pos());
        Map<Integer, AltarWorld.Stack> it = w.items(t.pos());
        if (it == null) return fail("I can't read " + where, w, now);
        AltarWorld.Stack s = it.get(0);
        if (s != null) {
            if (s.id().equals(mem.placed(t.pos())) && s.id().equals(t.item())) {
                placed.add(ti);
                return waitOut();
            }
            return fail(AltarPlan.notMine(where + " holds " + s.count() + " " + shortId(s.id())), w, now);
        }
        if (w.bag(t.item()) < 1) return fail("I ran out of " + shortId(t.item()) + " (for " + where + ")", w, now);
        status = label + " - putting " + shortId(t.item()) + " on " + where;
        mem.place(t.pos(), t.item());
        String err = w.use(t.pos(), t.item());
        if (err != null) return fail("couldn't put " + shortId(t.item()) + " on " + where + ": " + err, w, now);
        tries++;
        go("verify", now);
        return waitOut();
    }

    private Out verify(AltarWorld w, long now) {
        if (now - stageTick < VERIFY) return waitOut();
        AltarPlan.Target t = targets.get(ti);
        String where = t.altar() ? "the altar at " + fmt(t.pos()) : "the pedestal at " + fmt(t.pos());
        Map<Integer, AltarWorld.Stack> it = w.items(t.pos());
        AltarWorld.Stack s = it == null ? null : it.get(0);
        if (s != null && s.id().equals(t.item())) {
            placed.add(ti);
            tries = 0;
            go("place", now);
            return waitOut();
        }
        if (s != null) return fail(where + " holds " + shortId(s.id()) + " instead of my " + shortId(t.item()) + " - somebody else is using the altar", w, now);
        if (now - stageTick < CLICK_WAIT) return waitOut();
        if (tries < TRIES) {
            go("place", now);
            return waitOut();
        }
        return fail(where + " didn't take the " + shortId(t.item()), w, now);
    }

    private Out press(AltarWorld w, long now) {
        if (!w.ready()) return waitOut();
        if (w.freeSlots() < 1) return fail("my inventory is full - no room for the seed", w, now);
        status = label + " - pressing the button";
        mem.pressed(seed);
        String err = w.use(layout.button(), null);
        if (err != null) return fail("couldn't press the button at " + fmt(layout.button()) + ": " + err, w, now);
        presses++;
        pressTick = now;
        go("wait", now);
        return waitOut();
    }

    private Out waitFor(AltarWorld w, long now) {
        if (now - stageTick < 10) return waitOut();
        stageTick = now;
        status = label + " - waiting for the altar (seed " + (made + 1) + " of " + n + ")";
        Map<Integer, AltarWorld.Stack> alt = w.items(layout.altar());
        if (alt == null) return waitOut();
        AltarWorld.Stack out = alt.get(1), in = alt.get(0);
        if (out != null) {
            takeId = out.id();
            recovering = false;
            tries = 0;
            go("take", now);
            return waitOut();
        }
        if (in == null) {
            mem.pressed(null);
            return fail("the " + shortId(targets.get(0).item()) + " on the altar is gone and nothing was made - somebody took it?", w, now);
        }
        long in2 = now - pressTick;
        if (Boolean.TRUE.equals(w.active(layout.altar()))) {
            if (in2 > MAX_WAIT) return fail("the altar has been crafting for " + in2 / 20 + " s without a seed", w, now);
            return waitOut();
        }
        if (in2 > START_WAIT) {
            if (presses < PRESSES) {
                go("press", now);
                return waitOut();
            }
            mem.pressed(null);
            return fail("the altar didn't start after " + presses + " presses of the button - what is on it doesn't make " + shortId(seed) + "?", w, now);
        }
        return waitOut();
    }

    private Out take(AltarWorld w, long now) {
        if (!w.ready()) return waitOut();
        status = label + " - taking the " + shortId(takeId) + " off the altar";
        bagBefore = w.bag(takeId);
        String err = w.use(layout.altar(), null);
        if (err != null) return fail("couldn't take the " + shortId(takeId) + " off the altar: " + err, w, now);
        tries++;
        go("taken", now);
        return waitOut();
    }

    private Out taken(AltarWorld w, long now) {
        if (now - stageTick < VERIFY) return waitOut();
        Map<Integer, AltarWorld.Stack> alt = w.items(layout.altar());
        AltarWorld.Stack out = alt == null ? null : alt.get(1);
        int gained = w.bag(takeId) - bagBefore;
        if (out == null && alt != null) {
            if (gained < 1 && now - stageTick < CLICK_WAIT) return waitOut();     // it may still be on its way into the bag
            if (gained < 1) lost = (lost == null ? "" : lost + "; ") + "1 " + shortId(takeId) + " didn't reach my bag (on the ground by the altar?)";
            mem.pressed(null);
            // what the craft used up is no longer the bot's to look after
            for (AltarPlan.Target t : targets) {
                Map<Integer, AltarWorld.Stack> it = w.items(t.pos());
                if (it != null && it.get(0) == null) mem.forget(t.pos());
            }
            if (recovering) recovered++;
            else made++;
            recovering = false;
            placed.clear();
            tries = 0;
            go(made >= n ? "end" : "check", now);
            return waitOut();
        }
        if (now - stageTick < CLICK_WAIT) return waitOut();
        if (tries < TRIES) {
            go("take", now);
            return waitOut();
        }
        return fail("couldn't take the " + shortId(takeId) + " off the altar at " + fmt(layout.altar()), w, now);
    }

    private Out end(AltarWorld w, long now) {
        todo.clear();
        todo.addAll(ownOnIt(w));
        after = "finished";
        tries = 0;
        go("retrieve", now);
        return waitOut();
    }

    private Out retrieve(AltarWorld w, long now) {
        if (todo.isEmpty()) {
            go(after, now);
            return after.equals("failed") ? new Out(State.FAIL, failText(), status)
                    : after.equals("finished") ? new Out(State.DONE, AltarPlan.done(made, seed, layout.altar(), recovered, lost), status) : waitOut();
        }
        if (!w.ready()) return waitOut();
        int[] pos = todo.get(0);
        boolean isAltar = same(pos, layout.altar());
        Map<Integer, AltarWorld.Stack> it = w.items(pos);
        AltarWorld.Stack s = it == null ? null : it.get(0);
        String mine = mem.placed(pos);
        if (s == null || !s.id().equals(mine)) {
            if (s == null) mem.forget(pos);
            todo.remove(0);
            return waitOut();
        }
        // the altar hands out its output before its input: never click it while an output is there
        if (isAltar && it.get(1) != null) {
            todo.remove(0);
            lost = (lost == null ? "" : lost + "; ") + "my " + shortId(s.id()) + " is still on the altar (there is an output on it)";
            return waitOut();
        }
        status = label + " - taking my " + shortId(s.id()) + " back";
        String err = w.use(pos, null);
        if (err != null) return fail("couldn't take my " + shortId(s.id()) + " back off " + fmt(pos) + ": " + err, w, now);
        tries++;
        go("retrieved", now);
        return waitOut();
    }

    private Out retrieved(AltarWorld w, long now) {
        if (now - stageTick < VERIFY) return waitOut();
        int[] pos = todo.get(0);
        Map<Integer, AltarWorld.Stack> it = w.items(pos);
        AltarWorld.Stack s = it == null ? null : it.get(0);
        if (s == null) {
            mem.forget(pos);
            todo.remove(0);
            tries = 0;
            go("retrieve", now);
            return waitOut();
        }
        if (now - stageTick < CLICK_WAIT) return waitOut();
        if (tries < TRIES) {
            go("retrieve", now);
            return waitOut();
        }
        String what = "couldn't take my " + shortId(s.id()) + " back off " + fmt(pos);
        if (failWhy != null) {
            failWhy = failWhy + "; and " + what;
            stage = "failed";
            return new Out(State.FAIL, failText(), status);
        }
        failWhy = what;
        stage = "failed";
        return new Out(State.FAIL, failText(), status);
    }
}
