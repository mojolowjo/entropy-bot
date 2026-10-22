package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 0.32.3 (PATHS_PLAN P3): paper planning. {@code plan path <x y z | surface | place> [walk|place|break]} runs Baritone's
 * own A* ({@code AStarPathFinder}, internal; the unoptimized jar) with a context built under those rules, on a worker
 * thread with a long budget (4 s primary, 20 s failure), and reports: reachable, moves, blocks to break and to place,
 * the highest point and the path; nothing changes in the world. {@code plan go} walks the last plan with breaking and
 * placing on for that job only (owner: {@link #owned}, the safety net turns them off the moment the job ends).
 * The protected list and the area guard (the A* mixin) stay in the search. Loader API: none (Baritone API + internals,
 * vanilla client Level reads on the game thread).
 */
public final class PaperPlan {
    private static final Logger LOG = LogUtils.getLogger();
    public static final PaperPlan INSTANCE = new PaperPlan();
    static long primaryMs() { return Tuning.l("paperPlan.primaryMs"); }
    static long failureMs() { return Tuning.l("paperPlan.failureMs"); }
    static int surfaceR() { return Tuning.i("paperPlan.surfaceR"); }

    private PaperPlan() {}

    public record Result(String rules, String target, boolean reachable, String type, List<int[]> cells, int breaks, int places,
                         int highest, long ms, String why, String dim, int[] start) {
        public String text() {
            if (cells.isEmpty()) return "plan path " + target + " (" + rules + "): no way found (" + type + ", " + ms + " ms" + (why == null ? "" : "; " + why) + ")";
            int[] e = cells.get(cells.size() - 1);
            StringBuilder sb = new StringBuilder("plan path ").append(target).append(" (").append(rules).append("): ")
                    .append(reachable ? "reachable" : "partial: ends at " + e[0] + " " + e[1] + " " + e[2])
                    .append(", ").append(cells.size()).append(" moves, breaks ").append(breaks).append(", places ").append(places)
                    .append(", highest y ").append(highest).append(", ").append(ms).append(" ms; path:");
            int step = Math.max(1, cells.size() / 8);
            for (int i = 0; i < cells.size(); i += step) sb.append(' ').append(cells.get(i)[0]).append(',').append(cells.get(i)[1]).append(',').append(cells.get(i)[2]);
            sb.append(" -> ").append(e[0]).append(',').append(e[1]).append(',').append(e[2]);
            if (reachable && !"walk".equals(rules)) sb.append(" - next: plan go");
            else if (reachable) sb.append(" - next: plan go (or goto ").append(e[0]).append(' ').append(e[1]).append(' ').append(e[2]).append(')');
            return sb.toString();
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropybot-paper-plan");
        t.setDaemon(true);
        return t;
    });
    private volatile Result last;
    private volatile boolean running;
    /** review 3: the plan-go job itself (not its id): a new job never matches a stale owner. */
    private volatile Object goJobObj;
    private volatile long goJob = -1;

    public Result last() { return last; }

    /** The plan-go job runs (0.39.z8: it owns no Baritone setting; its walk uses Baritone's own travel settings). */
    public static boolean owned() {
        PaperPlan pp = INSTANCE;
        Object o = pp.goJobObj;
        if (o == null) return false;
        try {
            var j = Core.INSTANCE.commands.jobs.job;
            return j != null && !j.done && j == o;
        } catch (Throwable t) {
            Quiet.warn("PaperPlan.owned failed, carrying on with the fallback", t);
            return false;
        }
    }

    /** Core tick: a plan-go job that ended lets go of the plan-go slot (and the stop leftovers are put back). */
    public void tick() {
        if (goJobObj != null && !owned()) {
            goJobObj = null;
            goJob = -1;
            try {
                io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore();
                LOG.info("[entropybot] plan go: the job ended");
            } catch (Throwable t) {
                LOG.warn("[entropybot] plan go: couldn't restore the settings: {}", t.toString());
            }
        }
    }

    /** review 3: leaving or switching the world: no owner, no plan (its cells belong to the old world). */
    public void reset(String why) {
        boolean was = goJobObj != null;
        goJobObj = null;
        goJob = -1;
        last = null;
        if (was) {
            try { io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore(); } catch (Throwable caught) { Quiet.warn("PaperPlan.reset failed, carrying on with the fallback", caught); }
            LOG.info("[entropybot] plan go: {}: plan dropped", why);
        }
    }

    /** "plan path ..." / "plan go" / "plan last". */
    String command(Commands c, LocalPlayer p, String rest, String from, Consumer<String> report) {
        String[] w = rest.trim().toLowerCase().split("\\s+");
        if (w[0].equals("go")) return go(c, p, from, w.length > 1 && w[1].equals("anyway"));
        if (w[0].equals("last")) return last == null ? "no plan yet - plan path <x y z | surface | place> [walk|place|break]" : last.text();
        String args = rest.trim().replaceFirst("(?i)^path\\s*", "");
        String rules = "walk";
        String[] a = args.split("\\s+");
        if (a.length > 0 && a[a.length - 1].matches("(?i)walk|place|break")) {
            rules = a[a.length - 1].toLowerCase();
            args = args.substring(0, args.length() - a[a.length - 1].length()).trim();
        }
        return start(c, p, args, rules, report);
    }

    /** Starts a plan; the result comes through report (and "plan last"). */
    public String start(Commands c, LocalPlayer p, String target, String rules, Consumer<String> report) {
        if (running) return "busy: a plan is being made (plan last when it ends)";
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        Level lv = p.level();
        int[] me = Jobs.here(p);
        Goal goal;
        String name = target.isBlank() ? "surface" : target.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(-?\\d+) (-?\\d+) (-?\\d+)$").matcher(name);
        if (m.find()) {
            goal = new baritone.api.pathing.goals.GoalBlock(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } else if (name.equalsIgnoreCase("surface")) {
            goal = surfaceGoal(lv, me);
        } else {
            JsonObject pl = Core.INSTANCE.knowledge.places().get(name.toLowerCase());
            if (pl == null) return "error: I have no place called " + name + " - next: plan path surface " + rules;
            int[] q = Jobs.pos(pl);
            goal = new baritone.api.pathing.goals.GoalNear(new BlockPos(q[0], q[1], q[2]), 1);
        }
        boolean brk = rules.equals("break"), plc = rules.equals("break") || rules.equals("place");
        baritone.pathing.movement.CalculationContext ctx;
        Settings s = BaritoneAPI.getSettings();
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): the mod never sets Baritone's breaking / placing; a plan
        // is made with Baritone's own. Rules that ask for something else fail loudly instead of planning a different world.
        boolean brkNow = s.allowBreak.value, plcNow = s.allowPlace.value;
        if (brk != brkNow || plc != plcNow)
            return "error: plan path " + rules + " is retired: AA drives (Baritone's own settings now: breaking " + (brkNow ? "on" : "off")
                    + ", placing " + (plcNow ? "on" : "off") + "; the plan can only use those - plan path " + target + " " + (brkNow ? "break" : plcNow ? "place" : "walk") + ")";
        double obp = s.blockBreakAdditionalPenalty.value, opp = s.blockPlacementPenalty.value;
        try {
            // the context copies these at construction (game thread), then they are put back at once
            s.blockBreakAdditionalPenalty.value = Math.max(obp, 6.0);     // owner: a walk keeps breaking/placing expensive
            s.blockPlacementPenalty.value = Math.max(opp, 20.0);
            // mining plan phase 1: a plan's own context: BUILD (no travel cap) when it may break or place, else walking only
            ctx = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.withIntent(
                    brk || plc ? io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.BUILD : io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.NONE,
                    () -> new baritone.pathing.movement.CalculationContext(b, true));
        } finally {
            s.blockBreakAdditionalPenalty.value = obp;
            s.blockPlacementPenalty.value = opp;
        }
        if (plc && !ctx.hasThrowaway) LOG.info("[entropybot] plan: no throwaway blocks in the bag: the plan can't place");
        final String rl = rules, tg = name;
        final Goal g = goal;
        final baritone.pathing.movement.CalculationContext cx = ctx;
        BetterBlockPos start = new BetterBlockPos(me[0], me[1], me[2]);
        final String worldAt = Core.INSTANCE.worldKey(), dimAt = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
        running = true;
        LOG.info("[entropybot] plan path {} ({}) from {}: searching (up to {} s)", tg, rl, Jobs.fmt(me), failureMs() / 1000);
        worker.submit(() -> {
            long t0 = System.currentTimeMillis();
            List<int[]> cells = new ArrayList<>();
            String type = "?", why = null;
            boolean reach = false;
            IPath found = null;
            try {
                baritone.pathing.calc.AStarPathFinder pf = new baritone.pathing.calc.AStarPathFinder(start, me[0], me[1], me[2], g,
                        new baritone.utils.pathing.Favoring(null, cx), cx);
                // live: a break plan returned a 24-move segment at the 4 s primary timeout: digging plans use the whole budget
                PathCalculationResult r = pf.calculate(rl.equals("walk") ? primaryMs() : failureMs(), failureMs());
                type = r.getType().name().toLowerCase();
                IPath path = r.getPath().orElse(null);
                found = path;
                if (path != null) for (BetterBlockPos q : path.positions()) cells.add(new int[]{q.x, q.y, q.z});
                reach = path != null && r.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
            } catch (Throwable t) {
                why = t.toString();
                type = "exception";
                LOG.warn("[entropybot] plan path failed: {}", t.toString());
            }
            long ms = System.currentTimeMillis() - t0;
            final boolean rc = reach;
            final String ty = type, wy = why;
            final IPath fp = found;
            Minecraft.getInstance().execute(() -> {
                Result res;
                try {
                    // review 6: a plan made for another world (or with no world now) is dropped
                    if (Minecraft.getInstance().level == null || !java.util.Objects.equals(worldAt, Core.INSTANCE.worldKey())) {
                        LOG.info("[entropybot] plan path {}: dropped, the world changed meanwhile", tg);
                        return;
                    }
                    res = count(rl, tg, rc, ty, cells, ms, wy, fp, cx, dimAt, me);
                    last = res;
                    LOG.info("[entropybot] {}", res.text());
                } finally {
                    running = false;
                }
                if (report != null) report.accept(res.text());
            });
        });
        return "started: planning a path to " + tg + " (" + rules + ", up to " + failureMs() / 1000 + " s; plan last shows it)";
    }

    /** Breaks: a feet or head cell on the path that is solid now; places: a path cell with no solid block under it. */
    /**
     * Breaks and places from Baritone's own movements (toBreak/toPlace, live: the cell count said "breaks 0" on a path that
     * dug); the old cell count only when the movements can't be read.
     */
    private static Result count(String rules, String target, boolean reach, String type, List<int[]> cells, long ms, String why,
                                IPath path, baritone.pathing.movement.CalculationContext cx, String dim, int[] start) {
        int br = 0, pl = 0, hi = Integer.MIN_VALUE;
        for (int[] c : cells) hi = Math.max(hi, c[1]);
        boolean counted = false;
        if (path != null) {
            try {
                for (baritone.api.pathing.movement.IMovement mv : path.movements()) {
                    if (mv instanceof baritone.pathing.movement.Movement m) {
                        br += m.toBreak(cx.bsi).size();
                        pl += m.toPlace(cx.bsi).size();
                    }
                }
                counted = true;
            } catch (Throwable t) {
                LOG.info("[entropybot] plan: movements unreadable ({}), counting cells", t.toString());
                br = pl = 0;
            }
        }
        Level lv = Minecraft.getInstance().level;
        if (!counted && lv != null) for (int[] c : cells) {
            BlockPos f = new BlockPos(c[0], c[1], c[2]);
            if (!lv.getBlockState(f).getCollisionShape(lv, f).isEmpty()) br++;
            if (!lv.getBlockState(f.above()).getCollisionShape(lv, f.above()).isEmpty()) br++;
            if (lv.getBlockState(f.below()).getCollisionShape(lv, f.below()).isEmpty() && lv.getFluidState(f.below()).isEmpty()) pl++;
        }
        return new Result(rules, target, reach, type, cells, br, pl, cells.isEmpty() ? 0 : hi, ms, why, dim, start);
    }

    /** "Out to open sky": standing on a column's top within SURFACE_R (precomputed here, on the game thread). */
    static Goal surfaceGoal(Level lv, int[] me) {
        int n = 2 * surfaceR() + 1;
        int[] top = new int[n * n];
        for (int dx = -surfaceR(); dx <= surfaceR(); dx++)
            for (int dz = -surfaceR(); dz <= surfaceR(); dz++) {
                int x = me[0] + dx, z = me[2] + dz;
                top[(dx + surfaceR()) * n + dz + surfaceR()] = lv.isLoaded(new BlockPos(x, me[1], z))
                        ? lv.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Integer.MAX_VALUE;
            }
        int here = top[surfaceR() * n + surfaceR()];
        return new Goal() {
            int topAt(int x, int z) {
                int dx = x - me[0], dz = z - me[2];
                if (Math.abs(dx) > surfaceR() || Math.abs(dz) > surfaceR()) return Integer.MAX_VALUE;
                return top[(dx + surfaceR()) * n + dz + surfaceR()];
            }

            @Override public boolean isInGoal(int x, int y, int z) {
                int t = topAt(x, z);
                return t != Integer.MAX_VALUE && y >= t;
            }

            @Override public double heuristic(int x, int y, int z) {
                int t = topAt(x, z);
                if (t == Integer.MAX_VALUE) t = here == Integer.MAX_VALUE ? y : here;
                return Math.max(0, t - y) * 4.0;
            }

            @Override public String toString() { return "Surface{r=" + surfaceR() + "}"; }
        };
    }

    /**
     * 0.36.a6: AA's pillar out of a shaft: a plain walk to {@code e} (straight above) with Baritone's own travel settings (0.39.z8: no setting is turned on
     * for it), so Baritone pillars up with its throwaway blocks.
     */
    String pillar(Commands c, int[] e, String from) {
        if (running) return "busy: a plan is being made";
        if (owned()) return "busy: a plan go is walking";
        String s = c.jobs.startPlanGo(e, from, id -> { goJob = id; goJobObj = c.jobs.job; });
        LOG.info("[entropybot] pillar: to {} (Baritone's own travel settings), job {}: {}", Jobs.fmt(e), goJob, s);
        return s;
    }

    /** aa-replan 2: tunnel up + pillar to {@code e} (straight over me): with Baritone's own travel settings. */
    String tunnelUp(Commands c, int[] e, String from) {
        if (running) return "busy: a plan is being made";
        if (owned()) return "busy: a plan go is walking";
        String s = c.jobs.startPlanGo(e, from, id -> { goJob = id; goJobObj = c.jobs.job; });
        LOG.info("[entropybot] tunnel up: to {} (Baritone's own travel settings), job {}: {}", Jobs.fmt(e), goJob, s);
        return s;
    }

    /** "plan go": the last reachable plan, walked with Baritone's own travel settings (the plan was made with them). */
    String go(Commands c, LocalPlayer p, String from, boolean anyway) {
        // review 2: every check first; the rules change only for a walk that starts
        if (running) return "busy: a plan is being made (plan last when it ends)";
        if (owned()) return "busy: a plan go is walking - next: stop, then plan go";
        Result r = last;
        if (r == null) return "error: no plan yet - next: plan path surface break";
        if (r.cells().isEmpty()) return "error: the last plan found no way - next: plan path surface break";
        String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(p.level());
        if (r.dim() != null && !r.dim().equals(dim)) return "error: the last plan is for " + r.dim() + " - next: plan path again here";
        int[] me = Jobs.here(p);
        if (r.start() != null && Jobs.distSq(me, r.start()) > 36)
            return "error: the last plan starts at " + Jobs.fmt(r.start()) + ", I am at " + Jobs.fmt(me) + " - next: plan path again from here";
        int[] e = r.cells().get(r.cells().size() - 1);
        if (!r.reachable() && !anyway) return "the last plan is partial: it ends at " + Jobs.fmt(e) + ", not at " + r.target() + " - next: plan go anyway (walks to its end), or plan path again";
        String s = c.jobs.startPlanGo(e, from, id -> { goJob = id; goJobObj = c.jobs.job; });
        LOG.info("[entropybot] plan go: to {} ({}), job {}: {}", Jobs.fmt(e), r.rules(), goJob, s);
        return s;
    }
}
