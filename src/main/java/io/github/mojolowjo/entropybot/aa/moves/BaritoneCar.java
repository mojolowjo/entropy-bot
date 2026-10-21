package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.IBurrowProcess;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.engine.EngineProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Backbone step 2: the {@link Car} on Baritone. Goals go through the mod's {@link EngineProcess} override (its one
 * Baritone process, so the job's goal is kept under it); the burrow and the dig-in through the fork's
 * {@link IBurrowProcess} (priority over the engine's hold); the look through {@code LookBehavior.updateTarget}. The
 * pillar turns {@code allowPlace} on and adds the bag's pillar blocks (planks included) to
 * {@code acceptableThrowawayItems} for its run, and puts both back on stop.
 *
 * <p>Loader notes: Baritone API only (BaritoneAPI.getProvider().getPrimaryBaritone(), goals, settings); the fork's
 * {@code IBaritone.getBurrowProcess()} is missing from the stock jar: that is caught (LinkageError) and the burrow
 * fails with "this Baritone has no BurrowProcess". Client thread only. Never throws.
 */
public final class BaritoneCar implements Car {
    private static final Logger LOG = LogUtils.getLogger();
    /** Ticks of failed path calculations before a goal leg fails (Baritone retries a few). */
    static final long CALC_FAILS = 20;

    private final EngineProcess engine;
    private Goal goal;
    private String what = "";
    private long calcAtStart;
    private IBurrowProcess burrow;
    private Status forced;
    private Boolean savedPlace;
    private List<Item> savedThrow, setThrow;

    /** Backbone step 4: a reflex car's goals run at {@link EngineProcess#REFLEX_PRIORITY} (above every process). */
    private final boolean reflex;

    public BaritoneCar(EngineProcess engine) {
        this(engine, false);
    }

    public BaritoneCar(EngineProcess engine, boolean reflex) {
        this.engine = engine;
        this.reflex = reflex;
    }

    private void push(Goal g) {
        if (reflex) engine.overrideReflex(g);
        else engine.override(g);
    }

    private static IBaritone b() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void fresh() {
        restore();
        goal = null;
        burrow = null;
        forced = null;
    }

    private void drive(Goal g, String what) {
        fresh();
        goal = g;
        this.what = what;
        calcAtStart = engine.calcFails();
        push(g);
    }

    @Override public void runAway(int dist, List<int[]> from, double[] prefer) {
        if (prefer == null) { runAway(dist, from); return; }
        BlockPos[] at = new BlockPos[from.size()];
        for (int i = 0; i < at.length; i++) at[i] = pos(from.get(i));
        if (at.length == 0) { fresh(); forced = Status.failed("nothing to run from"); return; }
        var p = Minecraft.getInstance().player;
        if (p == null) { fresh(); forced = Status.failed("no player"); return; }
        drive(new PreferredRunAway(dist, prefer, p.getBlockX(), p.getBlockZ(), at), "run " + dist + " away, heading yaw "
                + Math.round(Math.toDegrees(Math.atan2(-prefer[0], prefer[1]))));
    }

    @Override public void reach(int[] cell) {
        drive(new GoalBlock(pos(cell)), "reach " + cell[0] + " " + cell[1] + " " + cell[2]);
    }

    @Override public boolean pathReady() {
        try {
            var pb = b().getPathingBehavior();
            return goal != null && pb.getCurrent() != null && pb.getGoal() == goal;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public void cover(int[] cell) {
        drive(new GoalGetToBlock(pos(cell)), "cover " + cell[0] + " " + cell[1] + " " + cell[2]);
    }

    @Override public void runAway(int dist, List<int[]> from) {
        BlockPos[] at = new BlockPos[from.size()];
        for (int i = 0; i < at.length; i++) at[i] = pos(from.get(i));
        if (at.length == 0) { fresh(); forced = Status.failed("nothing to run from"); return; }
        drive(new GoalRunAway(dist, at), "run " + dist + " away");
    }

    @Override public void pillar(int[] top) {
        fresh();
        try {
            var s = BaritoneAPI.getSettings();
            savedPlace = s.allowPlace.value;
            savedThrow = new ArrayList<>(s.acceptableThrowawayItems.value);
            s.allowPlace.value = true;
            List<Item> list = new ArrayList<>(savedThrow);
            for (Item i : bagBlocks()) if (!list.contains(i)) list.add(i);
            s.acceptableThrowawayItems.value = list;
            setThrow = list;
        } catch (RuntimeException e) {
            forced = Status.failed("pillar settings: " + e);
            return;
        }
        goal = new GoalBlock(pos(top));
        what = "pillar to " + top[0] + " " + top[1] + " " + top[2];
        calcAtStart = engine.calcFails();
        push(goal);
    }

    @Override public void burrow(List<int[]> dig, int[] stand, List<int[]> seal, int timeoutTicks) {
        fresh();
        JobHands.get().preempt("danger move burrow");     // step 3 review: a job's hands end with a reason, never silently
        engine.hold();          // the job stays paused; BurrowProcess (-0.25) outranks the hold (-0.5)
        try {
            var s = BaritoneAPI.getSettings();
            savedThrow = new ArrayList<>(s.acceptableThrowawayItems.value);
            List<Item> list = new ArrayList<>(savedThrow);
            for (Item i : bagBlocks()) if (!list.contains(i)) list.add(i);
            s.acceptableThrowawayItems.value = list;
            setThrow = list;
            List<BlockPos> d = new ArrayList<>(), sl = new ArrayList<>();
            for (int[] c : dig) d.add(pos(c));
            for (int[] c : seal) sl.add(pos(c));
            burrow = b().getBurrowProcess();
            burrow.start(d, stand == null ? null : pos(stand), sl, timeoutTicks);
            what = "burrow";
        } catch (LinkageError e) {
            forced = Status.failed("this Baritone has no BurrowProcess (the stock jar? install the entropy fork): " + e);
        } catch (RuntimeException e) {
            forced = Status.failed("burrow start: " + e);
        }
    }

    @Override public void look(double x, double y, double z) {
        try {
            var ctx = b().getPlayerContext();
            if (ctx.player() == null) return;
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(x, y, z), ctx.playerRotations());
            b().getLookBehavior().updateTarget(r, false);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] moves: look failed: {}", e.toString());
        }
    }

    @Override public Status status() {
        Status s = status0();
        if (s.state() != State.RUNNING) restore();      // review: the settings go back as soon as the leg ends, not after the hold
        return s;
    }

    private Status status0() {
        try {
            if (forced != null) return forced;
            if (burrow != null) {
                return switch (burrow.status()) {
                    case DONE -> Status.done(burrow.why());
                    case FAILED -> Status.failed(burrow.why());
                    case IDLE -> Status.failed("the burrow never started");
                    default -> Status.running(burrow.why());
                };
            }
            if (goal == null) return Status.failed("no command");
            var ctx = b().getPlayerContext();
            if (ctx.player() == null) return Status.failed("no player");
            if (goal.isInGoal(ctx.playerFeet())) return Status.done(what);
            if (engine.calcFails() - calcAtStart >= CALC_FAILS) return Status.failed("Baritone found no path (" + what + ")");
            if (engine.mode() != EngineProcess.Mode.OVERRIDE || reflex != engine.reflexGoal()) push(goal);     // a cancel dropped it
            return Status.running(what);
        } catch (RuntimeException e) {
            return Status.failed("status: " + e);
        }
    }

    @Override public void stop(String why) {
        try {
            if (burrow != null && burrow.status() == IBurrowProcess.Status.RUNNING) burrow.cancel(why);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] moves: burrow cancel failed: {}", e.toString());
        }
        fresh();
        // 0.39.m2: a reflex car gives back what its goal replaced (the job's goal / hold), never a hold of its own
        if (reflex) engine.endReflex();
        else engine.hold();
    }

    @Override public void release() {
        stop("released");
        if (!reflex) engine.release();
    }

    private void restore() {
        try {
            var s = BaritoneAPI.getSettings();
            // review: only what is still the value this car set (an owner change during the run wins)
            if (savedPlace != null && Boolean.TRUE.equals(s.allowPlace.value)) s.allowPlace.value = savedPlace;
            if (savedThrow != null && s.acceptableThrowawayItems.value == setThrow) s.acceptableThrowawayItems.value = savedThrow;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] moves: settings restore failed: {}", e.toString());
        }
        savedPlace = null;
        savedThrow = null;
        setThrow = null;
    }

    /** The bag's blocks the danger ways may place (AaEscape.pillarBlock: throwaway and planks). */
    private static List<Item> bagBlocks() {
        List<Item> out = new ArrayList<>();
        var p = Minecraft.getInstance().player;
        if (p == null) return out;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(id) && !out.contains(s.getItem())) out.add(s.getItem());
        }
        return out;
    }

    private static BlockPos pos(int[] c) {
        return new BlockPos(c[0], c[1], c[2]);
    }
}
