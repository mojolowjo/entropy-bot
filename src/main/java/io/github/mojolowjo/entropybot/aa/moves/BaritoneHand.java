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

    private static boolean warned;

    static void warn(String line) { LOG.warn(line); }
    private IBurrowProcess proc;
    private int mine = -1;
    private Car.Status forced;

    private static IBaritone b() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) {
        proc = null;
        builder = null;
        restoreLeg();
        forced = null;
        try {
            List<BlockPos> d = new ArrayList<>();
            for (int[] c : dig) d.add(pos(c));
            List<IBurrowProcess.Click> k = new ArrayList<>();
            for (JobHands.Click c : clicks) {
                Item it = null;
                if (c.item() != null) {
                    ResourceLocation rl = ResourceLocation.tryParse(c.item());
                    it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                    if (it == Items.AIR) { forced = Car.Status.failed("no item called " + c.item()); return; }
                }
                k.add(new IBurrowProcess.Click(pos(c.on()), null, it));      // null face: Baritone picks the face
            }
            proc = b().getBurrowProcess();
            proc.startJob(d, k, timeoutTicks);
            mine = proc.run();
        } catch (LinkageError e) {
            forced = Car.Status.failed("this Baritone has no job hands (an older jar? install the entropy fork from baritone-backbone3): " + e);
            if (!warned) {
                warned = true;
                LOG.warn("[entropybot] job hands: {}", forced.why());
            }
        } catch (RuntimeException e) {
            forced = Car.Status.failed("start: " + e);
        }
    }

    // ---- builder placements (fork: IBuilderProcess.buildLeg) ----
    private baritone.api.process.IBuilderProcess builder;
    private int buildRun = -1;
    /** Builder settings this leg set, with the value before it (put back at the end, only while still ours). */
    private final java.util.Map<baritone.api.Settings.Setting<Object>, Object[]> legSettings = new java.util.LinkedHashMap<>();

    @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks) {
        proc = null;
        builder = null;
        forced = null;
        restoreLeg();
        try {
            var p = Minecraft.getInstance().player;
            if (p == null) { forced = Car.Status.failed("no player"); return; }
            java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> want = new java.util.LinkedHashMap<>();
            java.util.Map<Item, Integer> need = new java.util.LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                ResourceLocation rl = ResourceLocation.tryParse(items.get(i));
                Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                if (!(it instanceof net.minecraft.world.item.BlockItem bi)) {
                    forced = Car.Status.failed("stage plan: " + items.get(i) + " is not a block item (the builder places blocks only)");
                    return;
                }
                want.put(pos(cells.get(i)), bi.getBlock().defaultBlockState());
                need.merge(it, 1, Integer::sum);
            }
            // the builder can't fetch: every item comes from the bag, a missing one fails here with its name
            for (var e : need.entrySet()) {
                int have = p.getInventory().countItem(e.getKey());
                if (have < e.getValue()) {
                    forced = Car.Status.failed("stage items: need " + e.getValue() + " " + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + ", the bag has " + have);
                    return;
                }
            }
            var s = BaritoneAPI.getSettings();
            setForLeg(s.allowInventory, true);
            setForLeg(s.buildInLayers, false);
            setForLeg(s.buildIgnoreExisting, false);
            setForLeg(s.buildRepeat, net.minecraft.core.Vec3i.ZERO);
            setForLeg(s.okIfWater, false);
            builder = b().getBuilderProcess();
            builder.buildLeg("place " + String.join(",", items), want, timeoutTicks);
            buildRun = builder.legRun();
        } catch (LinkageError e) {
            forced = Car.Status.failed("this Baritone has no build legs (an older jar? install the entropy fork from baritone-builder): " + e);
            if (!warned) {
                warned = true;
                LOG.warn("[entropybot] job hands: {}", forced.why());
            }
            restoreLeg();
        } catch (RuntimeException e) {
            forced = Car.Status.failed("start: " + e);
            restoreLeg();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void setForLeg(baritone.api.Settings.Setting<T> st, T v) {
        var key = (baritone.api.Settings.Setting<Object>) st;
        if (!legSettings.containsKey(key)) legSettings.put(key, new Object[]{st.value, v});
        st.value = v;
    }

    /** Puts back each builder setting this leg set, unless someone else changed it meanwhile (then theirs stays). */
    private void restoreLeg() {
        for (var e : legSettings.entrySet()) {
            if (java.util.Objects.equals(e.getKey().value, e.getValue()[1])) e.getKey().value = e.getValue()[0];
        }
        legSettings.clear();
    }

    private Car.Status buildStatus() {
        if (builder.legRun() != buildRun) { restoreLeg(); return Car.Status.failed("another build leg took the builder (run " + builder.legRun() + ", mine " + buildRun + ")"); }
        return switch (builder.legStatus()) {
            case DONE -> { restoreLeg(); yield Car.Status.done(builder.legWhy()); }
            case FAILED -> { restoreLeg(); yield Car.Status.failed("stage build: " + builder.legWhy()); }
            case IDLE -> { restoreLeg(); yield Car.Status.failed("never started"); }
            default -> Car.Status.running(builder.legWhy());
        };
    }

    @Override public Car.Status status() {
        if (forced != null) return forced;
        if (builder != null) {
            try { return buildStatus(); } catch (RuntimeException | LinkageError e) { restoreLeg(); return Car.Status.failed("status: " + e); }
        }
        if (proc == null) return Car.Status.failed("nothing started");
        try {
            // review 2: another run (a danger move's burrow) took the process: its status is not mine
            int now = proc.run();
            if (now != mine) return Car.Status.failed("another Baritone burrow run took the process (run " + now + ", mine " + mine + ")");
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
            if (proc != null && proc.run() == mine && proc.status() == IBurrowProcess.Status.RUNNING) proc.cancel(why);
            if (builder != null && builder.legRun() == buildRun) builder.cancelLeg(why);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] job hands: cancel failed: {}", e.toString());
        }
        restoreLeg();
        builder = null;
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
