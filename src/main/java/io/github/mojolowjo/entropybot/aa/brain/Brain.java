package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Status;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BRAIN_PLAN 2.1-2.3b (B1): one scored list of desires in the task slot, replacing the ladder and the idle chooser.
 *
 * <p>No desire runs: every desire is scored, the ones below 0 or cooling down are left out, the rest are tried from the
 * highest down (an urgent UPKEEP ranks above everything, so an owner goal never starves the bot); the first that
 * answers RUNNING or SUCCESS wins this tick (a rung that is met fails at once, as on the ladder). The last winner gets
 * {@link #BONUS} (ties go to the earlier desire).
 *
 * <p>A desire runs: it is ticked to its end. Only an UPKEEP at {@link #URGENT}+ or an OWNER goal just given that beats
 * the running one's score + {@link #BONUS} interrupts it (an owner goal only after {@link #MIN_RUN_MS}).
 *
 * <p>The stuck watchdog (2.3b): no progress (the {@link #progressSig} changes: position, bag, the task's progress, the
 * task itself) for {@link #STUCK_MS} halts the desire with a reason, counts a failure, cools it down (2 min doubling)
 * and logs an incident; the next tick picks the next best.
 */
public final class Brain extends Node {
    public static final double BONUS = 15, URGENT = 75;
    public static final long MIN_RUN_MS = 60_000, STUCK_MS = 180_000, CD_BASE_MS = 120_000, CD_MAX_MS = 16 * 60_000,
            GAP_MS = 5_000, PROBE_MS = 60_000;

    /** One scored desire for {@code aa why}. */
    /** @param parked AA_ROUND2 4: the value a parked desire would have (it flows to a GATE prerequisite), else 0 */
    public record Scored(Desire d, double score, double rank, String parts, String skip, double own, double parked) {
        Scored(Desire d, double score, double rank, String parts, String skip) { this(d, score, rank, parts, skip, score, 0); }

        Scored(Desire d, double score, double rank, String parts, String skip, double own) { this(d, score, rank, parts, skip, own, 0); }
    }

    private final List<Desire> desires = new ArrayList<>();
    private int running = -1, lastWinner = -1;
    private long startedAt, lastProgressAt, lastTickAt = -1;
    private boolean taskSeen;
    private String sig;
    /** AA_ROUND2 3: the one failure record (desire holds and fails in a row; the rung pickers' option memories bind to it). */
    public final Failures failures = new Failures();
    private List<Scored> lastScored = List.of();
    /** Counters for aa status. */
    public long picks, interrupts, stuck;
    public final Map<Desire.Kind, Long> interruptsByKind = new EnumMap<>(Desire.Kind.class);
    private long bornAt = -1;
    private String probeLast;
    private long probeAt = -1;

    public Brain(String id, String label, Desire... ds) {
        super(id, label);
        for (Desire d : ds) desires.add(d);
    }

    /** B4: an owner goal joins the list (or replaces one with the same id). */
    public void add(Desire d) {
        for (int i = 0; i < desires.size(); i++)
            if (desires.get(i).id.equals(d.id)) {
                if (i == running) {                 // review: the old node's task must not be orphaned
                    desires.get(i).node.halt("replaced by a new " + d.id);
                    running = -1;
                }
                desires.set(i, d);
                return;
            }
        desires.add(d);
    }

    /** B4 (aa drop / a goal reached): the desire leaves the list (halted if it runs); false when no such id. */
    public boolean remove(String id, String why) {
        for (int i = 0; i < desires.size(); i++) {
            if (!desires.get(i).id.equals(id)) continue;
            if (i == running) {
                desires.get(i).node.halt(why);
                running = -1;
                sig = null;
                taskSeen = false;
            } else if (running > i) running--;
            if (lastWinner == i) lastWinner = -1;
            else if (lastWinner > i) lastWinner--;
            desires.remove(i);
            failures.forget(id);
            List<Scored> keep = new ArrayList<>();
            for (Scored s : lastScored) if (!s.d().id.equals(id)) keep.add(s);
            lastScored = keep;
            return true;
        }
        return false;
    }

    public List<Desire> desires() { return desires; }

    /** The running desire, or null. */
    public Desire runningDesire() { return running < 0 ? null : desires.get(running); }

    /** The running desire's node, or null. */
    public Node runningChild() { return running < 0 ? null : desires.get(running).node; }

    // ---- scoring ----

    /** A desire's score now (0..100+, below 0 = not wanted) with its parts. */
    Scored score(Blackboard bb, Desire d, long now) {
        double v;
        try {
            v = d.score.applyAsDouble(bb);
        } catch (RuntimeException e) {
            bb.port.log("brain: " + d.id + " score failed: " + e);
            return new Scored(d, -1, -1, "score failed", "score failed: " + e);
        }
        String parkedWhy = null;
        if (v >= 0 && v < 100) {                       // feasibility: no source for a required input = parked, not worth 38
            try {
                parkedWhy = d.infeasible.apply(bb);
            } catch (RuntimeException e) {
                bb.port.log("brain: " + d.id + " feasibility failed: " + e);
            }
        }
        String skip = v < 0 ? "not wanted" : null;
        long cu = failures.until(d.id, null);
        if (skip == null && cu > now) skip = "cooling " + (cu - now + 999) / 1000 + "s (" + failures.fails(d.id, null) + " fails)";
        String parts = String.valueOf(Math.round(v));
        double live = parkedWhy != null ? Double.NaN : liveCost(bb, d);
        if (d.kind == Desire.Kind.PROGRESS && v >= 0 && v < 100) {
            // B2 (2.2, 2.9): value x P(success) / (1 + cost / C), cost and P measured after 3 runs; AA_ROUND2 1: the
            // picker's live cost first
            double p = Curves.pSuccess(failures.fails(d.id, null), stats.n(d.id), stats.ok(d.id));
            double c = Double.isNaN(live) ? stats.cost(d.id, d.handCostS) : live;
            double value = v;
            v = Curves.progress(value, p, c);
            parts = "value " + Math.round(value) + " x P " + Math.round(p * 100) / 100.0 + " / cost " + Math.round(c) + "s "
                    + (!Double.isNaN(live) ? "live" : stats.n(d.id) >= BrainStats.MIN_RUNS ? "measured" : "hand");
        } else if (d.kind == Desire.Kind.UPKEEP && v >= 0 && v < URGENT && !Double.isNaN(live)) {
            // AA_ROUND2 1: a far source does not pull the bot off a task it is close to finishing; urgent is never divided
            double value = v;
            v = value / (1 + live / Curves.COST_C);
            parts = "value " + Math.round(value) + " / cost " + Math.round(live) + "s live";
        }
        if (v >= 0 && v < 100 && d.exposure != Desire.Exposure.ANY && Night.hostile(bb)) {
            // AA_ROUND2 4 (was B3's +/-10): the night's danger makes surface work dearer and work under ground cheaper
            double danger = danger(bb), m = exposureFactor(d.exposure, danger);
            v = v >= URGENT ? v * m : Math.min(URGENT - 1, v * m);      // review: the night never makes a desire urgent
            parts += " night x" + Math.round(m * 100) / 100.0 + " (danger " + Math.round(danger * 100) / 100.0 + ")";
        }
        if (parkedWhy != null) return new Scored(d, 0, 0, "parked (infeasible)", "parked: " + parkedWhy, 0, cu > now ? 0 : Math.max(0, v));
        double rank = v + (urgent(d, v) ? 1000 : 0);
        return new Scored(d, v, rank, parts, skip);
    }

    /** The night's danger now (Costs.nightDanger at the current facts); 0 when monsters do not spawn (day, Peaceful). */
    public static double danger(Blackboard bb) {
        if (!Night.hostile(bb)) return 0;
        var in = bb.in;
        return Costs.nightDanger(in.difficulty, in.armorPoints, in.health, in.maxHealth, in.light, in.hostilesNear, in.bedKnown || in.bedInBag);
    }

    /** SURFACE 1 / (1 + danger), UNDER 1 + danger / 2, ANY 1. */
    public static double exposureFactor(Desire.Exposure e, double danger) {
        return switch (e) {
            case SURFACE -> 1 / (1 + danger);
            case UNDER -> 1 + danger / 2;
            case ANY -> 1;
        };
    }

    /** The desire's live cost in seconds, NaN when it has none (or it failed: logged). */
    private static double liveCost(Blackboard bb, Desire d) {
        if (d.liveCost == null) return Double.NaN;
        try {
            Double c = d.liveCost.apply(bb);
            return c == null || c < 0 ? Double.NaN : c;
        } catch (RuntimeException e) {
            bb.port.log("brain: " + d.id + " live cost failed: " + e);
            return Double.NaN;
        }
    }

    static boolean urgent(Desire d, double v) { return d.kind == Desire.Kind.UPKEEP && v >= URGENT; }

    /** B2 (2.9): measured runs per desire (the adapter loads and saves them). */
    public final BrainStats stats = new BrainStats();

    /**
     * Every desire scored now, in list order. B2 (2.2): a wanted desire whose {@code prereq} names another wanted desire
     * lifts it to at least {@link Curves#FLOW} of its score (two rounds, so a chain flows); interrupts use the own score only.
     */
    public List<Scored> scoreAll(Blackboard bb) {
        long now = bb.clock.now();
        List<Scored> out = new ArrayList<>();
        for (Desire d : desires) out.add(score(bb, d, now));
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < out.size(); i++) at.put(out.get(i).d().id, i);
        // AA_ROUND2 4: an under-ground stock-up that could run now (not held, not parked): the night shelter may yield to it
        for (Scored s : out)
            if (s.d().kind == Desire.Kind.UPKEEP && s.d().exposure == Desire.Exposure.UNDER && s.skip() == null && s.score() > 0) bb.fact(UNDER_WANTED_FACT, now);
        double[] own = new double[out.size()], flow = new double[out.size()];
        String[] from = new String[out.size()];
        for (int i = 0; i < own.length; i++) own[i] = out.get(i).score();
        for (int round = 0; round < 2; round++) {
            double[] next = new double[own.length];
            String[] nf = new String[own.length];
            for (int i = 0; i < own.length; i++) {
                if (own[i] < 0) continue;
                String p;
                try {
                    p = out.get(i).d().prereq.apply(bb);
                } catch (RuntimeException e) {
                    p = null;
                }
                Integer j = p == null ? null : at.get(p);
                if (j == null || j == i || own[j] < 0) continue;
                // AA_ROUND2 4: a desire parked by a gate gives the value it would have to that gate
                double base = out.get(j).d().kind == Desire.Kind.GATE ? Math.max(own[i], out.get(i).parked()) : own[i];
                // a prerequisite is worth at least FLOW of what it unlocks (the best giver; values never add up past it)
                double give = Curves.FLOW * Math.max(base, base + flow[i]) - own[j];
                if (give <= next[j]) continue;
                next[j] = give;
                nf[j] = null;
                nf[j] = "(at least " + Math.round(Curves.FLOW * 100) + "% of " + out.get(i).d().id + ": +" + Math.round(give) + ")";
            }
            flow = next;
            from = nf;
        }
        for (int i = 0; i < out.size(); i++) {
            if (flow[i] <= 0) continue;
            Scored s = out.get(i);
            double v = s.score() + flow[i];
            out.set(i, new Scored(s.d(), v, s.rank() + flow[i], s.parts() + " " + from[i], s.skip(), s.own(), s.parked()));
        }
        return out;
    }

    // ---- the tick ----

    @Override protected Status run(Blackboard bb) {
        long now = bb.clock.now();
        bonus = Personality.of(bb).get("margin");          // B3 (2.4): the commitment margin is the personality's
        if (bornAt < 0) bornAt = now;
        boolean gap = lastTickAt >= 0 && now - lastTickAt > GAP_MS;
        long prevTick = lastTickAt;
        lastTickAt = now;
        if (running >= 0) {
            Desire cur = desires.get(running);
            if (gap) {                                 // the slot was paused (danger, held): that time is no stall
                startedAt += now - prevTick;
                lastProgressAt = now;
            }
            Scored beat = challenger(bb, cur, now);
            if (beat != null) {
                interrupts++;
                interruptTimes.addLast(now);
                interrupter = beat.d();
                interruptsByKind.merge(beat.d().kind, 1L, Long::sum);
                String why = (beat.d().kind == Desire.Kind.OWNER ? "urgent: the owner's new goal " : "urgent: ") + beat.d().id + " "
                        + Math.round(beat.score()) + " beat " + cur.id + " " + Math.round(curScore) + " + " + Math.round(bonus);
                bb.port.log("brain switch: " + why);
                cur.node.halt(why);
                if (beat.d().kind == Desire.Kind.OWNER) beat.d().freshUntil = -1;
                running = -1;
                return choose(bb, now, cur);
            }
            Status s = cur.node.tick(bb);
            if (bb.task() != null) taskSeen = true;
            if (s == Status.RUNNING) return watchdog(bb, cur, now);
            ended(bb, cur, s, now);
            running = -1;
            return s == Status.FAILURE ? fail(cur.node.reason()) : s;
        }
        return choose(bb, now, null);
    }

    private double curScore;
    /** The commitment bonus now (the personality's margin, default {@link #BONUS}). */
    private double bonus = BONUS;

    private Scored challenger(Blackboard bb, Desire cur, long now) {
        List<Scored> all = scoreAll(bb);
        lastScored = all;
        Scored c = all.get(running);
        curScore = Math.max(0, c.score());
        wouldNote.clear();
        List<Scored> cands = new ArrayList<>();
        for (Scored s : all) {
            // 100 is the owner's aa do (its command preempts by itself): never an interrupt here
            boolean canUrge = s.d() != cur && s.skip() == null && s.score() < 100 && urgent(s.d(), s.own());
            if (!canUrge) s.d().urgentSince = -1;          // review: the grace starts afresh every time
            if (s.d() == cur || s.skip() != null || s.score() >= 100) continue;
            // grace: an urgent challenger with a grace waits that long after it turned urgent (the running step finishes)
            if (canUrge) {
                if (s.d().urgentSince < 0) s.d().urgentSince = now;
                if (now - s.d().urgentSince < s.d().graceMs) continue;
            }
            boolean owner = s.d().kind == Desire.Kind.OWNER && s.d().freshUntil > now && now - startedAt >= MIN_RUN_MS;
            if (!urgent(s.d(), s.own()) && !owner) continue;
            if (urgent(cur, c.own()) && !urgent(s.d(), s.own())) continue;     // an owner goal never breaks urgent upkeep
            // urgent upkeep breaks an owner goal whatever its number; otherwise the challenger must beat score + bonus
            boolean overOwner = urgent(s.d(), s.own()) && cur.kind == Desire.Kind.OWNER;
            if (!overOwner && s.score() <= curScore + bonus) continue;
            cands.add(s);
        }
        if (cands.isEmpty()) return null;
        cands.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        // AA_ROUND2 2: at most MAX_INTERRUPTS_PER_MIN; over the cap a challenger waits
        while (!interruptTimes.isEmpty() && now - interruptTimes.peekFirst() >= 60_000) interruptTimes.removeFirst();
        if (interruptTimes.size() >= MAX_INTERRUPTS_PER_MIN) {
            // director 2026-10-19: upkeep at CAP_EXEMPT+ (starving) is not capped; it still needs the probe below
            cands.removeIf(s -> {
                boolean exempt = s.d().kind == Desire.Kind.UPKEEP && s.own() >= CAP_EXEMPT;
                if (!exempt) wouldNote.put(s.d().id, "interrupt cap");
                return !exempt;
            });
            if (cands.isEmpty()) return null;
        }
        // AA_ROUND2 2: only a challenger that can start a task may halt the running one
        for (Scored s : cands) {
            // a "cannot start" answer is kept PROBE_HOLD_MS (a rung probe asks the planner and re-scores options)
            Held held = probeHeld.get(s.d().id);
            String why;
            if (held != null && now - held.at() < PROBE_HOLD_MS) why = held.why();
            else {
                why = canStart(bb, s.d());
                if (why == null) probeHeld.remove(s.d().id);
                else probeHeld.put(s.d().id, new Held(why, now));
            }
            if (why == null) return s;
            wouldNote.put(s.d().id, why);
        }
        return null;
    }

    /** AA_ROUND2 2: a challenger's "cannot start" is re-asked at most this often. */
    public static final long PROBE_HOLD_MS = 5_000;
    public static final int MAX_INTERRUPTS_PER_MIN = 2;
    /** Blackboard fact: when an UNDER upkeep desire last could run (ms). */
    public static final String UNDER_WANTED_FACT = "aa.underWantedAt";
    /** Upkeep this urgent is exempt from the cap (probe and false-interrupt cooldown still apply). */
    public static final double CAP_EXEMPT = 90;
    private record Held(String why, long at) {}
    private final Map<String, Held> probeHeld = new HashMap<>();
    private final java.util.ArrayDeque<Long> interruptTimes = new java.util.ArrayDeque<>();
    /** Per challenger id: why it would interrupt but does not (aa why). */
    private final Map<String, String> wouldNote = new HashMap<>();
    /** The last interrupt's challenger (checked on the choose right after the halt), or null. */
    private Desire interrupter;
    /** Counters for aa status: interrupts whose challenger failed at once. */
    public long falseInterrupts;

    /** AA_ROUND2 2: null when the desire could start now, else why not (infeasible, then its probe). */
    static String canStart(Blackboard bb, Desire d) {
        try {
            String why = d.infeasible.apply(bb);
            if (why != null) return why;
            return d.probe == null ? null : d.probe.apply(bb);
        } catch (RuntimeException e) {
            bb.port.log("brain: " + d.id + " probe failed: " + e);
            return "probe failed: " + e;
        }
    }

    /** @param not a desire just interrupted (not picked again this tick) */
    private Status choose(Blackboard bb, long now, Desire not) {
        List<Scored> all = scoreAll(bb);
        lastScored = all;
        wouldNote.clear();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (all.get(i).skip() == null && all.get(i).d() != not) order.add(i);
        order.sort((a, b) -> Double.compare(rankOf(all, b), rankOf(all, a)));     // stable: a tie keeps list order
        StringBuilder why = new StringBuilder();
        Desire checkFalse = interrupter;
        interrupter = null;
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            Desire d = desires.get(i);
            Scored sc = all.get(i);
            bb.pickNote("brain: " + d.id + " " + Math.round(sc.score()) + (i == lastWinner ? "+" + Math.round(bonus) : "") + " [" + sc.parts() + "]" + top3(all, i));
            taskSeen = false;
            Status s = d.node.tick(bb);
            if (bb.task() != null) taskSeen = true;
            if (s == Status.RUNNING) {
                running = i;
                lastWinner = i;
                startedAt = lastProgressAt = now;
                sig = null;
                picks++;
                probe(bb, d.id, "RUNNING (" + Math.round(sc.score()) + top3(all, i) + ")");
                return s;
            }
            if (s == Status.SUCCESS) {
                lastWinner = i;
                picks++;
                ended(bb, d, s, now);
                bb.pickNote(null);
                return s;
            }
            if (why.length() > 0) why.append("; ");
            why.append(d.node.reason());
            if (d == checkFalse && taskSeen) ended(bb, d, s, now);     // it started a task: a real run, not a false interrupt
            else if (d.selfCool && d != checkFalse) ended(bb, d, s, now);
            if (d == checkFalse && !taskSeen) {
                // AA_ROUND2 2: the interrupter could not even start: a false interrupt. It cools down (its own streak),
                // and the interrupted desire is tried next (its commitment bonus kept: lastWinner untouched)
                falseInterrupts++;
                bb.tracer.counts.pointless++;
                failed(bb, d.id, "false interrupt: " + d.node.reason(), now);
                bb.port.log("brain: false interrupt by " + d.id + ": " + d.node.reason());
                if (not != null) {
                    int ni = desires.indexOf(not);
                    if (ni >= 0 && all.get(ni).skip() == null) order.add(k + 1, ni);
                }
                continue;
            }
            if (d.lastTry) break;
        }
        bb.pickNote(null);
        if (order.isEmpty()) {
            probe(bb, "nothing", "no desire can run");
            return fail("no desire can run");
        }
        return fail(why.toString());
    }

    private double rankOf(List<Scored> all, int i) {
        return all.get(i).rank() + (i == lastWinner ? bonus : 0);
    }

    private String top3(List<Scored> all, int skip) {
        List<Scored> s = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (i != skip && all.get(i).skip() == null) s.add(all.get(i));
        s.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(3, s.size()); i++) b.append(i == 0 ? "; next " : ", ").append(s.get(i).d().id).append(' ').append(Math.round(s.get(i).score()));
        return b.toString();
    }

    /** A run ended: counted, a failure after a real task lowers P and cools the desire down. */
    private void ended(Blackboard bb, Desire d, Status s, long now) {
        if (s == Status.SUCCESS) {
            failures.succeeded(d.id);
            if (d.selfCool && d.restMs > 0) failures.rest(d.id, d.restMs, now, "done just now (a rest before it runs again)");
            if (d.kind == Desire.Kind.WANT) Wants.done(bb, d.id, now);   // B3: boredom starts over
            onEnd(d, true, taskSeen, now - startedAt);
        } else if (taskSeen) {
            // P(success) drops; a self-cooling desire is held here (2 min doubling), a rung by its own cooldown
            if (d.selfCool) failures.failed(d.id, null, d.node.reason(), now, bb.in);
            else failures.counted(d.id, null, d.node.reason());
            onEnd(d, false, true, now - startedAt);
        } else if (d.selfCool && d.noRunMs > 0) {
            // AA_ROUND2 3 (was the idle Cooldown's ranOnly): a failure before any task rests it, the next option gets its turn
            failures.rest(d.id, d.noRunMs, now, d.node.reason());
        }
        taskSeen = false;
    }

    /** B2: a measured run. */
    void onEnd(Desire d, boolean ok, boolean ran, long ms) {
        if (ran) stats.record(d.id, ok, ms / 1000.0);       // B2 (2.9): only runs that started a task are measured
    }

    private Status watchdog(Blackboard bb, Desire cur, long now) {
        String s = progressSig(bb);
        Object plan = bb.fact("planning");          // review: an ask waiting for the owner's aa pick is no stall
        boolean asking = plan != null && plan.toString().contains("asking the owner");
        if (!s.equals(sig) || asking || (bb.task() != null && bb.task().paused())) {
            sig = s;
            lastProgressAt = now;
            return Status.RUNNING;
        }
        if (now - lastProgressAt < cur.stuckMs) return Status.RUNNING;
        stuck++;
        String why = "stuck: " + cur.id + " made no progress for " + (now - lastProgressAt) / 1000 + "s";
        bb.port.log("brain " + why + " - halted, cooling down, the next desire runs");
        bb.port.incident("aa " + why);
        cur.node.halt(why);
        failed(bb, cur.id, why, now);
        if (taskSeen) onEnd(cur, false, true, now - startedAt);
        taskSeen = false;
        running = -1;
        return fail(why);
    }

    /** A failure in a row: the desire cools down 2 min, doubling to 16 min. */
    void failed(Blackboard bb, String id, String why, long now) { failures.failed(id, null, why, now, bb.in); }

    public int streak(String id) { return failures.fails(id, null); }

    /** What counts as progress: the position, the bag, the task and its progress, the planner working. */
    static String progressSig(Blackboard bb) {
        var in = bb.in;
        StringBuilder b = new StringBuilder();
        if (in.pos != null) b.append(in.pos[0]).append(',').append(in.pos[1]).append(',').append(in.pos[2]);
        // AA_ROUND2 6: the whole bag map (any item, modded too), not a list of named counts
        b.append('|').append(in.bag == null ? 0 : in.bag.hashCode());
        b.append('|').append(bb.taskId()).append(',').append(bb.task() == null ? -1 : bb.task().progressValue());
        return b.toString();
    }

    private void probe(Blackboard bb, String what, String reason) {
        String key = what + " " + reason.replaceAll("\\d+", "#");
        long now = bb.clock.now();
        if (key.equals(probeLast) && now - probeAt < PROBE_MS) return;
        probeLast = key;
        probeAt = now;
        bb.port.log("brain picked " + what + " -> " + reason);
    }

    // ---- for aa why / aa status ----

    /** The last scored list, highest first: "needs.iron 40 [..]" lines (top n). */
    public List<String> whyLines(int n) {
        List<Scored> s = new ArrayList<>(lastScored);
        s.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        List<String> out = new ArrayList<>();
        for (Scored x : s) {
            if (out.size() >= n) break;
            String would = wouldNote.get(x.d().id);
            out.add(x.d().id + " " + x.d().kind + " " + Math.round(x.score()) + " [" + x.parts() + "]" + (x.skip() != null ? " - " + x.skip() : "")
                    + (would != null ? " - would interrupt, but: " + would : "")
                    + (runningDesire() == x.d() ? " (running)" : ""));
        }
        return out;
    }

    public List<Scored> lastScored() { return lastScored; }

    /** AA_ROUND2 3: "held: desire/option reason (n min)" per failure record holding now (aa why). */
    public List<String> heldLines(long now) { return failures.describe(now); }

    /** "picks 12 (3.1/min), interrupts 0, stuck 0" for aa status. */
    public String counters(long now) {
        double min = Math.max(1, (now - Math.max(0, bornAt)) / 60000.0);
        return "brain: picks " + picks + " (" + Math.round(picks / min * 10) / 10.0 + "/min), interrupts " + interrupts
                + (interruptsByKind.isEmpty() ? "" : " " + interruptsByKind) + " (false " + falseInterrupts + "), stuck halts " + stuck + ", held " + failures.heldCount(now);
    }

    @Override public void halt(String why) {
        if (running >= 0) desires.get(running).node.halt(why);
        running = -1;
        sig = null;
        taskSeen = false;
    }

    @Override public List<Node> children() {
        List<Node> k = new ArrayList<>();
        for (Desire d : desires) k.add(d.node);
        return k;
    }
}
