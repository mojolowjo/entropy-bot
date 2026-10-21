package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBurrowProcess;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Backbone step 3: {@link JobHands.Hand} on the fork's {@code IBurrowProcess.startJob} (dig cells, then right-clicks
 * on a face with an item), the look on {@code LookBehavior.updateTarget}, the failure's guard line from
 * {@code GuardCore.checkUnlogged} on the cell.
 *
 * <p>Loader notes: Baritone API (BaritoneAPI.getProvider().getPrimaryBaritone()); {@code startJob} is only in our
 * fork's jar: a stock Baritone throws LinkageError, caught: FAILED "this Baritone has no job hands". Vanilla
 * registries (BuiltInRegistries.ITEM, ResourceLocation.tryParse), the same on Fabric. Client thread only. Never throws.
 */
final class BaritoneHand implements JobHands.Hand {
    private static final Logger LOG = LogUtils.getLogger();

    private IBurrowProcess proc;
    private Car.Status forced;

    private static IBaritone b() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) {
        proc = null;
        forced = null;
        try {
            List<BlockPos> d = new ArrayList<>();
            for (int[] c : dig) d.add(pos(c));
            List<IBurrowProcess.Click> k = new ArrayList<>();
            for (JobHands.Click c : clicks) {
                Direction f = Direction.byName(c.face());
                if (f == null) { forced = Car.Status.failed("no face called " + c.face()); return; }
                Item it = null;
                if (c.item() != null) {
                    ResourceLocation rl = ResourceLocation.tryParse(c.item());
                    it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                    if (it == Items.AIR) { forced = Car.Status.failed("no item called " + c.item()); return; }
                }
                k.add(new IBurrowProcess.Click(pos(c.on()), f, it));
            }
            proc = b().getBurrowProcess();
            proc.startJob(d, k, timeoutTicks);
        } catch (LinkageError e) {
            forced = Car.Status.failed("this Baritone has no job hands (the stock jar? install the entropy fork): " + e);
        } catch (RuntimeException e) {
            forced = Car.Status.failed("start: " + e);
        }
    }

    @Override public Car.Status status() {
        if (forced != null) return forced;
        if (proc == null) return Car.Status.failed("nothing started");
        try {
            return switch (proc.status()) {
                case DONE -> Car.Status.done(proc.why());
                case FAILED -> Car.Status.failed(proc.why());
                case IDLE -> Car.Status.failed("never started");
                default -> Car.Status.running(proc.why());
            };
        } catch (RuntimeException | LinkageError e) {
            return Car.Status.failed("status: " + e);
        }
    }

    @Override public void cancel(String why) {
        try {
            if (proc != null && proc.status() == IBurrowProcess.Status.RUNNING) proc.cancel(why);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] job hands: cancel failed: {}", e.toString());
        }
        proc = null;
        forced = null;
    }

    @Override public String verdict(String action, int[] cell) {
        try {
            var lv = Minecraft.getInstance().level;
            if (lv == null || cell == null) return "";
            BlockPos q = pos(cell);
            var v = Guard.INSTANCE.core.checkUnlogged(Guard.dimOf(lv), q.getX(), q.getY(), q.getZ(), action, Guard.INSTANCE.infoFor(lv, q));
            String block = BuiltInRegistries.BLOCK.getKey(lv.getBlockState(q).getBlock()).getPath();
            return "; guard (" + action + ") on " + block + " at " + q.getX() + " " + q.getY() + " " + q.getZ() + ": "
                    + (v.allowed() ? "allowed" : "the guard refused: " + v.reason());
        } catch (RuntimeException e) {
            return "; guard verdict unknown (" + e + ")";
        }
    }

    @Override public void look(double x, double y, double z) {
        try {
            var ctx = b().getPlayerContext();
            if (ctx.player() == null) return;
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(x, y, z), ctx.playerRotations());
            b().getLookBehavior().updateTarget(r, true);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] job hands: look failed: {}", e.toString());
        }
    }

    private static BlockPos pos(int[] c) {
        return new BlockPos(c[0], c[1], c[2]);
    }
}
