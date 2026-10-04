package io.github.mojolowjo.entropybot.commands;

import java.util.List;
import java.util.Map;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.clear.Bot;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.FloorFill;
import io.github.mojolowjo.entropybot.clear.JunkDrop;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * B7e F (2026-10-04): the game side of {@code dig ... floor [block]} ({@link FloorFill}) and {@code dig ... junk drop}
 * ({@link JunkDrop}). A floor dig's job is [climb back onto the walkway] [restock] [clear] [fill]; when the clear left
 * blocks it couldn't reach and the fill bridged something, another [clear] [fill] round follows (at most
 * {@link #MAX_ROUNDS}), so the face over a cave is dug from the new floor. The last fill ends the job with the clear's
 * report plus the fill's. Both are "clear" steps with their own kind, so Seq hands them to {@link Clearing#step}.
 */
final class FloorSteps {
    private static final Logger LOG = LogUtils.getLogger();

    private FloorSteps() {}

    static final String KIND_FLOOR = "floor", KIND_JUNK = "junk";
    static final int MAX_ROUNDS = 4;

    /** A fill step's state. */
    static final class FloorState {
        final ClearJob.Options opts;
        final boolean climbOnly;
        /** the clear step this fill follows (its Outcome), null for the climb before the clear */
        Seq.Step clearStep;
        int round = 1, brokenBefore;
        FloorFill.Run prev, run;
        LeaseSet leases;
        final Clearing.LiveWorld world = new Clearing.LiveWorld();
        long lastBeat = -1000;
        boolean done;

        FloorState(ClearJob.Options opts, boolean climbOnly) {
            this.opts = opts;
            this.climbOnly = climbOnly;
        }
    }

    /** The fill after {@code clearStep} (climbOnly: just get back up onto the walkway, before the clear). */
    static Seq.Step floorStep(ClearJob.Options opts, Seq.Step clearStep, boolean climbOnly) {
        Seq.Step s = new Seq.Step("clear");
        s.kind = KIND_FLOOR;
        s.clear = opts;
        FloorState fs = new FloorState(opts, climbOnly);
        fs.clearStep = clearStep;
        s.state = fs;
        return s;
    }

    /** Why a named block may not become the floor (by id, then the registry: a full, non-falling block without a block entity), or null. */
    static String floorBlockProblem(String id) {
        String bad = FloorFill.namedProblem(id);
        if (bad != null) return bad;
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) return "it's not a block";
            net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (!(it instanceof net.minecraft.world.item.BlockItem bi)) return "it's not a block";
            net.minecraft.world.level.block.state.BlockState s = bi.getBlock().defaultBlockState();
            if (s.hasBlockEntity()) return "it's a container or machine";
            if (bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return "it falls";
            if (!s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return "it's not a full block";
            return null;
        } catch (RuntimeException e) {
            return "it's not a block I know";
        }
    }

    /** The job ended (any way): the fill's place leases go. */
    static void ended(FloorState fs) {
        fs.done = true;
        if (fs.leases != null) fs.leases.releaseAll();
    }

    static String floorRun(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof FloorState fs)) return "the floor fill was not set up";
        if (fs.done) return "next";
        Commands c = seq.jobs.core().commands;
        if (!fs.climbOnly && fs.run == null) {
            // a clear that didn't end ok (stopped, stuck, the guard): no fill and no more rounds, its report ends the job
            Clearing.Outcome o = fs.clearStep != null ? fs.clearStep.cleared : null;
            if (o == null || !o.ok()) {
                fs.done = true;
                String m = o != null ? o.message() : "stopped: the clear left no report (while " + seq.label + ")";
                if (fs.prev != null) m = FloorFill.endMessage(m, fs.brokenBefore + (o != null ? o.broken() : 0), fs.prev.report());
                LOG.info("[entropybot] floor: the clear didn't end ok, no fill: {}", m);
                seq.jobs.finish(m);
                return "wait";
            }
        }
        fs.world.set(p);
        if (fs.leases == null) fs.leases = Clearing.newLeases();
        if (fs.run == null) {
            Jobs.safeSettings();                // Baritone only walks; the fill's clicks are its own
            // the floor layer and the cave steps lie inside the areas in every guard mode (log mode included)
            fs.run = new FloorFill.Run(fs.world, fs.opts.box, fs.opts.floorBlock, Clearing::floorCellAllowed, Clearing.standCheck(c),
                    fs.climbOnly).carry(fs.prev);
        }
        long now = seq.now();
        if (now - fs.lastBeat >= 20) {
            fs.lastBeat = now;
            fs.leases.beat();
            fs.leases.ensure();                 // a lease that died during a hold: the next placement takes its cell again
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || Gui.open(p)) Gui.close(p);
        if (!fs.run.tick(new Body(seq, fs, p))) return "wait";
        ended(fs);
        IBaritone b = Jobs.baritone();
        if (b != null && !Jobs.idle(b)) Jobs.cancel(b);
        if (fs.climbOnly) {
            LOG.info("[entropybot] floor: before the clear - {}", fs.run.report());
            return "next";
        }
        Clearing.Outcome o = fs.clearStep != null ? fs.clearStep.cleared : null;
        int broken = fs.brokenBefore + (o != null ? o.broken() : 0);
        if (o != null && o.left() > 0 && fs.run.filled() > 0 && fs.round < MAX_ROUNDS) {
            // the new floor reaches what the clear couldn't: once more from it
            Seq.Step again = Clearing.clearStep(fs.opts);
            Seq.Step fill = floorStep(fs.opts, again, false);
            FloorState n = (FloorState) fill.state;
            n.round = fs.round + 1;
            n.brokenBefore = broken;
            n.prev = fs.run;
            seq.splice(seq.idx + 1, List.of(again, fill));
            LOG.info("[entropybot] floor: filled {} cells, {} blocks left in the box - clearing again from the new floor (round {})",
                    fs.run.filled(), o.left(), n.round);
            return "next";
        }
        String msg = FloorFill.endMessage(o != null ? o.message() : "ok: done " + seq.label, broken, fs.run.report());
        LOG.info("[entropybot] floor: {}", msg);
        seq.jobs.finish(msg);
        return "wait";
    }

    /** {@link FloorFill.Run.Body} over the client: Clearing.placeAt with the fill's leases, Baritone walks. */
    static final class Body implements FloorFill.Run.Body {
        final Seq seq;
        final FloorState fs;
        final LocalPlayer p;

        Body(Seq seq, FloorState fs, LocalPlayer p) {
            this.seq = seq;
            this.fs = fs;
            this.p = p;
        }

        @Override public Bot bot() { return McClearWorld.botOf(p); }

        @Override public long now() { return seq.now(); }

        @Override public Map<String, Integer> inventory() { return Gui.inventory(p); }

        @Override public String place(Pos cell, String id) { return Clearing.placeAt(p, id, cell.x(), cell.y(), cell.z(), fs.leases); }

        @Override public void walkBlock(int x, int y, int z) {
            IBaritone b = Jobs.baritone();
            if (b == null) return;
            String fence = seq.jobs.goalAllowed(x, y, z);
            if (fence != null) {
                LOG.info("[entropybot] floor: not walking to {} {} {}: {}", x, y, z, fence);
                return;
            }
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(x, y, z)));
        }

        @Override public boolean walkIdle() {
            IBaritone b = Jobs.baritone();
            return b == null || Jobs.idle(b);
        }

        @Override public void cancelWalk() {
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
        }

        @Override public void status(String s) { seq.setStatus(seq.label + " - " + s); }

        @Override public void log(String s) { LOG.info("[entropybot] floor: {}", s); }
    }

    // ---- junk drop ----

    static final class JunkState {
        ClearJob job;
        double[] start;
        Map<String, Integer> plan;
        long t0;
    }

    /** The "trip" a junk-drop clear splices in when the bag is full: turn toward the dug side, throw the junk. */
    static Seq.Step junkStep(ClearJob job, double[] start) {
        Seq.Step s = new Seq.Step("clear");
        s.kind = KIND_JUNK;
        JunkState js = new JunkState();
        js.job = job;
        js.start = start;
        s.state = js;
        return s;
    }

    static String junkRun(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof JunkState js)) return "the junk drop was not set up";
        long now = seq.now();
        Minecraft mc = Minecraft.getInstance();
        if (js.plan == null) {
            if (mc.screen != null || Gui.open(p)) Gui.close(p);
            Map<String, Integer> inv = Gui.inventory(p);
            Map<String, Integer> dep = StorageRules.depositables(Storage.held(p), "", false, null, seq.storage.keeps());
            String keep = js.job.floor ? FloorFill.chooseBlock(js.job.floorBlock, inv) : null;
            js.plan = JunkDrop.plan(dep, inv, keep, keep != null ? JunkDrop.FLOOR_KEEP : 0);
            if (js.plan.isEmpty()) return "no plain junk blocks to throw away";
            // the rotation reaches the server with the next movement packets, before the throw clicks
            float yaw = JunkDrop.throwYaw(js.start, p.getX(), p.getZ(), js.job.box);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(-10f);
            js.t0 = now;
            seq.setStatus(seq.label + " - throwing junk blocks away");
            return "wait";
        }
        if (now - js.t0 < 4) return "wait";
        if (mc.screen != null || Gui.open(p)) Gui.close(p);
        for (Map.Entry<String, Integer> e : js.plan.entrySet()) {
            String r = GuiCore.drop(new McMenu(p), e.getKey() + " " + e.getValue());
            if (!r.startsWith("ok")) LOG.info("[entropybot] clear: junk drop {}: {}", e.getKey(), r);
        }
        JunkDrop.noteThrow(js.job, p.getX(), p.getY(), p.getZ(), now);       // those drops are not picked up again
        LOG.info("[entropybot] clear: bag full - {} (junk drop)", JunkDrop.summary(js.plan));
        return "next";
    }
}
