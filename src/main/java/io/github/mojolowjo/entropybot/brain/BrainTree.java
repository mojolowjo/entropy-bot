package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * B1 (BRAIN_PLAN 4.2): the behaviour tree. Selector (the first child that answers wins), Sequence (every child must
 * pass; the last one answers), Condition, Action. The shape is fixed in B1 (B4 loads it from a file later); every node
 * has an id, a label and a condition text for the visible tree. Order: off > danger > the owner's order > passive
 * (escort/defend/guard) > interrupts of the brain's running job > night > the needs (utility-scored, highest wins,
 * momentum) > idle. Pure.
 */
public final class BrainTree {

    public enum Kind { NOTHING, PAUSE, KEEP, START, SWITCH }

    /** What one loop decided: the branch (an action node's id), what to do, the chain, the need, its score, why. */
    public record Decision(String branch, Kind kind, String chain, String need, int score, String reason) {}

    /** The running brain job as the tree sees it. */
    public record Running(String need, String chain, int score) {}

    /** One loop's input. */
    public record Ctx(BrainState s, BrainConfig c, Needs.Scored scored, Running running, boolean nightDoneHere) {}

    private static final Decision PASS = new Decision("pass", Kind.NOTHING, null, null, 0, "");

    public abstract static class Node {
        public final String id, label, cond;
        public final List<Node> children = new ArrayList<>();

        Node(String id, String label, String cond) {
            this.id = id;
            this.label = label;
            this.cond = cond;
        }

        abstract Decision run(Ctx c);

        public String type() { return getClass().getSimpleName().toLowerCase(); }
    }

    static final class Selector extends Node {
        Selector(String id, String label, Node... kids) {
            super(id, label, "");
            children.addAll(List.of(kids));
        }

        @Override Decision run(Ctx c) {
            for (Node n : children) {
                Decision d = n.run(c);
                if (d != null && d != PASS) return d;
            }
            return null;
        }
    }

    static final class Sequence extends Node {
        Sequence(String id, String label, Node... kids) {
            super(id, label, "");
            children.addAll(List.of(kids));
        }

        @Override Decision run(Ctx c) {
            Decision last = null;
            for (Node n : children) {
                Decision d = n.run(c);
                if (d == null) return null;
                last = d;
            }
            return last;
        }
    }

    static final class Condition extends Node {
        final Predicate<Ctx> p;

        Condition(String id, String cond, Predicate<Ctx> p) {
            super(id, cond, cond);
            this.p = p;
        }

        @Override Decision run(Ctx c) { return p.test(c) ? PASS : null; }
    }

    static final class Action extends Node {
        final Function<Ctx, Decision> f;

        Action(String id, String label, Function<Ctx, Decision> f) {
            super(id, label, "");
            this.f = f;
        }

        @Override Decision run(Ctx c) { return f.apply(c); }
    }

    static Sequence when(String id, String label, String cond, Predicate<Ctx> p, Function<Ctx, Decision> act) {
        return new Sequence(id, label, new Condition(id + "?", cond, p), new Action(id, label, act));
    }

    private final Node root;

    public BrainTree() { root = build(); }

    public Node root() { return root; }

    public Decision run(Ctx c) {
        Decision d = root.run(c);
        return d != null ? d : new Decision("idle", Kind.NOTHING, null, null, 0, "nothing to do");
    }

