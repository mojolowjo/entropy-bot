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
    static long calcFails() { return (long) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.calcFails"); }

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
        reflexContext();
        goal = g;
        this.what = what;
        calcAtStart = engine.calcFails();
        push(g);
    }

    // ---- reflex probe 0.39.s5 / review 0.39.s6: the reflex leg's Baritone context ----
    private io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent savedIntent;
    private String savedIntentBy;
    private Boolean savedBreak, savedReflexPlace, savedUnder, savedLava;
    private boolean setBreak, setReflexPlace, setLava;
    private Double savedLavaCost;
    private double setLavaCost;
    static final String REFLEX_INTENT_BY = "reflex";

    /** 0.39.s6 review: a reflex leg owns Baritone's breaking/placing (ReflexOwners: per car, stale when dropped). */
    public static boolean reflexOwns() { return ReflexOwners.any(System.currentTimeMillis()); }

    private void beat() { if (savedBreak != null) ReflexOwners.beat(this, System.currentTimeMillis()); }
    /**
     * Reflex probe 0.39.s5 (the cause): a reflex fired while no job ran inherited the idle walking-only context
     * (allowBreak / allowPlace off), so a bot shut in rock, glass or a closed tank had no path and every burrow dig was
     * refused. A reflex leg now runs like a walk: the TRAVEL intent, so the break policy prices every break and place
     * (outside the areas the travel costs, inside its areas anything; the click veto asks the same policy), Baritone's
     * allowBreak / allowPlace as the travel rule set allows them, the bag's pillar blocks as throwaway, and the fork's
     * {@code pathUnderWater} (a swim under the surface); in lava the fork's lava wade. Baritone picks the cells;
     * everything goes back when the leg ends.
     */
    private void reflexContext() {
        // 0.39.s7 (staged: the danger desire's burrow, the night answer, was refused on grass the same way): AA's danger
        // car runs its moves under the same travel rule set as the reflex car
        var pr = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
        try {
            savedIntent = pr.intent();
            savedIntentBy = pr.intentBy();
            pr.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, REFLEX_INTENT_BY);
            var s = BaritoneAPI.getSettings();
            savedBreak = s.allowBreak.value;
            savedReflexPlace = s.allowPlace.value;
            setBreak = pr.travelMayBreak();
            setReflexPlace = pr.travelMayPlace();
            s.allowBreak.value = setBreak;
            s.allowPlace.value = setReflexPlace;
            beat();
            if (savedThrow == null) {
                savedThrow = new ArrayList<>(s.acceptableThrowawayItems.value);
                List<Item> list = new ArrayList<>(savedThrow);
                for (Item i : bagBlocks()) if (!list.contains(i)) list.add(i);
                s.acceptableThrowawayItems.value = list;
                setThrow = list;
            }
            try {
                savedUnder = s.pathUnderWater.value;
                s.pathUnderWater.value = true;
            } catch (LinkageError e) {
                savedUnder = null;
                LOG.warn("[entropybot] moves: this Baritone has no pathUnderWater (the stock jar?) - reflexes swim the surface only: {}", e.toString());
            }
            var p = Minecraft.getInstance().player;
            if (p != null && p.isInLava()) lavaWade();
            if (!setBreak || !setReflexPlace)
                LOG.info("[entropybot] moves: reflex leg under the travel rule set: break {}, place {} (the rule set says so)", setBreak, setReflexPlace);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] moves: reflex context not set ({}): {}", what, e.toString());
        }
    }

    /** The lava wade on for this leg (the fork's allowBreakNextToLava, lavaCellCost priced by LavaPrice), logged. */
    private void lavaWade() {
        var s = BaritoneAPI.getSettings();
        try {
            if (savedLava == null) savedLava = s.allowBreakNextToLava.value;
            setLava = true;
            s.allowBreakNextToLava.value = true;
        } catch (LinkageError e) {
            LOG.warn("[entropybot] moves: this Baritone has no allowBreakNextToLava (the stock jar?) - no dig beside lava: {}", e.toString());
        }
        try {
            if (savedLavaCost == null) savedLavaCost = s.lavaCellCost.value;
            var p = Minecraft.getInstance().player;
            setLavaCost = p == null ? 0 : LavaPrice.cellTicks(p.getHealth(), p.getMaxHealth());
            s.lavaCellCost.value = setLavaCost;
            LOG.info("[entropybot] moves: {}: in lava - the lava wade on ({} ticks a cell, breaks beside lava allowed)", what, Math.round(setLavaCost));
        } catch (LinkageError e) {
            LOG.warn("[entropybot] moves: this Baritone has no lavaCellCost (the stock jar?) - lava stays a wall: {}", e.toString());
        }
    }

    /** 0.39.s6 review: out of the lava, the wade and the dig beside lava go off again (stock lava rules). */
    private void lavaWadeOff() {
        try {
            var s = BaritoneAPI.getSettings();
            if (s.lavaCellCost.value == setLavaCost) s.lavaCellCost.value = savedLavaCost == null ? 0.0 : savedLavaCost;
            if (s.allowBreakNextToLava.value == setLava) s.allowBreakNextToLava.value = savedLava != null && savedLava;
            LOG.info("[entropybot] moves: {}: out of the lava - the lava wade off", what);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] moves: lava wade not put back: {}", e.toString());
        }
        setLava = false;
        setLavaCost = 0;
        savedLava = null;
        savedLavaCost = null;
    }

    private void restoreReflexContext() {
        if (savedBreak == null && savedIntent == null) return;
        ReflexOwners.release(this);
        try {
            var s = BaritoneAPI.getSettings();
            // only what is still the value this car set (an owner change during the leg wins)
            if (savedBreak != null && s.allowBreak.value == setBreak) s.allowBreak.value = savedBreak;
            if (savedReflexPlace != null && s.allowPlace.value == setReflexPlace) s.allowPlace.value = savedReflexPlace;
            try {
                if (savedUnder != null && Boolean.TRUE.equals(s.pathUnderWater.value)) s.pathUnderWater.value = savedUnder;
            } catch (LinkageError e) { io.github.mojolowjo.entropybot.Quiet.warn("car: pathUnderWater could not be put back (an older Baritone)", e); }
            if (savedLava != null || savedLavaCost != null) lavaWadeOff();
            // 0.39.s6 review: the intent goes back only if it is still ours (a job that set its own during the leg keeps it)
            var pr = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE;
            if (savedIntent != null && pr.intent() == io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL
                    && REFLEX_INTENT_BY.equals(pr.intentBy())) pr.setIntent(savedIntent, savedIntentBy);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] moves: reflex context restore failed: {}", e.toString());
        }
        savedBreak = savedReflexPlace = savedUnder = null;
        savedIntent = null;
        savedIntentBy = null;
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

    /** The client level as the reflex goals read it (feet / head open, water in a cell). */
    private static final ReflexGoals.Cells CELLS = new ReflexGoals.Cells() {
        @Override public boolean open(int x, int y, int z) {
            var lv = Minecraft.getInstance().level;
            if (lv == null) return false;
            BlockPos q = new BlockPos(x, y, z);
            return lv.getBlockState(q).getCollisionShape(lv, q).isEmpty();
        }

        @Override public boolean water(int x, int y, int z) {
            var lv = Minecraft.getInstance().level;
            return lv != null && lv.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
        }
    };

    @Override public void breathe() {
        var p = Minecraft.getInstance().player;
        if (p == null) { fresh(); forced = Status.failed("no player"); return; }
        int upTo = p.getBlockY() + (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("reflex.air.pullUp");
        drive(new ReflexGoals.Breathe(CELLS, upTo), "swim up to air");
        try {
            // the swim-up gap is the fork's: Baritone holds jump while it has no path yet (head under water)
            var s = BaritoneAPI.getSettings();
            // 0.39.s3 review: saved only when nothing is saved yet, so a second breathe never saves our own "true"
            if (savedSwim == null) savedSwim = s.swimUpWithoutPath.value;
            s.swimUpWithoutPath.value = true;
            LOG.info("[entropybot] moves: swim-up gap on (Baritone holds jump until it has a path; was {})", savedSwim);
        } catch (RuntimeException | LinkageError e) {
            savedSwim = null;
            LOG.warn("[entropybot] moves: this Baritone has no swimUpWithoutPath (the stock jar?) - no jump in the swim-up gap: {}", e.toString());
        }
    }

    @Override public void toWater() {
        drive(new ReflexGoals.Water(CELLS), "to water");
    }

    @Override public void burrowHere(int depth, boolean up, int timeoutTicks) {
        fresh();
        // a danger move ends the job's hands and holds the job; a reflex burrow (the fork runs it at priority 10) outranks the job's processes
        // without either, so the job resumes after it (step 4's save/restore)
        if (!reflex) {
            JobHands.get().preempt("danger move burrow");
            engine.hold();
            reflexContext();         // 0.39.s7: the danger burrow digs under the travel rule set too
        } else {
            engine.endReflex();      // a reflex goal before it (the run) would sit at 10 over the burrow
            reflexContext();         // reflex probe 0.39.s5: the burrow's digs under the travel rule set, not walking-only
        }
        try {
            var s = BaritoneAPI.getSettings();
            if (savedThrow == null) {
                savedThrow = new ArrayList<>(s.acceptableThrowawayItems.value);
                List<Item> list = new ArrayList<>(savedThrow);
                for (Item i : bagBlocks()) if (!list.contains(i)) list.add(i);
                s.acceptableThrowawayItems.value = list;
                setThrow = list;
            }
            burrow = b().getBurrowProcess();
            burrow.burrowHere(depth, up, timeoutTicks, reflex);     // a reflex car's burrow at priority 10 (the reflex level)
            what = "burrow " + (up ? "up " : "down ") + depth;
        } catch (LinkageError e) {
            forced = Status.failed("this Baritone has no burrowHere (the stock jar? install the entropy fork): " + e);
        } catch (RuntimeException e) {
            forced = Status.failed("burrow start: " + e);
        }
    }

    /** The cells the fork's burrow digs (DangerMoves' guard-verdict line); empty when none. */
    public List<BlockPos> burrowCells() {
        try {
            return burrow == null ? List.of() : burrow.digCells();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] moves: burrow cells unknown: {}", e.toString());
            return List.of();
        }
    }

    private Boolean savedSwim;

    @Override public boolean pathReady() {
        try {
            var pb = b().getPathingBehavior();
            return goal != null && pb.getCurrent() != null && pb.getGoal() == goal;
        } catch (RuntimeException e) {
            // reflex choosers: never silent; "no path yet" here only means the latency line waits
            LOG.warn("[entropybot] moves: path check failed ({}): {}", what, e.toString());
            return false;
        }
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

    @Override public void digInHere(int timeoutTicks) {
        fresh();
        JobHands.get().preempt("danger move dig in");     // step 3 review: a job's hands end with a reason, never silently
        engine.hold();          // the job stays paused; BurrowProcess (-0.25) outranks the hold (-0.5)
        reflexContext();        // 0.39.s7: the dig-in under the travel rule set
        try {
            var s = BaritoneAPI.getSettings();
            if (savedThrow == null) {           // reflexContext set the list already (the bag's blocks)
                savedThrow = new ArrayList<>(s.acceptableThrowawayItems.value);
                List<Item> list = new ArrayList<>(savedThrow);
                for (Item i : bagBlocks()) if (!list.contains(i)) list.add(i);
                s.acceptableThrowawayItems.value = list;
                setThrow = list;
            }
            burrow = b().getBurrowProcess();
            burrow.digInHere(timeoutTicks);          // rm-threat: the fork picks the wall
            what = "dig in";
        } catch (LinkageError e) {
            forced = Status.failed("this Baritone has no digInHere (the stock jar? install the entropy fork): " + e);
        } catch (RuntimeException e) {
            forced = Status.failed("dig in start: " + e);
        }
    }

    /**
     * rm-threat: the fork's "can I burrow {@code depth} down here" (null = possible, else its stage and reason). Asked
     * before any run, so the bag's blocks are not on the throwaway list yet (the seal's own block comes from the dig).
     */
    public String burrowVerdict(int depth) {
        try {
            return b().getBurrowProcess().canBurrowHere(depth);
        } catch (LinkageError e) {
            return "this Baritone has no canBurrowHere (the stock jar? install the entropy fork)";
        } catch (RuntimeException e) {
            return "burrow check: " + e;
        }
    }

    /** rm-threat: the fork's "can I dig into a wall here" (null = possible, else every wall's reason). */
    public String digInVerdict() {
        try {
            return b().getBurrowProcess().canDigInHere();
        } catch (LinkageError e) {
            return "this Baritone has no canDigInHere (the stock jar? install the entropy fork)";
        } catch (RuntimeException e) {
            return "dig in check: " + e;
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
            if (savedBreak != null) beat();      // 0.39.s7: a burrow leg beats too (its status returns before the goal part)
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
            // reflex probe 0.39.s5: "no path" = calcFails failed ticks with no path running; a replan that fails while a
            // path is still being walked (a dug cell changed the world) is not the leg's end
            if (reflex && pathReady()) calcAtStart = engine.calcFails();
            // staged 0.39.s5: a leg that started a tick before the bot fell into the lava gets the lava wade once it is in
            if (savedBreak != null) {
                beat();
                boolean inLava = ctx.player().isInLava();
                if (setLavaCost == 0 && inLava) lavaWade();
                else if (setLavaCost > 0 && !inLava) lavaWadeOff();      // 0.39.s6 review: out of it = stock lava rules
            }
            if (engine.calcFails() - calcAtStart >= calcFails()) return Status.failed("Baritone found no path (" + what + ")");
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
            if (savedSwim != null && Boolean.TRUE.equals(s.swimUpWithoutPath.value)) s.swimUpWithoutPath.value = savedSwim;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] moves: settings restore failed: {}", e.toString());
        }
        savedPlace = null;
        savedSwim = null;
        savedThrow = null;
        setThrow = null;
        restoreReflexContext();
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
