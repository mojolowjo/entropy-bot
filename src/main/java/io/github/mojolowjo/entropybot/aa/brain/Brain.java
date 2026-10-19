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
    public record Scored(Desire d, double score, double rank, String parts, String skip) {}

    private final List<Desire> desires = new ArrayList<>();
    private int running = -1, lastWinner = -1;
    private long startedAt, lastProgressAt, lastTickAt = -1;
    private boolean taskSeen;
    private String sig;
    private final Map<String, Long> coolUntil = new HashMap<>();
    private final Map<String, Integer> streak = new HashMap<>();
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
        String skip = v < 0 ? "not wanted" : null;
        Long cu = coolUntil.get(d.id);
        if (skip == null && cu != null && cu > now) skip = "cooling " + (cu - now + 999) / 1000 + "s (" + streak.getOrDefault(d.id, 0) + " fails)";
        double rank = v + (urgent(d, v) ? 1000 : 0);
        return new Scored(d, v, rank, String.valueOf(Math.round(v)), skip);
    }

    static boolean urgent(Desire d, double v) { return d.kind == Desire.Kind.UPKEEP && v >= URGENT; }

    /** Every desire scored now, in list order. */
    public List<Scored> scoreAll(Blackboard bb) {
        long now = bb.clock.now();
        List<Scored> out = new ArrayList<>();
        for (Desire d : desires) out.add(score(bb, d, now));
        return out;
    }

    // ---- the tick ----

    @Override protected Status run(Blackboard bb) {
        long now = bb.clock.now();
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
                interruptsByKind.merge(beat.d().kind, 1L, Long::sum);
                String why = (beat.d().kind == Desire.Kind.OWNER ? "urgent: the owner's new goal " : "urgent: ") + beat.d().id + " "
                        + Math.round(beat.score()) + " beat " + cur.id + " " + Math.round(curScore) + " + " + Math.round(BONUS);
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

    private Scored challenger(Blackboard bb, Desire cur, long now) {
        List<Scored> all = scoreAll(bb);
        lastScored = all;
        Scored c = all.get(running);
        curScore = Math.max(0, c.score());
        Scored best = null;
        for (Scored s : all) {
            // 100 is the owner's aa do (its command preempts by itself): never an interrupt here
            if (s.d() == cur || s.skip() != null || s.score() >= 100) continue;
            boolean owner = s.d().kind == Desire.Kind.OWNER && s.d().freshUntil > now && now - startedAt >= MIN_RUN_MS;
            if (!urgent(s.d(), s.score()) && !owner) continue;
            if (urgent(cur, c.score()) && !urgent(s.d(), s.score())) continue;     // an owner goal never breaks urgent upkeep
            // urgent upkeep breaks an owner goal whatever its number; otherwise the challenger must beat score + bonus
            boolean overOwner = urgent(s.d(), s.score()) && cur.kind == Desire.Kind.OWNER;
            if (!overOwner && s.score() <= curScore + BONUS) continue;
            if (best == null || s.rank() > best.rank()) best = s;
        }
        return best;
    }

    /** @param not a desire just interrupted (not picked again this tick) */
    private Status choose(Blackboard bb, long now, Desire not) {
        List<Scored> all = scoreAll(bb);
        lastScored = all;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (all.get(i).skip() == null && all.get(i).d() != not) order.add(i);
        order.sort((a, b) -> Double.compare(rankOf(all, b), rankOf(all, a)));     // stable: a tie keeps list order
        StringBuilder why = new StringBuilder();
        for (int i : order) {
            Desire d = desires.get(i);
            Scored sc = all.get(i);
            bb.pickNote("brain: " + d.id + " " + Math.round(sc.score()) + (i == lastWinner ? "+" + Math.round(BONUS) : "") + " [" + sc.parts() + "]" + top3(all, i));
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
        return all.get(i).rank() + (i == lastWinner ? BONUS : 0);
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
            streak.remove(d.id);
            coolUntil.remove(d.id);
            onEnd(d, true, taskSeen, now - startedAt);
        } else if (taskSeen) {
            streak.merge(d.id, 1, Integer::sum);      // P(success) drops; the node's own cooldown holds it off
            onEnd(d, false, true, now - startedAt);
        }
        taskSeen = false;
    }

    /** B2 hook (measured costs); B1 does nothing more. */
    void onEnd(Desire d, boolean ok, boolean ran, long ms) {}

    private Status watchdog(Blackboard bb, Desire cur, long now) {
        String s = progressSig(bb);
        Object plan = bb.fact("planning");          // review: an ask waiting for the owner's aa pick is no stall
        boolean asking = plan != null && plan.toString().contains("asking the owner");
        if (!s.equals(sig) || asking || (bb.task() != null && bb.task().paused())) {
            sig = s;
            lastProgressAt = now;
            return Status.RUNNING;
        }
        if (now - lastProgressAt < STUCK_MS) return Status.RUNNING;
        stuck++;
        String why = "stuck: " + cur.id + " made no progress for " + (now - lastProgressAt) / 1000 + "s";
        bb.port.log("brain " + why + " - halted, cooling down, the next desire runs");
        bb.port.incident("aa " + why);
        cur.node.halt(why);
        failed(cur.id, now);
        if (taskSeen) onEnd(cur, false, true, now - startedAt);
        taskSeen = false;
        running = -1;
        return fail(why);
    }

    /** A failure in a row: the desire cools down 2 min, doubling to 16 min. */
    void failed(String id, long now) {
        int n = streak.merge(id, 1, Integer::sum);
        coolUntil.put(id, now + Math.min(CD_MAX_MS, CD_BASE_MS << Math.min(n - 1, 20)));
    }

    public int streak(String id) { return streak.getOrDefault(id, 0); }

    /** What counts as progress: the position, the bag, the task and its progress, the planner working. */
    static String progressSig(Blackboard bb) {
        var in = bb.in;
        StringBuilder b = new StringBuilder();
        if (in.pos != null) b.append(in.pos[0]).append(',').append(in.pos[1]).append(',').append(in.pos[2]);
        b.append('|').append(in.freeSlots).append(',').append(in.wood).append(',').append(in.cobble).append(',').append(in.coal)
                .append(',').append(in.ironIngots).append(',').append(in.torches).append(',').append(in.planks).append(',')
                .append(in.pickTier).append(',').append(in.pickaxes).append(',').append(in.foodItems);
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
            out.add(x.d().id + " " + x.d().kind + " " + Math.round(x.score()) + " [" + x.parts() + "]" + (x.skip() != null ? " - " + x.skip() : "")
                    + (runningDesire() == x.d() ? " (running)" : ""));
        }
        return out;
    }

    public List<Scored> lastScored() { return lastScored; }

    /** "picks 12 (3.1/min), interrupts 0, stuck 0" for aa status. */
    public String counters(long now) {
        double min = Math.max(1, (now - Math.max(0, bornAt)) / 60000.0);
        return "brain: picks " + picks + " (" + Math.round(picks / min * 10) / 10.0 + "/min), interrupts " + interrupts
                + (interruptsByKind.isEmpty() ? "" : " " + interruptsByKind) + ", stuck halts " + stuck;
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
