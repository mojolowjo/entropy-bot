package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.engine.Hotbar;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.vocab.FishRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * aa-kelpfish {@code fish <n|minutes>} / {@code fish status}: craft a rod when the bag has 3 sticks + 2 string and no
 * rod, walk to a water surface (a 3x3 patch inside the areas, 3 to 9 blocks away), cast, wait, reel in when the
 * bobber's bite flag is set, cast again; stops at n fish (cod, salmon, tropical fish) or the minutes, or stop. Every
 * 5 ticks (a bite lasts 20 to 40 ticks, so the reaction is in time). The bite flag is read from the bobber's public
 * {@code SynchedEntityData.getNonDefaultValues()} (DATA_BITING itself is private); the status says "bobber unreadable"
 * if a bobber sits 20 s and never shows any synced value. Never runs while a reflex holds the bot (the air reflex,
 * fights). Ends with the reason: no rod and no recipe, no water, the rod craft failed, the time limit.
 * Loader notes: vanilla Player.fishing / FishingHook / MultiPlayerGameMode.useItem; nothing loader-specific.
 */
final class Fishing {
    private static final Logger LOG = LogUtils.getLogger();
    static final long IDLE_END = 60 * 20;
    static final int MAX_WALKS = 4;

    private final Commands c;

    private static final class Run {
        String from;
        FishRules.Parsed goal;
        long started, nextAt, lastBiteCast = -2, castAt = -1, reelAt = -1000, idleSince = -1;
        int baseFish, casts, bites, reels, walks;
        boolean craftTried, aimed;
        String why, lastCraft;
        JobRequests.Request req;
    }

    private Run run;

    Fishing(Commands c) { this.c = c; }

    /** AA_CLEANUP 2: who started the run (the door scope then; null = none). */
    io.github.mojolowjo.entropybot.aa.door.Driver driver;

    boolean running() { return run != null; }

