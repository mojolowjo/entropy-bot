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
    public static final long MIN_RUN_MS = 60_000, STUCK_MS = 180_000, FAST_STUCK_MS = 40_000, WAIT_MAX_S = 60, WAIT_MIN_S = 3, CD_BASE_MS = 120_000, CD_MAX_MS = 16 * 60_000,
            GAP_MS = 5_000, PROBE_MS = 60_000;

    /** One scored desire for {@code aa why}. */
    /** @param parked AA_ROUND2 4: the value a parked desire would have (it flows to a GATE prerequisite), else 0 */
    public record Scored(Desire d, double score, double rank, String parts, String skip, double own, double parked) {
        Scored(Desire d, double score, double rank, String parts, String skip) { this(d, score, rank, parts, skip, score, 0); }

        Scored(Desire d, double score, double rank, String parts, String skip, double own) { this(d, score, rank, parts, skip, own, 0); }
    }

    private final List<Desire> desires = new ArrayList<>();
    private int running = -1, lastWinner = -1;
    /** small-4: blackboard fact, when the earliest Failures hold lapses (ms, 0 = none). */
    public static final String NEXT_LAPSE_FACT = "aa.nextLapseAt";

    /** small-4: seconds the idle wait lasts: min(60, until the next hold lapses), at least 3. */
    public static int waitSeconds(long now, long nextLapseAt) {
        if (nextLapseAt <= now) return (int) WAIT_MAX_S;
        return (int) Math.max(WAIT_MIN_S, Math.min(WAIT_MAX_S, (nextLapseAt - now + 999) / 1000));
    }

    private long startedAt, lastProgressAt, lastMoveAt, lastTickAt = -1;
    private String moveSig;
    private boolean taskSeen;
    private String sig;
    /** AA_ROUND2 3: the one failure record (desire holds and fails in a row; the rung pickers' option memories bind to it). */
    public Failures failures = new Failures();
    /** Review 2 (4): the blackboard fact holding the outer brain's {@link Failures} (option memories outside a rung bind to it). */
    public static final String FAILURES_FACT = "aa.failures";
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
        // review 2 (4): a nested option set keeps no record of its own: it shares this brain's one record
        for (Desire d : ds) if (d.node instanceof Brain b && b.nested) b.failures = failures;
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
        // the owner's aa do (100) is never held by the brain's own cooling (the rung forgets its cooldown itself)
        if (skip == null && cu > now && v < 100) skip = "cooling " + (cu - now + 999) / 1000 + "s (" + failures.fails(d.id, null) + " fails)";
        String parts = String.valueOf(Math.round(v));
        if (d.detail != null) {
            try {
                parts = d.detail.apply(bb);
            } catch (RuntimeException e) {
                bb.port.log("brain: " + d.id + " detail failed: " + e);
            }
        }
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
        Desire.Exposure ex = workExposure(bb, d);
        if (v >= 0 && v < 100 && ex != Desire.Exposure.ANY && Night.hostile(bb)) {
            // AA_ROUND2 4 (was B3's +/-10): the night's danger makes surface work dearer and work under ground cheaper;
            // review 2 item 1: before the urgent test, so post-death tools at the surface (98) fall under the shelter
            double danger = danger(bb), m = exposureFactor(ex, danger);
            v = v >= URGENT ? v * m : Math.min(URGENT - 1, v * m);      // review: the night never makes a desire urgent
            parts += " night x" + Math.round(m * 100) / 100.0 + " (danger " + Math.round(danger * 100) / 100.0 + ")";
        }
        if (parkedWhy != null) return new Scored(d, 0, 0, "parked (infeasible)", "parked: " + parkedWhy, 0, cu > now ? 0 : Math.max(0, v));
        double rank = v + (urgent(d, v) ? 1000 : 0);
        double pv = 0;
        if (d.parkedValue != null) {
            try {
                pv = Math.max(0, d.parkedValue.applyAsDouble(bb));
            } catch (RuntimeException e) {
                bb.port.log("brain: " + d.id + " parked value failed: " + e);
            }
        }
        return new Scored(d, v, rank, parts, skip, v, pv);
    }

    /** The night's danger now (Costs.nightDanger at the current facts); 0 when monsters do not spawn (day, Peaceful). */
    public static double danger(Blackboard bb) {
        if (!Night.hostile(bb)) return 0;
        var in = bb.in;
        return Costs.nightDanger(in.difficulty, in.armorPoints, in.health, in.maxHealth, in.torchesNear, in.hostilesNear);
    }

    /** Under this much ground over the bot it is not at the surface (AaLadder.SURFACE_DEPTH). */
    static final int SURFACE_DEPTH = 3;

    /**
     * AA_ROUND4 B1: blackboard fact, an {@code IntBinaryOperator} (x, z) -> the surface's y there (the heightmap the adapter
     * keeps; {@link Integer#MIN_VALUE} = not loaded).
     */
    public static final String SURFACE_Y_FACT = "aa.surfaceY";

    /**
     * AA_ROUND4 B1 (danger where the work is): the night factor follows the site of the work, not a label. A trumping or
     * night-proof desire (shelter, hide, sleep, danger, the owner's help) is never discounted. A desire with a site (its
     * chosen option's target, the base...) is UNDER when the site is {@link #SURFACE_DEPTH}+ under the surface there (the
     * heightmap fact; unknown: the bot's own surface), else SURFACE. A desire with no site keeps its label as an override
     * (idle stone/coal UNDER, wood SURFACE); an ANY one with no site goes by where the bot is.
     */
    static Desire.Exposure workExposure(Blackboard bb, Desire d) {
        if (d.nightProof || d.trumps) return Desire.Exposure.ANY;
        Site s = d.siteOf(bb);
        if (s == null) {
            if (d.exposure != Desire.Exposure.ANY) return d.exposure;
            return bb.in.underDepth >= SURFACE_DEPTH ? Desire.Exposure.UNDER : Desire.Exposure.SURFACE;
        }
        return siteUnder(bb, s) ? Desire.Exposure.UNDER : Desire.Exposure.SURFACE;
    }

    /** The site is SURFACE_DEPTH+ under the surface above it (the heightmap; else the bot's surface: its y + ground over it). */
    static boolean siteUnder(Blackboard bb, Site s) {
        int surface = Integer.MIN_VALUE;
        if (bb.fact(SURFACE_Y_FACT) instanceof java.util.function.IntBinaryOperator h) {
            try {
                surface = h.applyAsInt(s.x(), s.z());
            } catch (RuntimeException e) {
                bb.port.log("brain: surface height failed: " + e);
            }
        }
        if (surface == Integer.MIN_VALUE) {
            int[] me = bb.in.pos;
            if (me == null || me.length < 3) return false;
            surface = me[1] + Math.max(0, bb.in.underDepth);
        }
        return s.y() <= surface - SURFACE_DEPTH;
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
                double give = Tuning.get("flow") * Math.max(base, base + flow[i]) - own[j];
                if (give <= next[j]) continue;
                next[j] = give;
                nf[j] = null;
                nf[j] = "(at least " + Math.round(Tuning.get("flow") * 100) + "% of " + out.get(i).d().id + ": +" + Math.round(give) + ")";
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
        if (!nested) bb.fact(FAILURES_FACT, failures);
        if (!nested) bb.fact(NEXT_LAPSE_FACT, nextLapse(now));      // small-4: the idle wait ends when the next hold does (nested holds too)
        bonus = Personality.of(bb).get("margin");          // B3 (2.4): the commitment margin is the personality's
        if (bornAt < 0) bornAt = now;
        boolean gap = lastTickAt >= 0 && now - lastTickAt > GAP_MS;
        long prevTick = lastTickAt;
        lastTickAt = now;
        if (running >= 0) {
            Desire cur = desires.get(running);
            if (gap) {                                 // the slot was paused (danger, held): that time is no stall
                startedAt += now - prevTick;
                lastProgressAt = lastMoveAt = now;
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
            replanWhileRunning(bb, now);
            Status s = cur.node.tick(bb);
            if (bb.task() != null) taskSeen = true;
            Scored mine = lastScored.size() > running ? lastScored.get(running) : null;
            Boolean metNow = mine == null || s != Status.RUNNING ? null : cur.metNow(bb);
            // aa-switch2 (r2-iron: "night.sleep wanted -1" 0.5 s after its pick): a score <= 0 ends the task only after
            // ZERO_END_MS without a break, and never while the desire's own probe answer is held (a flicker); met = done at once
            boolean goneNow = s == Status.RUNNING && mine != null && reasonGone(mine, metNow)
                    && (Boolean.TRUE.equals(metNow) || !probeFlicker(cur, now));
            if (!goneNow || gap) goneSince = -1;
            else if (goneSince < 0) goneSince = now;
            if (goneNow && (Boolean.TRUE.equals(metNow) || now - goneSince >= ZERO_END_MS)) {          // day-check: the reason is gone; the commitment margin must not keep it alive
                // aa-gate: the desire's own check met = its work is done (a met rung, day for the shelter, healed): no pointless switch
                String why = (Boolean.TRUE.equals(metNow) ? "done: its own check is met: " : "its reason is gone: ") + cur.id + " wanted " + Math.round(mine.score()) + " (" + mine.parts() + ")";
                bb.port.log("brain: ends " + cur.id + ": " + why);
                cur.node.halt(why);
                running = -1;
                return choose(bb, now, cur);
            }
            if (s == Status.RUNNING) return watchdog(bb, cur, now);
            ended(bb, cur, s, now);
            running = -1;
            return s == Status.FAILURE ? fail(cur.node.reason()) : s;
        }
        return choose(bb, now, null);
    }

    /** aa-switch2: how long a running desire's score must stay at or below 0 before the day-check ends it. */
    public static final long ZERO_END_MS = 5_000;
    /** aa-switch2: since when the running desire's reason has been gone without a break (-1: it is not). */
    private long goneSince = -1;

    /** aa-switch2: the running desire's own "cannot start" probe answer is held: its -1 is that flicker, not a gone reason. */
    private boolean probeFlicker(Desire cur, long now) {
        Held h = probeHeld.get(cur.id);
        return h != null && now - h.at() < PROBE_HOLD_MS;
    }

    /** day-check: a running desire (not the owner's) whose desire fell to 0 or below, or is no longer wanted, ends. */
    static boolean reasonGone(Scored c) { return reasonGone(c, null); }

    /**
     * aa-night2 P0 3: with the running desire's own predicate ({@code met}, null = none) that alone decides: a gate whose
     * flow dried up (score -1 while its climb still runs) is not a gone reason ("needs.surface wanted -1" live).
     */
    static boolean reasonGone(Scored c, Boolean met) {
        if (c.d().kind == Desire.Kind.OWNER) return false;
        if (met != null) return met;
        return c.score() <= 0 && (c.skip() == null || c.skip().startsWith("not wanted"));
    }

    /** aa-night2 P0 1: the food bar under which eating beats a trumping (night) desire. */
    public static final int TRUMP_FOOD = 4;

    /**
     * aa-night2 P0 1: a desire marked {@link Desire#trumps()} at its full score ({@link Costs#SHELTER_MAX}: the full night
     * at the surface) beats the running one whatever its number and the commitment margin; only food under
     * {@link #TRUMP_FOOD} and another trumping desire keep running.
     */
    static boolean trumps(Blackboard bb, Scored s, Desire cur) { return trumps(bb, s, cur, 0); }

    /**
     * AA_STEP2 1 (round 5: "urgent: danger 99 beat night.hide 99 + 15" and back every ~3 s): a running desire at the cap
     * itself is never trumped; the commitment margin decides between two at the cap, so they cannot alternate.
     */
    static boolean trumps(Blackboard bb, Scored s, Desire cur, double curScore) {
        if (curScore >= Costs.SHELTER_MAX) return false;
        // AA_CLEANUP 4: a desire that trumps the trumping ones (danger) beats a running shelter or hide at its full score too
        if (!s.d().trumps || s.own() < Costs.SHELTER_MAX || (cur.trumps && !(s.d().overTrumps && !cur.overTrumps))) return false;
        return !(cur.id.equals("needs.food") && bb.in.food < TRUMP_FOOD);
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
            // AA_STEP2 review 1: the cap guard is only between trumping desires; food under TRUMP_FOOD and the owner's
            // fresh order still beat a running trumping one (danger waiting out the night at 99)
            if (cur.trumps && !s.d().trumps && (owner || s.d().id.equals("needs.food") && bb.in.food < TRUMP_FOOD && s.score() > 0)) {
                cands.add(s);
                continue;
            }
            // and the other way round: starving food keeps the slot against a trumping desire (no flip back)
            if (s.d().trumps && cur.id.equals("needs.food") && bb.in.food < TRUMP_FOOD) continue;
            if (!urgent(s.d(), s.own()) && !owner) continue;
            if (urgent(cur, c.own()) && !urgent(s.d(), s.own())) continue;     // an owner goal never breaks urgent upkeep
            // urgent upkeep breaks an owner goal whatever its number; otherwise the challenger must beat score + bonus
            boolean overOwner = urgent(s.d(), s.own()) && cur.kind == Desire.Kind.OWNER;
            if (!overOwner && !trumps(bb, s, cur, curScore) && s.score() <= curScore + bonus) continue;
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
        if (!nested) sequence(bb, all, order, now);
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
                startedAt = lastProgressAt = lastMoveAt = now;
                goneSince = -1;
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
            else if (!taskSeen && d != checkFalse && d.node instanceof io.github.mojolowjo.entropybot.aa.Rung)
                failures.rest(d.id, io.github.mojolowjo.entropybot.aa.core.Cooldown.NO_RUN_MS, now, d.node.reason());      // review 2 (4): a no-task rung fail rests
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

    // ---- AA_ROUND3 2: order planning within the horizon ----

    /** Blackboard fact: the horizon in seconds ({@code aa horizon}, an Integer); absent: {@link Sequencer#DEFAULT_HORIZON_S}. */
    public static final String HORIZON_FACT = "aa.horizon";
    public static final long PLAN_EVERY_MS = 30_000;
    private Sequencer.Plan plan;
    private long planAt = -1;
    /** The plan's next after a SUCCESS (runs without the ranking deciding while its site is near), or null. */
    private String planNext;
    private String planNote;

    public static int horizon(Blackboard bb) {
        return bb.fact(HORIZON_FACT) instanceof Integer h && h > 0 ? h : Sequencer.DEFAULT_HORIZON_S;
    }

    static Site here(Blackboard bb) {
        int[] p = bb.in.pos;
        return p == null ? null : new Site(bb.in.dimension, p[0], p[1], p[2], 0);
    }

    /**
     * A desire's own cost in seconds for the sequencer: live, else measured/hand; AREAS (0.39.2): plus the soft way back
     * when its site lies farther from the nearest base area than the bot stands (AreaFacts.awayS, the old WayBack's
     * return leg as a price, not a scripted leg).
     */
    double costOf(Blackboard bb, Desire d) {
        double live = liveCost(bb, d);
        double c = Double.isNaN(live) ? stats.cost(d.id, d.handCostS) : live;
        Site s = d.siteOf(bb);
        if (s != null && !Double.isNaN(c)) c += io.github.mojolowjo.entropybot.aa.core.AreaFacts.awayS(bb.in.pos, new int[]{s.x(), s.y(), s.z()}, bb.in.nearestBase);
        return c;
    }

    /** Builds the plan from the ranked runnable list (winner first). */
    Sequencer.Plan buildPlan(Blackboard bb, List<Scored> all, List<Integer> order) {
        List<Sequencer.Item> items = new ArrayList<>();
        for (int i : order) {
            if (items.size() >= Sequencer.K) break;
            Scored s = all.get(i);
            if (s.score() <= 0) continue;
            items.add(new Sequencer.Item(s.d().id, costOf(bb, s.d()), s.d().siteOf(bb)));
        }
        planHorizon = horizon(bb);
        return Sequencer.build(here(bb), items, planHorizon);
    }

    private int planHorizon = Sequencer.DEFAULT_HORIZON_S;

    /**
     * Rebuilds the plan and puts its first (or, right after a plan step ended SUCCESS, its next while that is still
     * wanted and its site within {@link Site#SAME}) at the head of {@code order}. Never over urgent upkeep or the owner's
     * aa do (a top rank of 100+).
     */
    private void sequence(Blackboard bb, List<Scored> all, List<Integer> order, long now) {
        String next = planNext;
        planNext = null;
        if (order.isEmpty()) return;
        try {
            plan = buildPlan(bb, all, order);
            planAt = now;
        } catch (RuntimeException e) {
            bb.port.log("brain: plan failed: " + e);
            plan = null;
            return;
        }
        Scored top = all.get(order.get(0));
        if (top.rank() >= 100) { planNote = null; planNext = next; return; }     // review: the next waits out urgent work
        String want = null;
        if (next != null) {
            int j = indexOf(all, order, next);
            Site site = j < 0 ? null : all.get(j).d().siteOf(bb);
            Site me = here(bb);
            if (j >= 0 && all.get(j).score() > 0 && site != null && me != null && site.near(me)) {
                want = next;
                planNote = "plan next " + next + " (its site is near, no re-pick)";
            } else planNote = "plan next " + next + " dropped (" + (j < 0 ? "not runnable" : all.get(j).score() <= 0 ? "not wanted" : "its site moved away") + "): the normal pick";
            bb.port.log("brain: " + planNote);
        }
        if (want == null && plan != null && plan.steps().size() > 1) want = plan.first();
        if (want == null) return;
        int j = indexOf(all, order, want);
        if (j < 0) return;
        order.remove(Integer.valueOf(j));
        order.add(0, j);
    }

    private static int indexOf(List<Scored> all, List<Integer> order, String id) {
        for (int i : order) if (all.get(i).d().id.equals(id)) return i;
        return -1;
    }

    /** "plan: iron (at ..) then coal (same place, saves 40 s)" for aa why, or null before the first plan. */
    public String planText() {
        if (plan == null) return null;
        return "plan (horizon " + planHorizon + " s): " + plan.text() + (planNote == null ? "" : "; " + planNote);
    }

    public Sequencer.Plan plan() { return plan; }

    /** Every {@link #PLAN_EVERY_MS} while a desire runs the plan is rebuilt for aa why. */
    private void replanWhileRunning(Blackboard bb, long now) {
        if (nested || planAt >= 0 && now - planAt < PLAN_EVERY_MS) return;
        List<Scored> all = lastScored;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (all.get(i).skip() == null) order.add(i);
        order.sort((a, b) -> Double.compare(rankOf(all, b), rankOf(all, a)));
        if (running >= 0 && running < all.size()) {          // the running desire is the plan's first
            order.remove(Integer.valueOf(running));
            order.add(0, running);
        }
        planAt = now;
        try {
            plan = order.isEmpty() ? null : buildPlan(bb, all, order);
        } catch (RuntimeException e) {
            bb.port.log("brain: plan failed: " + e);
        }
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
            if (!nested && plan != null) planNext = plan.next(d.id);      // AA_ROUND3 2: the plan's next runs while near
            if (d.selfCool && d.restMs > 0) failures.rest(d.id, d.restMs, now, "done just now (a rest before it runs again)");
            if (d.kind == Desire.Kind.WANT) Wants.done(bb, d.id, now);   // B3: boredom starts over
            onEnd(d, true, taskSeen, now - startedAt);
        } else if (taskSeen) {
            // P(success) drops; a self-cooling desire is held here (2 min doubling), a rung by its own cooldown
            if (d.selfCool && !d.optionHolds) failures.failed(d.id, null, d.node.reason(), now, bb.in);
            else failures.counted(d.id, null, d.node.reason());
            onEnd(d, false, true, now - startedAt);
        } else if (d.selfCool && d.noRunMs > 0) {
            // AA_ROUND2 3 (was the idle Cooldown's ranOnly): a failure before any task rests it, the next option gets its turn
            failures.rest(d.id, d.noRunMs, now, d.node.reason());
        } else if (!d.selfCool && d.node instanceof io.github.mojolowjo.entropybot.aa.Rung) {
            // review 2 (4, 07:52: seven picks in 60 s, each a one-second no-task fail): a rung that failed without
            // starting a task rests NO_RUN_MS in the one record, so the next desire gets its turn
            failures.rest(d.id, io.github.mojolowjo.entropybot.aa.core.Cooldown.NO_RUN_MS, now, d.node.reason());
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
        boolean asking = plan != null && (plan.toString().contains("asking the owner")
                || plan.toString().contains(io.github.mojolowjo.entropybot.aa.Rung.SETTLE_WAIT));      // review 2: a bounded settle wait is no stall
        if (!s.equals(sig) || asking || (bb.task() != null && bb.task().paused())) {
            sig = s;
            lastProgressAt = now;
            // small-2: the second clock - the position and the bag
            String m = moveSigOf(bb);
            if (!m.equals(moveSig)) {
                moveSig = m;
                lastMoveAt = now;
            }
            return Status.RUNNING;
        }
        String m = moveSigOf(bb);
        if (!m.equals(moveSig)) {
            moveSig = m;
            lastMoveAt = now;
        }
        // small-2: standing still with the bag unchanged and no task progress for FAST_STUCK_MS is a stall; the long
        // clock stays for work that moves or collects (a desire with its own patience, a shelter, keeps only its own)
        boolean waiting = bb.task() != null && bb.task().spec() != null && bb.task().spec().kind() == io.github.mojolowjo.entropybot.aa.core.TaskKind.WAIT;     // a wait stands still on purpose
        boolean fast = !waiting && cur.stuckMs == STUCK_MS && now - lastMoveAt >= FAST_STUCK_MS && now - lastProgressAt >= FAST_STUCK_MS;
        if (!fast && now - lastProgressAt < cur.stuckMs) return Status.RUNNING;
        stuck++;
        String why = "stuck: " + cur.id + " made no progress for " + (now - lastProgressAt) / 1000 + "s" + (fast ? " (no move, no bag change)" : "");
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
    /** small-2: position and bag only. */
    static String moveSigOf(Blackboard bb) {
        var in = bb.in;
        return (in.pos == null ? "" : in.pos[0] + "," + in.pos[1] + "," + in.pos[2]) + "|" + (in.bag == null ? 0 : in.bag.hashCode());
    }

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

    /** Why this challenger would interrupt but does not (the last scoring), or null (the dashboard's live tree). */
    public String wouldWhy(String id) { return wouldNote.get(id); }

    /** AA_ROUND2 3: "held: desire/option reason (n min)" per failure record holding now (aa why). */
    public List<String> heldLines(long now) {
        List<String> out = new ArrayList<>(failures.describe(now));
        for (Desire d : desires) if (d.node instanceof Brain b && b.failures != failures) out.addAll(b.heldLines(now));
        return out;
    }

    // ---- idle as one desire (director 2026-10-19): a nested brain is one desire's option set ----

    /** This brain is one desire's option set inside another brain (it leaves the lapse fact to the outer one). */
    private boolean nested;

    public Brain nested() {
        nested = true;
        return this;
    }

    /** The earliest hold lapse here or in a nested brain (ms), 0 none. */
    public long nextLapse(long now) {
        long best = failures.nextLapse(now);
        for (Desire d : desires) {
            if (!(d.node instanceof Brain b)) continue;
            long l = b.nextLapse(now);
            if (l > 0 && (best <= 0 || l < best)) best = l;
        }
        return best;
    }

    /** Scores the options now and keeps the list (the outer desire's score, aa why, the live tree). */
    public List<Scored> scoreOptions(Blackboard bb) {
        lastScored = scoreAll(bb);
        return lastScored;
    }

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
