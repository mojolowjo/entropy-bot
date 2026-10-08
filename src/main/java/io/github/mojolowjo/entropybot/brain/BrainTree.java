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
        /** B4: the verb or job it runs ("" none) and the settings it reads (the visible tree's fields). */
        public String verb = "";
        public List<String> keys = List.of();

        @SuppressWarnings("unchecked")
        <T extends Node> T meta(String verb, String... keys) {
            this.verb = verb;
            this.keys = List.of(keys);
            return (T) this;
        }

        Node(String id, String label, String cond) {
            this.id = id;
            this.label = label;
            this.cond = cond;
        }

        abstract Decision run(Ctx c);

        public String type() { return getClass().getSimpleName().toLowerCase(); }

        /** B4: the exported type: selector, sequence, condition, interrupt, leaf. */
        public String exportType() {
            if (this instanceof Action) return id.equals("job.interrupt") ? "interrupt" : "leaf";
            return type();
        }
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

    static Sequence when(String id, String label, String cond, Predicate<Ctx> p, Function<Ctx, Decision> act, String verb, String... keys) {
        return new Sequence(id, label, new Condition(id + "?", cond, p).meta("", keys), new Action(id, label, act).meta(verb, keys));
    }

    private final Node root;

    public BrainTree() { root = build(); }

    /** B4: a tree of another shape (a validated override file, {@link BrainTreeFile}). */
    BrainTree(Node root) { this.root = root; }

    /** B4: the nodes from the root to the leaf with this id, as uids ("r", "r/4", "r/4/1"); empty when not found. */
    public List<String> pathTo(String leafId) {
        List<String> out = new ArrayList<>();
        return find(root, "r", leafId, out) ? out : List.of();
    }

    private static boolean find(Node n, String uid, String id, List<String> out) {
        out.add(uid);
        if (n.children.isEmpty() && n instanceof Action && n.id.equals(id)) return true;
        for (int i = 0; i < n.children.size(); i++) if (find(n.children.get(i), uid + "/" + i, id, out)) return true;
        out.remove(out.size() - 1);
        return false;
    }

    public Node root() { return root; }

    public Decision run(Ctx c) {
        Decision d = root.run(c);
        return d != null ? d : new Decision("idle", Kind.NOTHING, null, null, 0, "nothing to do");
    }

    static Node build() {
        return new Selector("root", "the brain",
                when("off", "nothing", "not in a world, parked by the death policy, or a menu open",
                        c -> !c.s().inWorld || c.s().parked || c.s().menuOpen,
                        c -> new Decision("off", Kind.NOTHING, null, null, 0, !c.s().inWorld ? "not in a world" : c.s().parked ? "parked by the death policy (resume)" : "a menu is open"), ""),
                when("danger", "the reflexes handle it", "a mob counts (threat test), a reflex runs, or health is low",
                        c -> c.s().danger || c.s().health < c.c().i("dangerHealth"),
                        c -> new Decision("danger", Kind.PAUSE, null, "safety", 100, c.s().danger ? c.s().dangerWhy : "health " + Math.round(c.s().health)), "", "dangerHealth"),
                when("owner", "keep the owner's order", "the owner's order runs",
                        c -> c.s().ownerJob != null,
                        c -> new Decision("owner", Kind.NOTHING, null, null, 0, "your order runs: " + c.s().ownerJob), ""),
                when("passive", "passive", "escorting, defending or guarding",
                        c -> c.s().passive != null,
                        c -> new Decision("passive", Kind.NOTHING, null, null, 0, c.s().passive + ": only danger and your words"), ""),
                new Sequence("job", "the brain's running job",
                        new Condition("job?", "a job the brain started runs", c -> c.running() != null),
                        new Selector("job.check", "interrupts",
                                new Action("job.dusk", "shelter band and dusk near: a surface job gives way to the shelter", c -> {
                                    if (!shelterBand(c) || !surfaceBarred(c) || c.nightDoneHere() || NightSafety.underground(c.running().chain())) return null;
                                    return new Decision("job.dusk", Kind.SWITCH, shelterChain(c), "night", 70, "dusk is near and " + NightSafety.status(c.s(), c.c()));
                                }).meta("shelter", "night.shelterBelow", "night.duskHours"),
                                new Action("job.interrupt", "broken tool / hungry / bag full: handle, then resume", BrainTree::interrupt).meta("eat | deposit | craft", "hungryInterrupt", "fullInterrupt", "toolsWornPct"),
                                new Action("job.switch", "outscored by the margin: switch", BrainTree::outscored).meta("", "switchMargin", "floor"),
                                new Action("job.keep", "keep it", c -> new Decision("job.keep", Kind.KEEP, c.running().chain(), c.running().need(),
                                        c.running().score(), "still on it")).meta("", "switchMargin"))),
                new Sequence("night", "night",
                        new Condition("night?", "night (or near dusk in the shelter band) and nothing running",
                                c -> (c.s().night || (shelterBand(c) && NightSafety.nearDusk(c.s().dayTime, c.c().i("night.duskHours")))) && !c.nightDoneHere()),
                        new Selector("night.pick", "shelter, sleep or light",
                                when("night.shelter", "shelter", "night safety in the shelter band (0.24.3)",
                                        BrainTree::shelterBand,
                                        c -> new Decision("night.shelter", Kind.START, shelterChain(c), "night", 70,
                                                (c.s().night ? "night" : "dusk is near") + ", and " + NightSafety.status(c.s(), c.c()) + ": I shelter until day"), "shelter", "night.shelterBelow", "night.duskHours"),
                                when("night.sleep", "sleep", "sleep auto on and others sleep",
                                        c -> c.s().night && c.s().sleepAuto && c.s().othersSleeping,
                                        c -> new Decision("night.sleep", Kind.START, "sleep", "night", 60, "night, and others are sleeping (sleep auto)"), "sleep"),
                                when("night.shelter.nobed", "shelter", "underground band and no bed (or sleep auto off): shelter",
                                        c -> c.s().night && underBand(c) && (!c.s().sleepAuto || !c.s().bedNear) && !hasUnderground(c),
                                        c -> new Decision("night.shelter.nobed", Kind.START, shelterChain(c), "night", 55,
                                                "night, no bed and no underground job: " + NightSafety.status(c.s(), c.c())), "shelter", "night.workBelow"),
                                when("night.light", "light the spot", "my spot is dark",
                                        c -> !c.s().litHere,
                                        c -> new Decision("night.light", Kind.START, "light here 8", "night", 50, "night: lighting my spot, then I work on"), "light here"))),
                new Action("pick", "the needs: highest score wins", c -> {
                    Needs.Option o = surfaceBarred(c) ? bestUnderground(c) : c.scored().best(c.c().i("floor"));
                    return o == null ? null : new Decision("pick", Kind.START, o.chain(), o.need(), o.score(), o.reason());
                }).meta("the need's job", "floor"),
                when("idle.near", "stay near you", "not released, the owner online and further than 32",
                        c -> c.s().bound() && BrainState.flat(c.s().botPos, c.s().ownerPos) > c.c().i("nearbyR"),
                        c -> new Decision("idle.near", Kind.START, "come", "near", 0, "staying within " + c.c().i("nearbyR") + " of you (not released: done lets me go)"), "come", "nearbyR"),
                new Action("idle", "idle", c -> new Decision("idle", Kind.NOTHING, null, null, 0, "nothing scores above " + c.c().i("floor"))).meta("", "floor"));
    }

    // ---- 0.24.3: night safety ----

    static boolean shelterBand(Ctx c) {
        return NightSafety.band(NightSafety.score(c.s()), c.c()) == NightSafety.Band.SHELTER;
    }

    static boolean underBand(Ctx c) {
        return NightSafety.band(NightSafety.score(c.s()), c.c()) == NightSafety.Band.UNDERGROUND;
    }

    /** No surface job now: night in the underground band, or near dusk (or night) in the shelter band. */
    static boolean surfaceBarred(Ctx c) {
        if (shelterBand(c)) return c.s().night || NightSafety.nearDusk(c.s().dayTime, c.c().i("night.duskHours"));
        return c.s().night && underBand(c);
    }

    static Needs.Option bestUnderground(Ctx c) {
        for (Needs.Option o : c.scored().options())
            if (o.chain() != null && o.score() > c.c().i("floor") && NightSafety.underground(o.chain())) return o;
        return null;
    }

    static boolean hasUnderground(Ctx c) { return bestUnderground(c) != null; }

    /** To the camp first when one is marked and further than 8 blocks, then shelter. */
    static String shelterChain(Ctx c) {
        int[] camp = c.s().campPos;
        String prep = c.s().shelterBlocks < io.github.mojolowjo.entropybot.camp.ShelterPlan.FULL ? "cut 8 logs then craft planks 32 then " : "";
        return prep + (camp != null && BrainState.flat(camp, c.s().botPos) > 8 ? "go camp then shelter" : "shelter");
    }

    static Decision interrupt(Ctx c) {
        String[] m = Interrupts.midJob(c.s(), c.c());
        if (m == null) return null;
        return new Decision("job.interrupt", Kind.SWITCH, m[1] + " then " + c.running().chain(), c.running().need(), c.running().score(), m[2] + ": " + m[1] + ", then back to " + c.running().chain());
    }

    static Decision outscored(Ctx c) {
        Needs.Option o = c.scored().best(c.c().i("floor"));
        if (o == null || o.need().equals(c.running().need())) return null;
        // a job of a need that is not scored (night, near) keeps the score it started with
        int cur = c.scored().has(c.running().need()) ? c.scored().scoreOf(c.running().need()) : c.running().score();
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
