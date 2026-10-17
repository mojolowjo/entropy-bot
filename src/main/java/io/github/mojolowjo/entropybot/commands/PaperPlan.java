package io.github.mojolowjo.entropybot.commands;

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
    static final long PRIMARY_MS = 4000, FAILURE_MS = 20000;
    static final int SURFACE_R = 48;

    private PaperPlan() {}

    public record Result(String rules, String target, boolean reachable, String type, List<int[]> cells, int breaks, int places,
                         int highest, long ms, String why) {
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
    private volatile long goJob = -1;
    private volatile boolean goBreak, goPlace;

    public Result last() { return last; }

    /** The plan-go job runs and owns breaking/placing (SafetyNet's fourth owner). */
    public static boolean owned() {
        PaperPlan pp = INSTANCE;
        if (pp.goJob < 0) return false;
        try {
            var j = Core.INSTANCE.commands.jobs.job;
            return j != null && !j.done && j.id == pp.goJob;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean ownsBreak() { return owned() && INSTANCE.goBreak; }

    public static boolean ownsPlace() { return owned() && INSTANCE.goPlace; }

    /** Core tick: a plan-go job that ended gives breaking/placing back at once (the safety net is the backstop). */
    public void tick() {
        if (goJob >= 0 && !owned()) {
            goJob = -1;
            try {
                io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore();
                LOG.info("[entropybot] plan go: the job ended, breaking/placing off again");
            } catch (Throwable t) {
                LOG.warn("[entropybot] plan go: couldn't restore the settings: {}", t.toString());
            }
        }
    }

    /** "plan path ..." / "plan go" / "plan last". */
    String command(Commands c, LocalPlayer p, String rest, String from, Consumer<String> report) {
        String[] w = rest.trim().toLowerCase().split("\\s+");
        if (w[0].equals("go")) return go(c, p, from);
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
        boolean ob = s.allowBreak.value, op = s.allowPlace.value;
        double obp = s.blockBreakAdditionalPenalty.value, opp = s.blockPlacementPenalty.value;
        try {
            // the context copies these at construction (game thread), then they are put back at once
            s.allowBreak.value = brk;
            s.allowPlace.value = plc;
            s.blockBreakAdditionalPenalty.value = Math.max(obp, 6.0);     // owner: a walk keeps breaking/placing expensive
            s.blockPlacementPenalty.value = Math.max(opp, 20.0);
            ctx = new baritone.pathing.movement.CalculationContext(b, true);
        } finally {
            s.allowBreak.value = ob;
            s.allowPlace.value = op;
            s.blockBreakAdditionalPenalty.value = obp;
            s.blockPlacementPenalty.value = opp;
        }
        if (plc && !ctx.hasThrowaway) LOG.info("[entropybot] plan: no throwaway blocks in the bag: the plan can't place");
        final String rl = rules, tg = name;
        final Goal g = goal;
        final baritone.pathing.movement.CalculationContext cx = ctx;
        BetterBlockPos start = new BetterBlockPos(me[0], me[1], me[2]);
        running = true;
        LOG.info("[entropybot] plan path {} ({}) from {}: searching (up to {} s)", tg, rl, Jobs.fmt(me), FAILURE_MS / 1000);
        worker.submit(() -> {
            long t0 = System.currentTimeMillis();
            List<int[]> cells = new ArrayList<>();
            String type = "?", why = null;
            boolean reach = false;
            try {
                baritone.pathing.calc.AStarPathFinder pf = new baritone.pathing.calc.AStarPathFinder(start, me[0], me[1], me[2], g,
                        new baritone.utils.pathing.Favoring(null, cx), cx);
                PathCalculationResult r = pf.calculate(PRIMARY_MS, FAILURE_MS);
                type = r.getType().name().toLowerCase();
                IPath path = r.getPath().orElse(null);
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
            Minecraft.getInstance().execute(() -> {
                Result res;
                try {
                    res = count(rl, tg, rc, ty, cells, ms, wy);
                    last = res;
                    LOG.info("[entropybot] {}", res.text());
                } finally {
                    running = false;
                }
                if (report != null) report.accept(res.text());
            });
        });
        return "started: planning a path to " + tg + " (" + rules + ", up to " + FAILURE_MS / 1000 + " s; plan last shows it)";
    }

    /** Breaks: a feet or head cell on the path that is solid now; places: a path cell with no solid block under it. */
    private static Result count(String rules, String target, boolean reach, String type, List<int[]> cells, long ms, String why) {
        int br = 0, pl = 0, hi = Integer.MIN_VALUE;
        Level lv = Minecraft.getInstance().level;
        for (int[] c : cells) {
            hi = Math.max(hi, c[1]);
            if (lv == null) continue;
            BlockPos f = new BlockPos(c[0], c[1], c[2]);
            if (!lv.getBlockState(f).getCollisionShape(lv, f).isEmpty()) br++;
            if (!lv.getBlockState(f.above()).getCollisionShape(lv, f.above()).isEmpty()) br++;
            if (lv.getBlockState(f.below()).getCollisionShape(lv, f.below()).isEmpty() && lv.getFluidState(f.below()).isEmpty()) pl++;
        }
        return new Result(rules, target, reach, type, cells, br, pl, cells.isEmpty() ? 0 : hi, ms, why);
    }

    /** "Out to open sky": standing on a column's top within SURFACE_R (precomputed here, on the game thread). */
    static Goal surfaceGoal(Level lv, int[] me) {
        int n = 2 * SURFACE_R + 1;
        int[] top = new int[n * n];
        for (int dx = -SURFACE_R; dx <= SURFACE_R; dx++)
            for (int dz = -SURFACE_R; dz <= SURFACE_R; dz++) {
                int x = me[0] + dx, z = me[2] + dz;
                top[(dx + SURFACE_R) * n + dz + SURFACE_R] = lv.isLoaded(new BlockPos(x, me[1], z))
                        ? lv.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Integer.MAX_VALUE;
            }
        int here = top[SURFACE_R * n + SURFACE_R];
        return new Goal() {
            int topAt(int x, int z) {
                int dx = x - me[0], dz = z - me[2];
                if (Math.abs(dx) > SURFACE_R || Math.abs(dz) > SURFACE_R) return Integer.MAX_VALUE;
                return top[(dx + SURFACE_R) * n + dz + SURFACE_R];
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

            @Override public String toString() { return "Surface{r=" + SURFACE_R + "}"; }
        };
    }

    /**
     * PATHS_PLAN P3.4: the escape's last steps after the trail retrace: a long walking search to open sky (20 s), and
     * when that finds no way, a plan with breaking and placing from here (the highest point the retrace reached). Only
     * reported ("next: plan go"): digging stays the owner's choice. Never throws.
     */
    public void escape(Commands c, LocalPlayer p, String to) {
        try {
            String s = start(c, p, "surface", "walk", r -> {
                Result res = last;
                if (res != null && res.reachable()) {
                    c.whisper(to, "stuck under ground: " + res.text());
                    return;
                }
                LocalPlayer q = Minecraft.getInstance().player;
                if (q == null) return;
                String s2 = start(c, q, "surface", "break", r2 -> c.whisper(to, "stuck under ground, no way out on foot: " + r2));
                LOG.info("[entropybot] escape plan: {}", s2);
            });
            LOG.info("[entropybot] escape plan: {}", s);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] escape plan failed: {}", e.toString());
        }
    }

    /** "plan go": the last reachable plan, walked with its rules' breaking/placing on for this job only. */
    String go(Commands c, LocalPlayer p, String from) {
        Result r = last;
        if (r == null) return "error: no plan yet - next: plan path surface break";
        if (r.cells().isEmpty()) return "error: the last plan found no way - next: why";
        if (running) return "busy: a plan is being made";
        int[] e = r.cells().get(r.cells().size() - 1);
        boolean brk = r.rules().equals("break"), plc = brk || r.rules().equals("place");
        goBreak = brk;
        goPlace = plc;
        String s = c.jobs.startPlanGo(e, brk, plc, from, id -> goJob = id);
        LOG.info("[entropybot] plan go: to {} ({}), job {}: {}", Jobs.fmt(e), r.rules(), goJob, s);
        return s;
    }
}