    Chains.Reply command(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.equals("status")) return Chains.Reply.now(run == null ? "fish: not running" : status(p));
        FishRules.Parsed g = FishRules.parse(rest);
        if (g == null) return Chains.Reply.now("usage: fish [n] | fish <min>m | fish status");
        if (run != null) return Chains.Reply.now("busy: " + status(p));
        if (c.kelping.running() || c.hunting.running()) return Chains.Reply.now("busy: another food run is on (stop it first)");
        Run k = new Run();
        k.from = from;
        k.goal = g;
        k.started = Core.INSTANCE.tick();
        k.baseFish = FishRules.fish(Gui.inventory(p));
        String reply = "started: fishing " + (g.timed() ? g.minutes() + " min" : "until " + g.n() + " fish") + " (fish status)";
        k.req = c.requests.local("fish", from, raw, reply, l);
        run = k;
        driver = io.github.mojolowjo.entropybot.aa.door.Door.current();     // AA_CLEANUP 2: its ticks run as its starter
        LOG.info("[entropybot] fish: {} for {}", g, from);
        return new Chains.Reply(reply, k.req);
    }

    String statusText(LocalPlayer p) { return run == null ? null : status(p); }

    private String status(LocalPlayer p) {
        Run r = run;
        int caught = p == null ? 0 : Math.max(0, FishRules.fish(Gui.inventory(p)) - r.baseFish);
        return "fish: " + caught + (r.goal.timed() ? " fish in " + (Core.INSTANCE.tick() - r.started) / 1200 + "/" + r.goal.minutes() + " min" : "/" + r.goal.n() + " fish")
                + ", " + r.casts + " casts, " + r.bites + " bites" + (r.why != null ? " - waiting: " + r.why : "");
    }

    String stop(String why) {
        Run r = run;
        if (r == null) return null;
        end(r, "stopped: fish - " + why);
        return "fish";
    }

    private void end(Run r, String text) {
        if (run != r) return;
        run = null;
        reelInQuietly();
        LOG.info("[entropybot] fish: {}", text);
        c.requests.done(r.req.id, text);
    }

    private static void reelInQuietly() {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p != null && p.fishing != null && p.getMainHandItem().is(Items.FISHING_ROD)) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] fish: reel in on stop failed: {}", e.toString());
        }
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
        for (int i = 0; i < items.size(); i++) if (items.get(i).is(Items.FISHING_ROD)) return i;
        return -1;
    }

    /** Every 5 ticks. */
    void tick(LocalPlayer p) {
        Run r = run;
        if (r == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = Core.INSTANCE.tick();
        Map<String, Integer> bag = Gui.inventory(p);
        int fish = FishRules.fish(bag);
        if (fish < r.baseFish) r.baseFish = fish;                      // I ate or cooked some: the count starts from what is left
        int caught = fish - r.baseFish;
        FishingHook hook = p.fishing;
        int slot = rodSlot(p);
        boolean haveRod = slot >= 0;
        int sticks = bag.getOrDefault("minecraft:stick", 0), string = bag.getOrDefault("minecraft:string", 0);
        if (now < r.nextAt) return;
        var refl = Core.INSTANCE.reflexes;
        boolean busy = p.isDeadOrDying() || refl.hold() || c.jobs.running();
        // the end tests come first even when busy (time and count), but only the bite-free ones; the rest waits for the bot
        List<Object> sv = synced(hook);
        boolean biting = FishRules.bite(sv);
        if (biting && r.lastBiteCast != r.castAt) { r.bites++; r.lastBiteCast = r.castAt; }
        if (hook != null && r.castAt < 0) r.castAt = now;            // a bobber I did not cast: time it from now
        if (busy && !(r.goal.timed() ? now - r.started >= r.goal.minutes() * 1200L : caught >= r.goal.n() || now - r.started >= FishRules.COUNT_MAX)) return;
        int[] spotInfo = null;
        boolean needSpot = hook == null && haveRod && now - r.reelAt >= FishRules.RECAST_DELAY;
        if (needSpot) spotInfo = FishRules.pick(surface(p), p.getBlockX(), p.getBlockZ(), this::inAreas);
        boolean spotOk = spotInfo != null && spotInfo[3] == 0, spotFar = spotInfo != null && spotInfo[3] == 1;
        FishRules.State st = new FishRules.State(haveRod, FishRules.canCraftRod(sticks, string), r.craftTried, hook != null, biting,
                hook == null || r.castAt < 0 ? 0 : now - r.castAt, now - r.reelAt, spotOk, spotFar, caught, r.goal, now - r.started);
        FishRules.Act act = FishRules.next(st);
        switch (act) {
            case DONE_COUNT -> end(r, "ok: fished " + caught + " fish (" + r.casts + " casts, " + r.bites + " bites) - next: cook cod " + caught + " (or salmon)");
            case DONE_TIME -> end(r, "ok: fished " + r.goal.minutes() + " min: " + caught + " fish (" + r.casts + " casts, " + r.bites + " bites)");
            case TIMED_OUT -> end(r, "stopped: fish " + caught + "/" + r.goal.n() + " after " + FishRules.COUNT_MAX / 1200 + " min (" + r.casts + " casts, " + r.bites + " bites)");
            case CRAFT_ROD -> {
                r.craftTried = true;
                Chains.Reply rep = c.handle(r.from, "craft fishing_rod 1", true, null);
                r.lastCraft = rep.text();
                LOG.info("[entropybot] fish: craft rod: {}", rep.text());
                r.nextAt = now + 40;
            }
            case NO_ROD -> {
                String why = FishRules.rodWhy(false, sticks, string);
                end(r, "stopped: fish - " + (why != null ? why : "the rod craft did not give a rod: " + r.lastCraft));
            }
            case WAIT -> { }
            case WAIT_BITE -> {
                r.why = null;
                if (r.castAt >= 0 && now - r.castAt >= 400 && now - r.castAt < 405) LOG.info("[entropybot] fish: no bite yet after 20 s (the bite is a synced flag on the bobber)");
            }
            case GO_WATER -> {
                if (++r.walks > MAX_WALKS) { end(r, "stopped: fish " + caught + "/" + r.goal.n() + " - could not get within casting distance of water in " + MAX_WALKS + " walks"); return; }
                int[] s = FishRules.standPoint(spotInfo, p.getBlockX(), p.getBlockZ());
                r.why = "walking to the water at " + spotInfo[0] + " " + spotInfo[1] + " " + spotInfo[2];
                c.handle(r.from, "goto " + s[0] + " " + s[1] + " " + s[2], true, null);
                r.nextAt = now + 60;
            }
            case NO_WATER -> {
                r.why = "I see no water surface (3x3 patch) inside my areas within " + FishRules.RANGE + " blocks - walk me to some";
                if (r.idleSince < 0) r.idleSince = now;
                r.nextAt = now + 100;
                if (now - r.idleSince >= IDLE_END) end(r, "stopped: fish " + caught + "/" + r.goal.n() + " - " + r.why);
            }
            case CAST -> {
                r.idleSince = -1;
                r.why = null;
                r.walks = 0;
                if (!p.getMainHandItem().is(Items.FISHING_ROD)) {
                    if (!Hotbar.toHand(mc, p, slot)) { r.nextAt = now + 20; return; }
                    r.nextAt = now + 5;
                    return;
                }
                if (!r.aimed) {                                          // turn first: the server uses the rotation it was last sent
                    p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(spotInfo[0] + 0.5, spotInfo[1] + 0.5, spotInfo[2] + 0.5));
                    r.aimed = true;
                    r.nextAt = now + 5;
                    return;
                }
                r.aimed = false;
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                r.castAt = now;
                r.casts++;
                r.nextAt = now + 10;
            }
            case REEL_BITE, REEL_TIMEOUT -> {
                if (!p.getMainHandItem().is(Items.FISHING_ROD)) {
                    if (!Hotbar.toHand(mc, p, slot)) { r.nextAt = now + 20; return; }
                    r.nextAt = now + 5;
                    return;
                }
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                r.reelAt = now;
                r.castAt = -1;
                r.nextAt = now + 5;
                LOG.info("[entropybot] fish: reel in ({})", act == FishRules.Act.REEL_BITE ? "bite" : "no bite for 40 s, casting again");
            }
        }
    }

    private boolean inAreas(int[] cell) {
        String dim = Storage.dim();
        return c.inAreas(dim, cell[0], cell[2])
                || (Core.INSTANCE.guard.core.nearBox() != null && Core.INSTANCE.guard.core.nearBox().contains(dim, cell[0], cell[1], cell[2]));
    }

    /** Water source cells with air above, near me (one scan per cast). */
    private List<int[]> surface(LocalPlayer p) {
        var level = Minecraft.getInstance().level;
        List<int[]> out = new ArrayList<>();
        if (level == null) return out;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int R = FishRules.RANGE;
        for (int x = p.getBlockX() - R; x <= p.getBlockX() + R; x++)
            for (int z = p.getBlockZ() - R; z <= p.getBlockZ() + R; z++)
                for (int y = p.getBlockY() - 4; y <= p.getBlockY() + 2; y++) {
                    if (!level.getFluidState(q.set(x, y, z)).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) continue;
                    if (level.getBlockState(q.set(x, y + 1, z)).isAir()) out.add(new int[]{x, y, z});
                }
        return out;
    }
}

