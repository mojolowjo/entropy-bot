package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.clear.ClearGrid;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.PlaceRules;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.restore.Ledger;
import io.github.mojolowjo.entropybot.restore.RestorePlan;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P1: one restore, stepped by {@link Jobs} every tick: the planned cells newest first, each walked to (Baritone with
 * breaking and placing off) when out of reach, then put back with the mod's own placement ({@link Clearing#placeAt} with
 * a restore lease: any guard mode, never in a protect box, only into air or water). A cell that is no longer empty is
 * dropped from the ledger (someone built there); a wet or occupied one, or one it can't reach, stays for "restore now".
 * Every cell that is not put back is a {@link RestorePlan.Left} the end line names.
 */
final class RestoreRun {
    private static final Logger LOG = LogUtils.getLogger();
    static final long WALK_MAX = 20 * 60, CHECK_TICKS = 6;

    final List<RestorePlan.Action> queue;
    final List<RestorePlan.Left> left = new ArrayList<>();
    final LeaseSet leases = Clearing.newLeases();
    int placed, placedEscape, idx, tries;
    String stage, item;
    long stageTick;
    boolean ended;

    RestoreRun(RestorePlan.Plan plan) {
        queue = new ArrayList<>(plan.actions());
        left.addAll(plan.left());
    }

    boolean empty() { return queue.isEmpty(); }

    int total() { return queue.size(); }

    String summary() { return RestorePlan.summary(placed, left); }

    /** The rest of the queue is not done (a stop, a fight that ended the job): each a Left with why. */
    void stopRest(String why) {
        for (int i = idx; i < queue.size(); i++) left.add(new RestorePlan.Left(queue.get(i).entry(), why));
        idx = queue.size();
    }

    void end() {
        if (ended) return;
        ended = true;
        leases.releaseAll();
        Jobs.safeSettings();
    }

    private void next() {
        idx++;
        stage = null;
        tries = 0;
        item = null;
    }

    private void skip(Ledger.Entry e, String why) {
        left.add(new RestorePlan.Left(e, why));
        LOG.info("[entropybot] restore: left {} {} {} ({})", e.x, e.y, e.z, why);
        IBaritone b = Jobs.baritone();
        if (b != null && "walking".equals(stage)) Jobs.cancel(b);
        next();
    }

    /** One tick; true once every cell had its turn. */
    boolean step(LocalPlayer p, long now) {
        if (idx >= queue.size()) return true;
        RestorePlan.Action a = queue.get(idx);
        Ledger.Entry e = a.entry();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        BlockPos pos = new BlockPos(e.x, e.y, e.z);
        if (!level.isLoaded(pos)) {
            skip(e, "too far");
            return false;
        }
        BlockState st = level.getBlockState(pos);
        if (!"check".equals(stage)) {
            if (!st.getFluidState().isEmpty()) {
                skip(e, st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water");
                return false;
            }
            if (!st.canBeReplaced()) {
                RestoreLive.INSTANCE.drop(e);
                skip(e, e.x + " " + e.y + " " + e.z + " is no longer empty");
                return false;
            }
            AABB cell = new AABB(pos);
            if (!level.getEntitiesOfClass(LivingEntity.class, cell, en -> en != p && en.isAlive()).isEmpty()) {
                skip(e, "occupied");
                return false;
            }
        }
        if (stage == null) {
            item = pickItem(p, e, a.item());
            if (item == null) {
                skip(e, "no " + RestoreRules.shortId(e.items.get(e.items.size() - 1)));
                return false;
            }
            Clearing.LiveWorld w = new Clearing.LiveWorld();
            w.set(p);
            boolean botInCell = p.getBoundingBox().intersects(new AABB(pos));
            boolean reach = !botInCell && p.getEyePosition().distanceTo(pos.getCenter()) <= 4.5
                    && PlaceRules.placeSide(w, e.x, e.y, e.z, p.getX(), p.getEyeY(), p.getZ()) != null;
            if (reach) {
                stage = "place";
            } else {
                ClearGrid.Spot spot = PlaceRules.standFor(w, new Pos(e.x, e.y, e.z), McClearWorld.botOf(p), null);
                IBaritone b = Jobs.baritone();
                if (spot == null || b == null || (spot.x() == e.x && spot.z() == e.z && (spot.y() == e.y || spot.y() + 1 == e.y))) {
                    skip(e, "couldn't reach");
                    return false;
                }
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(spot.x(), spot.y(), spot.z())));
                stage = "walking";
                stageTick = now;
                return false;
            }
        }
        switch (stage) {
            case "walking" -> {
                if (now - stageTick < 20) return false;
                IBaritone b = Jobs.baritone();
                if (b != null && !Jobs.idle(b)) {
                    if (now - stageTick > WALK_MAX) skip(e, "couldn't reach");
                    return false;
                }
                tries++;
                if (tries > 2) {
                    skip(e, "couldn't reach");
                    return false;
                }
                stage = null;                                // look again from where the walk ended
                return false;
            }
            case "retry" -> {
                if (now - stageTick >= 10) stage = "place";
                return false;
            }
            case "place" -> {
                String r;
                try {
                    r = Clearing.placeAt(p, item, e.x, e.y, e.z, leases, null, true);
                } catch (RuntimeException ex) {
                    r = "error: " + ex;
                }
                if (r.equals(PlaceRules.ALREADY_THERE)) {
                    done(e);
                    return false;
                }
                if (!r.startsWith("ok")) {
                    tries++;
                    if (tries >= 3 || r.contains("guard refused")) {
                        skip(e, "couldn't place: " + r.replaceFirst("^error: ", ""));
                        return false;
                    }
                    stage = "retry";
                    stageTick = now;
                    return false;
                }
                stage = "check";
                stageTick = now;
                return false;
            }
            case "check" -> {
                if (now - stageTick < CHECK_TICKS) return false;
                if (!level.getBlockState(pos).canBeReplaced()) {
                    done(e);
                    return false;
                }
                tries++;
                if (tries >= 3) {
                    skip(e, "couldn't place: it didn't stay");
                    return false;
                }
                stage = "place";
                return false;
            }
            default -> {
                stage = null;
                return false;
            }
        }
    }

    private void done(Ledger.Entry e) {
        placed++;
        if (Ledger.ESCAPE.equals(e.reason)) placedEscape++;
        RestoreLive.INSTANCE.drop(e);
        next();
    }

    /** The planned item when the bag still has it, else any other of the entry's items it has; null for none. */
    private static String pickItem(LocalPlayer p, Ledger.Entry e, String planned) {
        Map<String, Integer> inv = Gui.inventory(p);
        if (planned != null && inv.getOrDefault(planned, 0) > 0) return planned;
        for (String c : e.items) if (inv.getOrDefault(c, 0) > 0) return c;
        return null;
    }
}
