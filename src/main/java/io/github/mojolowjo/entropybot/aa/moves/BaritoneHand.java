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
        interact = null;
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
            var lv = Minecraft.getInstance().level;
            if (lv == null) { forced = Car.Status.failed("stage plan: no world"); return; }
            String dim = Guard.dimOf(lv);
            proc.startJob(d, k, timeoutTicks, new IBurrowProcess.JobGuard() {
                @Override public String stand(BlockPos f) { return standVerdict(dim, f); }

                @Override public String breakCell(BlockPos q) { return guardVerdict(lv, dim, q, "break"); }
            });
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

    // ---- builder placements (fork: IBuilderProcess.buildLeg; its settings are set and put back by the fork) ----
    private baritone.api.process.IBuilderProcess builder;
    private int buildRun = -1;

    @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks, boolean replace) {
        proc = null;
        builder = null;
        interact = null;
        forced = null;
        try {
            var p = Minecraft.getInstance().player;
            var lv = Minecraft.getInstance().level;
            if (p == null || lv == null) { forced = Car.Status.failed("stage plan: no player"); return; }
            java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> want = new java.util.LinkedHashMap<>();
            java.util.Map<Item, Integer> need = new java.util.LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                ResourceLocation rl = ResourceLocation.tryParse(items.get(i));
                Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                if (!(it instanceof net.minecraft.world.item.BlockItem bi)) {
                    forced = Car.Status.failed("stage plan: " + items.get(i) + " is not a block item (the builder places blocks only)", cells.get(i));
                    return;
                }
                want.put(pos(cells.get(i)), bi.getBlock().defaultBlockState());
                need.merge(it, 1, Integer::sum);
            }
            // the builder can't fetch: every item comes from the bag, a missing one fails here with its name
            for (var e : need.entrySet()) {
                int have = p.getInventory().countItem(e.getKey());
                if (have < e.getValue()) {
                    forced = Car.Status.failed("stage items: need " + e.getValue() + " " + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + ", the bag has " + have, cells.get(0));
                    return;
                }
            }
            String dim = Guard.dimOf(lv);
            baritone.api.process.IBuilderProcess.LegGuard g = new baritone.api.process.IBuilderProcess.LegGuard() {
                @Override public String cell(BlockPos q) { return guardVerdict(lv, dim, q, "place"); }

                @Override public String stand(BlockPos f) { return standVerdict(dim, f); }

                @Override public String breakCell(BlockPos q) { return guardVerdict(lv, dim, q, "break"); }
            };
            builder = b().getBuilderProcess();
            builder.buildLeg("place " + String.join(",", items), want, timeoutTicks, g, replace);
            buildRun = builder.legRun();
        } catch (LinkageError e) {
            forced = Car.Status.failed("this Baritone has no build legs (an older jar? install the entropy fork from baritone-builder): " + e);
            if (!warned) {
                warned = true;
                LOG.warn("[entropybot] job hands: {}", forced.why());
            }
        } catch (RuntimeException e) {
            forced = Car.Status.failed("start: " + e);
        }
    }

    /** one-planner: the fork's placeNear (Baritone picks the cell, the stand and the face; the mod names none). */
    @Override public void startPlaceNear(String item, int radius, int timeoutTicks) {
        proc = null;
        builder = null;
        interact = null;
        forced = null;
        try {
            var p = Minecraft.getInstance().player;
            var lv = Minecraft.getInstance().level;
            if (p == null || lv == null) { forced = Car.Status.failed("stage plan: no player"); return; }
            ResourceLocation rl = ResourceLocation.tryParse(item.contains(":") ? item : "minecraft:" + item);
            Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            if (!(it instanceof net.minecraft.world.item.BlockItem bi)) { forced = Car.Status.failed("stage plan: " + item + " is not a block item (the builder places blocks only)"); return; }
            if (p.getInventory().countItem(it) < 1) { forced = Car.Status.failed("stage items: need 1 " + rl.getPath() + ", the bag has 0"); return; }
            String dim = Guard.dimOf(lv);
            baritone.api.process.IBuilderProcess.LegGuard g = new baritone.api.process.IBuilderProcess.LegGuard() {
                @Override public String cell(BlockPos q) { return guardVerdict(lv, dim, q, "place"); }

                @Override public String stand(BlockPos f) { return standVerdict(dim, f); }

                @Override public String breakCell(BlockPos q) { return guardVerdict(lv, dim, q, "break"); }
            };
            builder = b().getBuilderProcess();
            builder.placeNear("place " + rl.getPath() + " near me", bi.getBlock().defaultBlockState(), radius, timeoutTicks, g);
            buildRun = builder.legRun();
        } catch (LinkageError e) {
            forced = Car.Status.failed("this Baritone has no placeNear (an older jar? install the entropy fork from baritone-one-planner): " + e);
            if (!warned) {
                warned = true;
                LOG.warn("[entropybot] job hands: {}", forced.why());
            }
        } catch (RuntimeException e) {
            forced = Car.Status.failed("start: " + e);
        }
    }

    // ---- block use (fork: IInteractProcess, apart from the placing hands) ----
    private baritone.api.process.IInteractProcess interact;
    private int useRun = -1;

    /** The fork's InteractProcess.use: it stands within reach, looks and clicks; the policy's walk verdict vetoes the stand before each click. */
    @Override public void startUse(int[] on) {
        proc = null;
        builder = null;
        interact = null;
        forced = null;
        try {
            var lv = Minecraft.getInstance().level;
            if (lv == null) { forced = Car.Status.failed("use: no world"); return; }
            String dim = Guard.dimOf(lv);
            interact = b().getInteractProcess();
            interact.use(pos(on), f -> standVerdict(dim, f));
            useRun = interact.run();
        } catch (LinkageError e) {
            interact = null;
            forced = Car.Status.failed("this Baritone has no interact process (an older jar? install the entropy fork from baritone-interact): " + e);
            if (!warned) {
                warned = true;
                LOG.warn("[entropybot] job hands: {}", forced.why());
            }
        } catch (RuntimeException e) {
            interact = null;
            forced = Car.Status.failed("use: start: " + e);
        }
    }

    private Car.Status useStatus() {
        if (interact.run() != useRun) return Car.Status.failed("another use took the interact process (run " + interact.run() + ", mine " + useRun + ")");
        return switch (interact.status()) {
            case DONE -> Car.Status.done(interact.why());
            case FAILED -> interact.end() == baritone.api.process.LegEnd.LOST_CONTROL ? Car.Status.lost(interact.why()) : Car.Status.failed(interact.why());
            case IDLE -> Car.Status.failed("never started");
            default -> Car.Status.running(interact.why());
        };
    }

    /** The guard's dry run for this action on the cell: null = allowed, else "the guard: why". */
    private static String guardVerdict(net.minecraft.world.level.Level lv, String dim, BlockPos q, String action) {
        var v = Guard.INSTANCE.core.checkUnlogged(dim, q.getX(), q.getY(), q.getZ(), action, Guard.INSTANCE.infoFor(lv, q));
        return v.allowed() ? null : "the guard (" + action + "): " + v.reason();
    }

    /** The policy's walk verdict for a feet cell Baritone chose (keep-out, portals, denied dimensions). */
    private static String standVerdict(String dim, BlockPos f) {
        return io.github.mojolowjo.entropybot.commands.FenceRules.walkVerdict(
                io.github.mojolowjo.entropybot.api.BotAPI.check(dim, f.getX(), f.getY(), f.getZ(), "go"));
    }

    private static int[] cellOf(BlockPos q) {
        return q == null ? null : new int[]{q.getX(), q.getY(), q.getZ()};
    }

    private Car.Status buildStatus() {
        if (builder.legRun() != buildRun) return Car.Status.failed("another build leg took the builder (run " + builder.legRun() + ", mine " + buildRun + ")");
        int[] at = cellOf(builder.legCell());
        return switch (builder.legStatus()) {
            case DONE -> Car.Status.done(builder.legWhy());
            case FAILED -> builder.legEnd() == baritone.api.process.LegEnd.LOST_CONTROL
                    ? Car.Status.lost("stage build: " + builder.legWhy())
                    : Car.Status.failed("stage build: " + builder.legWhy(), at);
            case IDLE -> Car.Status.failed("never started");
            default -> Car.Status.running(builder.legWhy());
        };
    }

    /** A higher temporary process (a reflex goal, the pause, a danger burrow) holds Baritone now. */
    @Override public boolean reflexActive() {
        try {
            var in = b().getPathingControlManager().mostRecentInControl();
            return in.isPresent() && in.get().isTemporary() && in.get().priority() > baritone.api.process.IBuilderProcess.LEG_PRIORITY;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] job hands: who holds Baritone unreadable: {}", e.toString());
            return false;
        }
    }
    @Override public Car.Status status() {
        if (forced != null) return forced;
        if (builder != null) {
            try { return buildStatus(); } catch (RuntimeException | LinkageError e) { return Car.Status.failed("status: " + e); }
        }
        if (interact != null) {
            try { return useStatus(); } catch (RuntimeException | LinkageError e) { return Car.Status.failed("status: " + e); }
        }
        if (proc == null) return Car.Status.failed("nothing started");
        try {
            // review 2: another run (a danger move's burrow) took the process: its status is not mine
            int now = proc.run();
            if (now != mine) return Car.Status.failed("another Baritone burrow run took the process (run " + now + ", mine " + mine + ")");
            return switch (proc.status()) {
                case DONE -> Car.Status.done(proc.why());
                case FAILED -> proc.end() == baritone.api.process.LegEnd.LOST_CONTROL ? Car.Status.lost(proc.why()) : Car.Status.failed(proc.why(), cellOf(proc.clickedCell()));
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
            if (interact != null && interact.run() == useRun && interact.status() == baritone.api.process.IInteractProcess.Status.RUNNING) interact.cancel();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] job hands: cancel failed: {}", e.toString());
        }
        interact = null;
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