    static Node build() {
        return new Selector("root", "the brain",
                when("off", "nothing", "not in a world, parked by the death policy, or a menu open",
                        c -> !c.s().inWorld || c.s().parked || c.s().menuOpen,
                        c -> new Decision("off", Kind.NOTHING, null, null, 0, !c.s().inWorld ? "not in a world" : c.s().parked ? "parked by the death policy (resume)" : "a menu is open")),
                when("danger", "the reflexes handle it", "a mob counts (threat test), a reflex runs, or health is low",
                        c -> c.s().danger || c.s().health < c.c().i("dangerHealth"),
                        c -> new Decision("danger", Kind.PAUSE, null, "safety", 100, c.s().danger ? c.s().dangerWhy : "health " + Math.round(c.s().health))),
                when("owner", "keep the owner's order", "the owner's order runs",
                        c -> c.s().ownerJob != null,
                        c -> new Decision("owner", Kind.NOTHING, null, null, 0, "your order runs: " + c.s().ownerJob)),
                when("passive", "passive", "escorting, defending or guarding",
                        c -> c.s().passive != null,
                        c -> new Decision("passive", Kind.NOTHING, null, null, 0, c.s().passive + ": only danger and your words")),
                new Sequence("job", "the brain's running job",
                        new Condition("job?", "a job the brain started runs", c -> c.running() != null),
                        new Selector("job.check", "interrupts",
                                new Action("job.interrupt", "broken tool / hungry / bag full: handle, then resume", BrainTree::interrupt),
                                new Action("job.switch", "outscored by the margin: switch", BrainTree::outscored),
                                new Action("job.keep", "keep it", c -> new Decision("job.keep", Kind.KEEP, c.running().chain(), c.running().need(),
                                        c.running().score(), "still on it")))),
                new Sequence("night", "night",
                        new Condition("night?", "night and nothing running", c -> c.s().night && !c.nightDoneHere()),
                        new Selector("night.pick", "sleep or light",
                                when("night.sleep", "sleep", "sleep auto on and others sleep",
                                        c -> c.s().sleepAuto && c.s().othersSleeping,
                                        c -> new Decision("night.sleep", Kind.START, "sleep", "night", 60, "night, and others are sleeping (sleep auto)")),
                                when("night.light", "light the spot", "my spot is dark",
                                        c -> !c.s().litHere,
                                        c -> new Decision("night.light", Kind.START, "light here 8", "night", 50, "night: lighting my spot, then I work on")))),
                new Action("pick", "the needs: highest score wins", c -> {
                    Needs.Option o = c.scored().best(c.c().i("floor"));
                    return o == null ? null : new Decision("pick", Kind.START, o.chain(), o.need(), o.score(), o.reason());
                }),
                when("idle.near", "stay near you", "not released, the owner online and further than 32",
                        c -> c.s().bound() && BrainState.flat(c.s().botPos, c.s().ownerPos) > c.c().i("nearbyR"),
                        c -> new Decision("idle.near", Kind.START, "come", "near", 0, "staying within " + c.c().i("nearbyR") + " of you (not released: done lets me go)")),
                new Action("idle", "idle", c -> new Decision("idle", Kind.NOTHING, null, null, 0, "nothing scores above " + c.c().i("floor"))));
    }

    static Decision interrupt(Ctx c) {
        String[] m = Interrupts.midJob(c.s(), c.c());
        if (m == null) return null;
        return new Decision("job.interrupt", Kind.SWITCH, m[1] + " then " + c.running().chain(), c.running().need(), c.running().score(), m[2] + ": " + m[1] + ", then back to " + c.running().chain());
    }

    static Decision outscored(Ctx c) {
        Needs.Option o = c.scored().best(c.c().i("floor"));
        if (o == null || o.need().equals(c.running().need())) return null;
        int cur = c.scored().scoreOf(c.running().need());
        if (o.score() - cur < c.c().i("switchMargin")) return null;
        return new Decision("job.switch", Kind.SWITCH, o.chain(), o.need(), o.score(), o.reason() + " (" + o.score() + " beats " + c.running().need() + " " + cur + ")");
    }

    /** Every node, depth first: {depth, id, type, label, condition} (B4's page reads it). */
    public List<String[]> describe() {
        List<String[]> out = new ArrayList<>();
        walk(root, 0, out);
        return out;
    }

    private static void walk(Node n, int depth, List<String[]> out) {
        out.add(new String[]{String.valueOf(depth), n.id, n.type(), n.label, n.cond});
        for (Node k : n.children) walk(k, depth + 1, out);
    }
}
