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

    /**
     * One loop's input. 0.26.1: mem, the failure memory (R1; null = none) every leaf that starts a chain consults, and
     * skipped, where the leaves note what they skipped ("light here 8 failed NO_ITEM 3 min ago") for why and the log.
     */
    public record Ctx(BrainState s, BrainConfig c, Needs.Scored scored, Running running, boolean nightDoneHere, FailMemory mem, List<String> skipped) {
        public Ctx(BrainState s, BrainConfig c, Needs.Scored scored, Running running, boolean nightDoneHere) {
            this(s, c, scored, running, nightDoneHere, null, new ArrayList<>());
        }
    }

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

        /** R1: a leaf whose chain the failure memory holds is skipped (noted), so the next sibling answers. */
        @Override Decision run(Ctx c) {
            Decision d = f.apply(c);
            if (d == null || d == PASS || c.mem() == null || d.chain() == null || (d.kind() != Kind.START && d.kind() != Kind.SWITCH)) return d;
            FailMemory.Entry e = c.mem().blocks(d.chain(), c.s(), c.s().now);
            if (e == null) return d;
            c.skipped().add(FailMemory.skipText(e, c.s().now));
            return null;
        }
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
                new Selector("passive.sel", "passive",
                        // 0.26.1 R5: escort/assist (owner-bound) and the owner gone past passive.ownerTimeoutS: dismiss then home
                        when("passive.leave", "you left: dismiss then home", "escorting or assisting, and you are offline passive.ownerTimeoutS or longer",
                                c -> c.s().passive != null && ownerGone(c) && c.s().ownerOfflineMs >= c.c().i("passive.ownerTimeoutS") * 1000L,
                                c -> new Decision("passive.leave", Kind.START, "dismiss then home", "passive", 50, LEFT_WHISPER), "dismiss then home", "passive.ownerTimeoutS"),
                        when("passive.grace", "you are offline: wait the grace", "escorting or assisting, and you are offline less than passive.ownerTimeoutS",
                                c -> c.s().passive != null && ownerGone(c),
                                c -> new Decision("passive.grace", Kind.NOTHING, null, null, 0, c.s().passive + ": you are offline " + c.s().ownerOfflineMs / 1000
                                        + " s - home after " + c.c().i("passive.ownerTimeoutS") + " s (passive.ownerTimeoutS)"), "", "passive.ownerTimeoutS"),
                        when("passive", "passive", "escorting, defending or guarding",
                                c -> c.s().passive != null,
                                c -> new Decision("passive", Kind.NOTHING, null, null, 0, c.s().passive + ": only danger and your words"), "")),
                new Sequence("job", "the brain's running job",
                        new Condition("job?", "a job the brain started runs", c -> c.running() != null),
                        new Selector("job.check", "interrupts",
                                new Action("job.dusk", "shelter band and dusk near: a surface job gives way to the shelter (no torches nor bed and over an hour left: torches first)", BrainTree::dusk)
                                        .meta("shelter | gather torch 16", "night.shelterBelow", "night.duskHours"),
                                new Action("job.storm", "a thunderstorm with sky above: a surface job gives way (shelter band: shelter; underground band: an underground job)", BrainTree::stormInterrupt)
                                        .meta("shelter 5m | the need's job", "weather.storm", "night.shelterBelow", "night.workBelow"),
                                new Action("job.interrupt", "broken tool / hungry / bag full: handle, then resume", BrainTree::interrupt).meta("eat | go base then deposit then smelt collect | craft", "hungryInterrupt", "brain.bagFullSlots", "toolsWornPct"),
                                new Action("job.switch", "outscored by the margin: switch", BrainTree::outscored).meta("", "switchMargin", "floor"),
                                new Action("job.keep", "keep it", c -> new Decision("job.keep", Kind.KEEP, c.running().chain(), c.running().need(),
                                        c.running().score(), "still on it")).meta("", "switchMargin"))),
                // 0.26.1 R4: a thunderstorm with sky above counts as night for the bands (rain alone: nothing; underground: nothing)
                new Selector("weather.storm", "a thunderstorm",
                        when("weather.storm.shelter", "shelter from the storm", "thundering, sky above me and the shelter band",
                                c -> storm(c) && shelterBand(c),
                                c -> new Decision("weather.storm.shelter", Kind.START, stormShelter(c), "storm", 65,
                                        "a thunderstorm and the sky above me, and " + NightSafety.status(c.s(), c.c()) + ": I shelter a while"), "shelter 5m", "weather.storm", "night.shelterBelow"),
                        when("weather.storm.under", "only underground work in the storm", "thundering, sky above me and the underground band",
                                c -> storm(c) && underBand(c) && hasUnderground(c),
                                c -> {
                                    Needs.Option o = bestUnderground(c);
                                    return new Decision("weather.storm.under", Kind.START, o.chain(), o.need(), o.score(), o.reason() + " (a thunderstorm: underground work only)");
                                }, "the need's job", "weather.storm", "night.workBelow")),
                new Sequence("night", "night",
                        new Condition("night?", "night (or near dusk in the shelter band) and nothing running",
                                c -> (c.s().night || (shelterBand(c) && NightSafety.nearDusk(c.s().dayTime, c.c().i("night.duskHours")))) && !c.nightDoneHere()),
                        new Selector("night.pick", "eat, a pickaxe, then shelter, sleep or light",
                                // 0.26.1 R8: the night's needs first
                                when("night.eat", "eat before the night", "food at or below hungryInterrupt + 2 and food on me",
                                        c -> c.s().food <= c.c().i("hungryInterrupt") + 2 && c.s().foodItems > 0,
                                        c -> new Decision("night.eat", Kind.START, "eat", "food", 75, "night: food " + c.s().food + "/20 (hungryInterrupt "
                                                + c.c().i("hungryInterrupt") + " + 2): I eat first"), "eat", "hungryInterrupt"),
                                when("night.tool", "a new pickaxe before the night", "a pickaxe at or below toolsWornPct + 5 % and the materials for one on me",
                                        c -> SupplyCheck.tier(c.s().stage) != null && c.s().pickaxes > 0 && c.s().pickPct <= c.c().i("toolsWornPct") + 5 && c.s().pickMaterials,
                                        c -> new Decision("night.tool", Kind.START, "craft " + SupplyCheck.tier(c.s().stage) + "_pickaxe 1", "gear", 72,
                                                "night: my pickaxe is at " + c.s().pickPct + " % (toolsWornPct " + c.c().i("toolsWornPct") + " + 5) and I have the materials"), "craft", "toolsWornPct"),
                                when("night.shelter", "shelter", "night safety in the shelter band (0.24.3)",
                                        BrainTree::shelterBand,
                                        c -> new Decision("night.shelter", Kind.START, shelterChain(c), "night", 70,
                                                (c.s().night ? "night" : "dusk is near") + ", and " + NightSafety.status(c.s(), c.c()) + ": I shelter until day"), "shelter", "night.shelterBelow", "night.duskHours"),
                                // 0.26.1 R2: sleep only with a bed near (24) or in the bag
                                when("night.sleep", "sleep", "sleep auto on, others sleep, and a bed near or in my bag",
                                        c -> c.s().night && c.s().sleepAuto && c.s().othersSleeping && PlayRules.bedFor(c.s()),
                                        c -> new Decision("night.sleep", Kind.START, "sleep", "night", 60, "night, and others are sleeping (sleep auto)"), "sleep"),
                                when("night.shelter.nobed", "shelter", "underground band and no bed (or sleep auto off): shelter",
                                        c -> c.s().night && underBand(c) && (!c.s().sleepAuto || !PlayRules.bedFor(c.s())) && !hasUnderground(c),
                                        c -> new Decision("night.shelter.nobed", Kind.START, shelterChain(c), "night", 55,
                                                "night, no bed and no underground job: " + NightSafety.status(c.s(), c.c())), "shelter", "night.workBelow"),
                                // 0.26.1 R2: light only with torches; else craft them from coal or logs; else shelter (shelter band); else the needs (torch)
                                when("night.light", "light the spot", "my spot is dark and I have torches",
                                        c -> !c.s().litHere && c.s().torches > 0,
                                        c -> new Decision("night.light", Kind.START, "light here 8", "night", 50, "night: lighting my spot, then I work on"), "light here"),
                                when("night.light.craft", "make torches, then light the spot", "my spot is dark, no torches, coal or logs on me",
                                        c -> !c.s().litHere && c.s().torches <= 0 && PlayRules.torchMaterials(c.s()),
                                        c -> new Decision("night.light.craft", Kind.START, "craft torch 4 then light here 8", "night", 50,
                                                "night: no torches, so I make some (coal " + c.s().coal + ", logs " + c.s().logs + ") and light my spot"), "craft"),
                                when("night.light.shelter", "shelter", "my spot is dark, nothing to light it with, the shelter band",
                                        c -> !c.s().litHere && c.s().torches <= 0 && !PlayRules.torchMaterials(c.s()) && shelterBand(c),
                                        c -> new Decision("night.light.shelter", Kind.START, shelterChain(c), "night", 50,
                                                "night: no torches, coal or logs, and " + NightSafety.status(c.s(), c.c())), "shelter", "night.shelterBelow"))),
                // 0.26.1 R6: by day, a bed within 24 and my spawn not near the base: set it there (once per spawn.cooldownMinutes)
                when("spawn", "set my spawn at the bed", "day, a bed within 24, spawn not near the base, no try within spawn.cooldownMinutes",
                        c -> !c.s().night && c.s().dayTime >= 0 && c.s().bedNear && !c.s().spawnNearBase && c.s().spawnDue && !surfaceBarred(c),
                        c -> new Decision("spawn", Kind.START, "spawn", "spawn", 30, "a bed is within 24 and my spawn is not near the base: I set it there"),
                        "spawn", "spawn.cooldownMinutes"),
                new Action("pick", "the needs: highest score wins", BrainTree::pick)
                        .meta("the need's job", "floor", "brain.foodStock", "brain.foodDusk", "cave.eatFirst", "brain.failMemoryMinutes", "brain.preDepositSlots"),
                // 0.26.1 R9: never "come" to an unknown or placeholder position (bound() needs ownerKnown)
                when("idle.near", "stay near you", "not released, the owner online, placed (seen or a fresh companion fix) and further than 32",
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

    /** No surface job by the night rules: night in the underground band, or near dusk (or night) in the shelter band. */
    static boolean nightBarred(Ctx c) {
        if (shelterBand(c)) return c.s().night || NightSafety.nearDusk(c.s().dayTime, c.c().i("night.duskHours"));
        return c.s().night && underBand(c);
    }

    /** No new surface job: the night rules, or (0.26.1 R4) a thunderstorm with sky above outside the normal band. */
    static boolean surfaceBarred(Ctx c) {
        return nightBarred(c) || (storm(c) && (shelterBand(c) || underBand(c)));
    }

    // ---- 0.26.1 "steady brain" ----

    /** R5's whisper (and the decision's reason). */
    public static final String LEFT_WHISPER = "you left - going home";

    /** R5: an owner-bound passive (escort, assist) with the owner offline and the timeout on. Defend and guard never. */
    static boolean ownerGone(Ctx c) {
        return c.s().passiveOwnerBound && !c.s().ownerOnline && c.c().i("passive.ownerTimeoutS") > 0;
    }

    /** R4: thundering, the sky above the bot's head, the setting on. */
    static boolean storm(Ctx c) { return c.c().on("weather.storm") && c.s().thundering && c.s().skyAbove; }

    /** R4: the storm's shelter (a while, not until day): shell blocks first when short. */
    static String stormShelter(Ctx c) {
        String prep = c.s().shelterBlocks < io.github.mojolowjo.entropybot.camp.ShelterPlan.FULL ? "cut 8 logs then craft planks 32 then " : "";
        return prep + "shelter 5m";
    }

    /** R2: no torches and no bed (near or in the bag), on the surface, with more than an hour (1000 ticks) to dusk. */
    static boolean torchesFirst(Ctx c) {
        BrainState s = c.s();
        return s.skyAbove && s.torches <= 0 && !PlayRules.bedFor(s) && NightSafety.toDusk(s.dayTime) > 1000;
    }

    static Decision dusk(Ctx c) {
        if (!shelterBand(c) || !nightBarred(c) || c.nightDoneHere() || NightSafety.underground(c.running().chain())) return null;
        if (torchesFirst(c)) {
            if (c.running().chain().trim().toLowerCase().startsWith("gather torch")) return null;      // already on it
            long d = NightSafety.toDusk(c.s().dayTime);
            return new Decision("job.dusk", Kind.SWITCH, "gather torch 16", "torch", 70, "dusk in " + d / 1000 + "h" + String.format("%02d", (d % 1000) * 60 / 1000)
                    + "m, no torches and no bed: torches first (the shelter in the last hour)");
        }
        return new Decision("job.dusk", Kind.SWITCH, shelterChain(c), "night", 70, "dusk is near and " + NightSafety.status(c.s(), c.c()));
    }

    static Decision stormInterrupt(Ctx c) {
        if (!storm(c) || NightSafety.underground(c.running().chain())) return null;
        if (shelterBand(c)) return new Decision("job.storm", Kind.SWITCH, stormShelter(c), "storm", 65, "a thunderstorm and the sky above me, and " + NightSafety.status(c.s(), c.c()));
        if (!underBand(c)) return null;
        Needs.Option o = bestUnderground(c);
        if (o != null) return new Decision("job.storm", Kind.SWITCH, o.chain(), o.need(), o.score(), o.reason() + " (a thunderstorm: underground work only)");
        return new Decision("job.storm", Kind.SWITCH, stormShelter(c), "storm", 60, "a thunderstorm, the sky above me and no underground job: I shelter a while");
    }

    /** The needs by score; R1: a remembered failure is skipped (noted) and the next option tried. */
    static Decision pick(Ctx c) {
        boolean barred = surfaceBarred(c);
        int floor = c.c().i("floor");
        for (Needs.Option o : learnedOrder(c.scored().options(), c.s())) {
            if (o.chain() == null || o.score() <= floor) continue;
            if (barred && !NightSafety.underground(o.chain())) continue;
            if (c.mem() != null) {
                FailMemory.Entry e = c.mem().blocks(o.chain(), c.s(), c.s().now);
                if (e != null) {
                    c.skipped().add(FailMemory.skipText(e, c.s().now));
                    continue;
                }
            }
            String note = c.s().learned(o.chain()).note();
            return new Decision("pick", Kind.START, o.chain(), o.need(), o.score(), o.reason() + (note.isEmpty() ? "" : " [" + note + "]"));
        }
        return null;
    }

    /**
     * 0.26.5: the options by score x the learned multiplier (stable: equal products keep the scorer's order). Only the
     * pick uses it; no table (or brain.learn 0) = the scorer's order.
     */
    static List<Needs.Option> learnedOrder(List<Needs.Option> opts, BrainState s) {
        if (s.learn == null || s.learnParams == null || !s.learnParams.on() || opts.size() < 2) return opts;
        java.util.Map<Needs.Option, Double> v = new java.util.IdentityHashMap<>();
        for (Needs.Option o : opts) v.put(o, o.chain() == null ? o.score() : o.score() * s.learned(o.chain()).m());
        List<Needs.Option> out = new ArrayList<>(opts);
        out.sort(java.util.Comparator.comparingDouble((Needs.Option o) -> -v.get(o)));
        return out;
    }

    static Needs.Option bestUnderground(Ctx c) {
        for (Needs.Option o : c.scored().options())
            if (o.chain() != null && o.score() > c.c().i("floor") && NightSafety.underground(o.chain())
                    && (c.mem() == null || c.mem().blocks(o.chain(), c.s(), c.s().now) == null)) return o;
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
        // 0.25.1: the running job is the bag's own trip (or holds a deposit): no interrupt for a full bag
        if (m[0].equals("FULL") && (c.running().need().equals("bag") || Interrupts.bagTrip(c.running().chain()))) return null;
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
