package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.TickPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.trail.Trail;
import io.github.mojolowjo.entropybot.aa.trail.Trails;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D4 adapter: records the trail from the feet each client tick (only an on-ground cell change does any work), reads the
 * world's trail.json at join and writes it every trail.saveS when changed, notes the fork's saved routes (IPricedPath
 * routeCounts, read by name: rule 7 "not readable in this fork jar"), and prices one level up from Baritone's own costs
 * ({@link #climb}). Loader notes: vanilla LocalPlayer#blockPosition / onGround, Level block states and
 * ItemStack#getDestroySpeed through GameTimesLive; Baritone's ActionCosts and Settings; BotFiles for the file. Client
 * thread only; never throws.
 */
public final class TrailRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private TrailRuntime() {}

    private static BlockPos lastFeet;
    private static long lastSave;
    private static long forkSaved = -1;
    private static boolean forkSaidMissing;
    private static long probeLast = -1;

    /** The board's trails (null: no AA runtime yet). */
    static Trails trails() {
        Commands c = Core.INSTANCE.commands;
        return c == null || c.aaRuntime == null ? null : Trails.of(c.aaRuntime.bb);
    }

    /** The trail of the dimension the bot is in (null: no world or no runtime). */
    static Trail here() {
        Trails t = trails();
        var lv = Minecraft.getInstance().level;
        return t == null || lv == null ? null : t.of(io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv));
    }

    public static void tick(long tick) {
        try {
            tickInner(tick);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] trail: the recorder failed this tick: {}", e.toString());
        }
    }

    private static void tickInner(long tick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Trails ts = trails();
        if (p == null || mc.level == null || ts == null) return;
        String key = Core.INSTANCE.worldKey();
        var files = Core.INSTANCE.files();
        if (key == null || files == null) return;
        if (!ts.loaded() || !key.equals(ts.world())) {
            LOG.info("[entropybot] trail: {}", ts.load(key, files.readJson(Trails.FILE)));
            lastFeet = null;
        }
        Trail t = ts.of(io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level));
        t.loadedEmpty();
        noteForkRoute(t);
        if (StepSignalsLive.destroyStage(p) >= 0) t.breakTick();     // D4 pass two: breaking time is kept apart from the walk's seconds
        if (p.onGround()) {
            BlockPos f = p.blockPosition();
            if (!f.equals(lastFeet)) {
                lastFeet = f;
                boolean out = Jobs.belowSurface(mc.level, f.getX(), f.getY(), f.getZ()) == 0;
                int before = t.segs().size();
                String line = t.offer(new Trail.Cell(f.getX(), f.getY(), f.getZ()), tick, out, Tuning.i("trail.jumpBlocks"));
                if (line != null) LOG.info("[entropybot] {}", line);
                if (probe() && t.segs().size() > before) {
                    var s = t.segs().get(t.segs().size() - 1);
                    LOG.info("[entropybot] probe trail: segment #{} {} -> {} {} {} s{} | {} segments", t.segs().size(), s.from().text(), s.to().text(),
                            s.oneWay() ? "ONE_WAY drop " + s.drop() : "WALK", Math.round(s.walkedS()), s.forkRoute() ? " (a fork route)" : "", t.segs().size());
                }
            }
        }
        if (probe() && tick - probeLast >= 20L * 60) {          // structural: one count line a minute (ticks)
            probeLast = tick;
            LOG.info("[entropybot] probe trail: {}", counts(t));
        }
        if (ts.dirty() && tick - lastSave >= 20L * Tuning.get("trail.saveS")) {   // structural: 20 ticks a second
            lastSave = tick;
            String r = files.writeJson(Trails.FILE, ts.save());
            if (r.startsWith("ok")) ts.written();
            else LOG.warn("[entropybot] trail: couldn't write {}: {}", Trails.FILE, r);
        }
    }

    static boolean probe() { return Tuning.get("debug.trailProbe") > 0; }

    /** "recorded N, chosen N, walked N, breaks N | OPEN, N segments". */
    static String counts(Trail t) {
        return "segments recorded " + t.recorded + ", trail chosen " + t.chosen + ", segments walked " + t.walked + ", breaks " + t.breaks
                + " | " + t.state() + ", " + t.segs().size() + " segments" + (t.broken() == null ? "" : ", " + t.broken());
    }

    /** D11 build 2: a route the fork saved while a walk segment was walked marks that segment's fork route. */
    private static void noteForkRoute(Trail t) {
        var b = Jobs.baritone();
        if (b == null) return;
        long[] r = ProbeLines.routeCounts(b.getPricedPath());
        if (r == null) {
            if (!forkSaidMissing) { forkSaidMissing = true; LOG.info("[entropybot] trail: the fork's saved routes are not readable in this fork jar; segments are recorded by the mod alone"); }
            return;
        }
        long saved = r[4];                                  // structural: {asked, reused, changed, not loaded, saved}
        if (forkSaved >= 0 && saved > forkSaved) t.forkSaved();
        forkSaved = saved;
    }

    /**
     * One level up in the chain's seconds, from Baritone's own costs: a pillar level (JUMP_ONE_BLOCK_COST + the walk lease's
     * blockPlacementPenalty, walk.placePenalty) and a dug stair level (ReflexGoals.digLevelTicks over the dearest of the
     * cells round the feet as Baritone prices a break: the best hotbar item's mining ticks + blockBreakAdditionalPenalty).
     */
    static Trail.Climb climb(LocalPlayer p) {
        double pillar = TickPrice.walkS(baritone.api.pathing.movement.ActionCosts.JUMP_ONE_BLOCK_COST + Tuning.get("walk.placePenalty"));
        double stair = Double.NaN;
        try {
            var lv = p.level();
            BlockPos f = p.blockPosition();
            double penalty = baritone.api.BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value;
            double dearest = 0;
            for (BlockPos q : new BlockPos[]{f.above(2), f.north().above(), f.south().above(), f.east().above(), f.west().above()}) {
                if (!lv.isLoaded(q)) continue;
                var st = lv.getBlockState(q);
                if (st.getCollisionShape(lv, q).isEmpty() || st.getDestroySpeed(lv, q) < 0) continue;
                double best = GameTimesLive.breakSeconds(p, st, net.minecraft.world.item.ItemStack.EMPTY);
                for (int i = 0; i < net.minecraft.world.entity.player.Inventory.getSelectionSize(); i++) {
                    var it = p.getInventory().getItem(i);
                    if (!it.isEmpty()) best = Math.min(best, GameTimesLive.breakSeconds(p, st, it));
                }
                dearest = Math.max(dearest, best * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS + penalty);
            }
            if (!Double.isInfinite(dearest)) stair = TickPrice.walkS(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.digLevelTicks(dearest));
        } catch (RuntimeException | LinkageError e) {
            LOG.info("[entropybot] trail: the stair level price was not read ({}); the pillar price alone", e.toString());
        }
        int throwaway = 0;
        try {
            throwaway = io.github.mojolowjo.entropybot.aa.AaEscape.throwaway(io.github.mojolowjo.entropybot.gui.Gui.inventory(p));
        } catch (RuntimeException | LinkageError e) {
            LOG.info("[entropybot] trail: the bag's throwaway blocks were not read ({}); no pillar price", e.toString());
        }
        return new Trail.Climb(pillar, stair, throwaway);
    }

    private static boolean routesSaidMissing;

    /**
     * D4 pass two: the trail's return price from the feet, on to {@code target} from the trail's start, added once: the fork's
     * stored route from the start to the target when one exists (IPricedPath.routeList, read by name; not readable: said once,
     * rule 7) or Baritone's estimate from the start. {@code target} null: nothing on (the target is chosen at the start).
     */
    static Trail.Price priced(LocalPlayer p, Trail t, int[] target) {
        Trail.Price pr = t.price(climb(p), Core.INSTANCE.tick());
        if (pr == null || !pr.whole() || target == null || t.start() == null) return pr;
        double ticks = Trail.routeTicks(routeList(), t.start(), target, Tuning.i("trail.routeReach"));
        if (!Double.isNaN(ticks)) return pr.on(TickPrice.walkS(ticks), "the fork's stored route");
        double e = LivePathPrice.INSTANCE.estimateFrom(t.start().arr(), target[0], target[1], target[2]);
        return pr.on(e, Double.isNaN(e) ? "no stored route and no estimate from " + t.start().text() : "Baritone's estimate, no stored route");
    }

    /** The fork's stored routes (IPricedPath.routeList by name); empty: none or not readable (said once). */
    @SuppressWarnings("unchecked")
    static java.util.List<String> routeList() {
        var b = Jobs.baritone();
        if (b == null) return java.util.List.of();
        try {
            Object pp = b.getPricedPath();
            Object o = pp.getClass().getMethod("routeList").invoke(pp);
            if (o instanceof java.util.List<?> l) return (java.util.List<String>) l;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (!routesSaidMissing) { routesSaidMissing = true; LOG.info("[entropybot] trail: the fork's stored routes (routeList) are not readable in this fork jar ({}); the way on is Baritone's estimate", e.toString()); }
        }
        return java.util.List.of();
    }

    /**
     * D4: the walk back by the trail, one Baritone goal at a time (GoalGetToBlock, its own path, rule 2), newest first. One that
     * ends short breaks the trail there with its reason (rule 7). Shared by the deposit and the walk up to the surface.
     */
    static final class Walker {
        enum State { RUNNING, DONE, BROKEN }
        record Tick(State state, String line) {}

        private final String who;
        private final java.util.List<Trail.Goal> goals;
        private int idx;
        private boolean walking;

        Walker(String who, Trail t) {
            this.who = who;
            this.goals = t.way(Tuning.i("trail.climbLegUp"));
            t.chosen++;
        }

        int size() { return goals.size(); }

        void cancel() {
            var b = Jobs.baritone();
            if (walking && b != null) Jobs.cancel(b);
            walking = false;
        }

        Tick tick(LocalPlayer p) {
            Trail t = here();
            baritone.api.IBaritone b = Jobs.baritone();
            if (t == null || b == null || t.state() != Trail.State.OPEN || idx >= goals.size()) {
                cancel();
                if (t != null && t.state() == Trail.State.OPEN) {
                    String line = t.clear("back at its start by the walk back");
                    if (line != null) LOG.info("[entropybot] {}", line);
                }
                return new Tick(State.DONE, who + ": back at the trail's start");
            }
            var g = goals.get(idx);
            BlockPos at = new BlockPos(g.at().x(), g.at().y(), g.at().z());
            if (LivePathPrice.goal(at).isInGoal(p.blockPosition())) {
                cancel();
                idx++;
                t.walked++;
                if (probe()) LOG.info("[entropybot] probe trail: walked to {} ({} of {}) | {}", g.at().text(), idx, goals.size(), counts(t));
                return new Tick(State.RUNNING, who + ": by the trail, at " + g.at().text());
            }
            if (!walking) {
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, who);
                LivePathPrice.walk(b, at);
                walking = true;
                return new Tick(State.RUNNING, who + ": by the trail to " + g.at().text() + " (" + (idx + 1) + " of " + goals.size() + ")");
            }
            if (!b.getCustomGoalProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
                walking = false;
                return new Tick(State.BROKEN, t.breakAt(g, "Baritone's walk to " + g.at().text() + " ended at " + Jobs.fmt(Jobs.here(p)) + " short of it (no path, or a changed cell)"));
            }
            return new Tick(State.RUNNING, who + ": by the trail to " + g.at().text() + " - walking (" + Jobs.fmt(Jobs.here(p)) + ")");
        }
    }
}
