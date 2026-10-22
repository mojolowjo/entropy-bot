package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.engine.Hotbar;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.vocab.FishRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rm-final: the fishing step of the one Steps machine ({@code Step.Fish}), no tick loop of its own: {@link StepHands} asks
 * {@link #tick} every tick like any step, and the Steps machine owns the time limit, the end and the failure with its stage
 * (charged to the option by Failures). The cast and the reel are the item input on Baritone's look (the camera is
 * LookBehavior's target through Moves.lookAt, the rod comes to the hand through the hotbar); the walk to the water is one
 * Baritone GoalNear on a water surface cell (Baritone picks where to stand within casting distance). The bite flag is read
 * from the bobber's public {@code SynchedEntityData.getNonDefaultValues()} (DATA_BITING itself is private). Never runs a
 * cast while a reflex holds the bot (the air reflex, fights). Loader notes: vanilla Player.fishing / FishingHook /
 * MultiPlayerGameMode.useItem; nothing loader-specific.
 */
final class FishStep {
    private static final Logger LOG = LogUtils.getLogger();

    private final Commands c;
    private final FishRules.Parsed goal;
    private long started, nextAt, castAt = -1, reelAt = -1000, idleSince = -1, lastBiteCast = -2;
    private int baseFish, casts, bites, walks;
    private boolean aimed, walking;
    private String why;

    FishStep(Commands c, Step.Fish f, LocalPlayer p) {
        this.c = c;
        this.goal = f.timed() ? new FishRules.Parsed(0, f.minutes()) : new FishRules.Parsed(f.n(), 0);
        this.started = Core.INSTANCE.tick();
        this.baseFish = FishRules.fish(Gui.inventory(p));
    }

    /** Null when the step can start, else why not (no rod: the option puts a craft step before this one). */
    static String canStart(LocalPlayer p) {
        return rodSlot(p) >= 0 ? null : FishRules.rodWhy(false, Gui.inventory(p));
    }

    /** The bobber's synced values that differ from their defaults (the bite flag among them), null without a bobber. */
    private static List<Object> synced(FishingHook h) {
        if (h == null) return null;
        List<SynchedEntityData.DataValue<?>> v = h.getEntityData().getNonDefaultValues();
        List<Object> out = new ArrayList<>();
        if (v != null) for (SynchedEntityData.DataValue<?> d : v) out.add(d.value());
        return out;
    }

    private static int rodSlot(LocalPlayer p) {
        var items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) if (items.get(i).getItem() instanceof FishingRodItem) return i;
        return -1;
    }

    /** The step ends (cancelled, finished, failed): a bobber still out is reeled in; a failed reel is said. */
    void end() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p != null && p.fishing != null && p.getMainHandItem().getItem() instanceof FishingRodItem) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] fish: reel in at the end failed: {}", e.toString());
        }
    }

    String status(LocalPlayer p) {
        int caught = Math.max(0, FishRules.fish(Gui.inventory(p)) - baseFish);
        return "fish: " + caught + (goal.timed() ? " fish in " + (Core.INSTANCE.tick() - started) / 1200 + "/" + goal.minutes() + " min" : "/" + goal.n() + " fish")
                + ", " + casts + " casts, " + bites + " bites" + (why != null ? " - " + why : "");
    }

    /** One tick of the step: RUNNING with what it waits for, DONE with the catch, or FAILED with the reason. */
    Car.Status tick(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        long now = Core.INSTANCE.tick();
        Map<String, Integer> bag = Gui.inventory(p);
        int fish = FishRules.fish(bag);
        if (fish < baseFish) baseFish = fish;                         // I ate or cooked some: the count starts from what is left
        int caught = fish - baseFish;
        FishingHook hook = p.fishing;
        int slot = rodSlot(p);
        if (now < nextAt) return Car.Status.running(status(p));
        boolean busy = p.isDeadOrDying() || Core.INSTANCE.reflexes.hold();
        List<Object> sv = synced(hook);
        boolean biting = FishRules.bite(sv);
        if (biting && lastBiteCast != castAt) { bites++; lastBiteCast = castAt; }
        if (hook != null && castAt < 0) castAt = now;                 // a bobber I did not cast: time it from now
        boolean over = goal.timed() ? now - started >= goal.minutes() * 1200L : caught >= goal.n() || now - started >= FishRules.countMax();
        if (busy && !over) return Car.Status.running("a reflex holds me");
        int[] spot = null;
        boolean needSpot = hook == null && slot >= 0 && now - reelAt >= FishRules.recastDelay();
        if (needSpot) spot = FishRules.pick(surface(p), p.getBlockX(), p.getBlockZ(), this::workAllowed);
        boolean spotOk = spot != null && spot[3] == 0, spotFar = spot != null && spot[3] == 1;
        FishRules.State st = new FishRules.State(slot >= 0, hook != null, biting, hook == null || castAt < 0 ? 0 : now - castAt, now - reelAt,
                spotOk, spotFar, caught, goal, now - started);
        FishRules.Act act = FishRules.next(st);
        switch (act) {
            case DONE_COUNT -> { end(); return Car.Status.done("ok: fished " + caught + " fish (" + casts + " casts, " + bites + " bites)"); }
            case DONE_TIME -> { end(); return Car.Status.done("ok: fished " + goal.minutes() + " min: " + caught + " fish (" + casts + " casts, " + bites + " bites)"); }
            case TIMED_OUT -> { end(); return Car.Status.failed("fish " + caught + "/" + goal.n() + " after " + FishRules.countMax() / 1200 + " min (" + casts + " casts, " + bites + " bites)"); }
            case NO_ROD -> { end(); return Car.Status.failed(FishRules.rodWhy(false, bag) + " (the rod left the bag)"); }
            case WAIT -> { }
            case WAIT_BITE -> {
                why = null;
                if (castAt >= 0 && now - castAt >= 400 && now - castAt < 405) LOG.info("[entropybot] fish: no bite yet after 20 s (the bite is a synced flag on the bobber)");
            }
            case GO_WATER -> {
                if (++walks > Tuning.i("fishing.maxWalks")) {
                    end();
                    return Car.Status.failed("fish " + caught + "/" + goal.n() + " - could not get within casting distance of water in " + Tuning.i("fishing.maxWalks") + " walks");
                }
                why = "walking to the water at " + spot[0] + " " + spot[1] + " " + spot[2];
                var b = Jobs.baritone();
                if (b == null) return Car.Status.failed("baritone not loaded: no walk to the water");
                // one Baritone goal: within casting distance of the water surface cell; Baritone picks where to stand
                b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(new BlockPos(spot[0], spot[1], spot[2]), FishRules.maxCast() - 1));
                nextAt = now + Tuning.l("fishing.walkTicks");
            }
            case NO_WATER -> {
                why = "I see no water surface (3x3 patch) within " + FishRules.range() + " blocks";
                if (idleSince < 0) idleSince = now;
                nextAt = now + Tuning.l("fishing.idleRecheck");
                if (now - idleSince >= Tuning.l("fishing.idleEnd")) { end(); return Car.Status.failed("fish " + caught + "/" + goal.n() + " - " + why); }
            }
            case CAST -> {
                idleSince = -1;
                why = null;
                walks = 0;
                if (!(p.getMainHandItem().getItem() instanceof FishingRodItem)) {
                    if (!Hotbar.toHand(mc, p, slot)) { nextAt = now + Tuning.l("fishing.handRetry"); return Car.Status.running("rod to hand"); }
                    nextAt = now + Tuning.l("fishing.aimTicks");
                    return Car.Status.running("rod to hand");
                }
                if (!aimed) {                                         // turn first: the server uses the rotation it was last sent
                    io.github.mojolowjo.entropybot.aa.moves.Moves.lookAt(new double[]{spot[0] + 0.5, spot[1] + 0.5, spot[2] + 0.5});     // Baritone's look
                    aimed = true;
                    nextAt = now + Tuning.l("fishing.aimTicks");
                    return Car.Status.running("aiming");
                }
                aimed = false;
                io.github.mojolowjo.entropybot.aa.moves.Moves.lookAt(new double[]{spot[0] + 0.5, spot[1] + 0.5, spot[2] + 0.5});     // held through the cast tick
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                castAt = now;
                casts++;
                nextAt = now + Tuning.l("fishing.castRest");
            }
            case REEL_BITE, REEL_TIMEOUT -> {
                if (!(p.getMainHandItem().getItem() instanceof FishingRodItem)) {
                    if (!Hotbar.toHand(mc, p, slot)) { nextAt = now + Tuning.l("fishing.handRetry"); return Car.Status.running("rod to hand"); }
                    nextAt = now + Tuning.l("fishing.aimTicks");
                    return Car.Status.running("rod to hand");
                }
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                reelAt = now;
                castAt = -1;
                nextAt = now + Tuning.l("fishing.aimTicks");
                LOG.info("[entropybot] fish: reel in ({})", act == FishRules.Act.REEL_BITE ? "bite" : "no bite for 40 s, casting again");
            }
        }
        return Car.Status.running(status(p));
    }

    private boolean workAllowed(int[] cell) {
        return c.workAllowed(Storage.dim(), cell[0], cell[2]);
    }

    /** Water source cells with air above, near me (one scan per pick). */
    private List<int[]> surface(LocalPlayer p) {
        var level = Minecraft.getInstance().level;
        List<int[]> out = new ArrayList<>();
        if (level == null) return out;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int r = FishRules.range();
        for (int x = p.getBlockX() - r; x <= p.getBlockX() + r; x++)
            for (int z = p.getBlockZ() - r; z <= p.getBlockZ() + r; z++)
                for (int y = p.getBlockY() - Tuning.i("fishing.scanDown"); y <= p.getBlockY() + Tuning.i("fishing.scanUp"); y++) {
                    if (!level.getFluidState(q.set(x, y, z)).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) continue;
                    if (level.getBlockState(q.set(x, y + 1, z)).isAir()) out.add(new int[]{x, y, z});
                }
        return out;
    }
}