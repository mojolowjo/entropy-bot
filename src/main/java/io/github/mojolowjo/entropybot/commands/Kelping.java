package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.vocab.KelpRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * aa-kelpfish {@code gather kelp <n> [<min>m] [cook]} / {@code gather kelp status}: cut kelp columns inside the areas
 * (one block above the base, so the base regrows and the column above drops), walk over the drops, stop at n kelp (or
 * the minutes, or stop). Each cut is the dig verb's water mode, so the mod's own water rules and leases apply; the air
 * reflex owns surfacing (this verb waits for a breath before the next dive, {@link KelpRules#mayDive}, and never runs
 * while a reflex holds the bot). With {@code cook} the kelp is smelted to dried kelp at the end ({@code cook kelp n}).
 * Errors: no kelp in reach, three cuts that gained nothing, a refused dig and the time limit all end it with the reason.
 * Loader notes: vanilla block states (Blocks.KELP / KELP_PLANT) and the client level; nothing loader-specific.
 */
final class Kelping {
    private static final Logger LOG = LogUtils.getLogger();
    static final long IDLE_END = 60 * 20;
    static final int MAX_DRY = 3;

    private final Commands c;

    private static final class Run {
        String from;
        KelpRules.Parsed goal;
        long started, nextAt, idleSince = -1;
        String why;
        int[] dropAt;
        int lastHave, dry, refused, cuts;
        boolean cooking, picked;
        JobRequests.Request req;
    }

    private Run run;

    Kelping(Commands c) { this.c = c; }

    /** AA_CLEANUP 2: who started the run (the door scope then; null = none). */
    io.github.mojolowjo.entropybot.aa.door.Driver driver;

    boolean running() { return run != null; }

    Chains.Reply command(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.equals("kelp status")) return Chains.Reply.now(run == null ? "gather kelp: not running" : status(p));
        KelpRules.Parsed g = KelpRules.parse(rest);
        if (g == null) return Chains.Reply.now("usage: gather kelp [n] [<min>m] [cook] | gather kelp status");
        if (run != null) return Chains.Reply.now("busy: " + status(p));
        if (c.fishing.running() || c.hunting.running()) return Chains.Reply.now("busy: another food run is on (stop it first)");
        Run k = new Run();
        k.from = from;
        k.goal = g;
        k.started = Core.INSTANCE.tick();
        k.lastHave = p == null ? 0 : KelpRules.have(Gui.inventory(p));
        String reply = "started: cutting kelp until I have " + g.n() + " (up to " + g.minutes() + " min" + (g.cook() ? ", then dried kelp" : "") + "; gather kelp status)";
        k.req = c.requests.local("gather", from, raw, reply, l);
        run = k;
        driver = io.github.mojolowjo.entropybot.aa.door.Door.current();     // AA_CLEANUP 2: its ticks run as its starter
        LOG.info("[entropybot] gather kelp: {} for {}", g, from);
        return new Chains.Reply(reply, k.req);
    }

    String statusText(LocalPlayer p) { return run == null ? null : status(p); }

    private String status(LocalPlayer p) {
        Run r = run;
        int have = p == null ? 0 : KelpRules.have(Gui.inventory(p));
        return "gather kelp: " + have + "/" + r.goal.n() + (r.cooking ? " (smelting)" : "") + ", " + r.cuts + " cuts" + (r.why != null ? " - waiting: " + r.why : "");
    }

    String stop(String why) {
        Run r = run;
        if (r == null) return null;
        end(r, "stopped: gather kelp - " + why);
        return "gather kelp";
    }

    private void end(Run r, String text) {
        if (run != r) return;
        run = null;
        LOG.info("[entropybot] gather kelp: {}", text);
        c.requests.done(r.req.id, text);
    }

    /** Every 5 ticks. */
    void tick(LocalPlayer p) {
        Run r = run;
        if (r == null) return;
        long now = Core.INSTANCE.tick();
        Map<String, Integer> bag = Gui.inventory(p);
        int have = KelpRules.have(bag);
        if (r.cooking) {
            if (!c.jobs.running()) end(r, "ok: " + have + " kelp, smelted (dried kelp) - eat it with eat");
            return;
        }
        if (KelpRules.done(bag, r.goal.n())) {
            int plain = bag.getOrDefault(KelpRules.KELP, 0);
            if (r.goal.cook() && plain > 0) {
                Chains.Reply rep = c.handle(r.from, "cook kelp " + plain, true, null);
                if (rep.text().startsWith("error") || rep.text().startsWith("busy") || !c.jobs.running()) end(r, "ok: gathered " + have + " kelp - the cook failed: " + rep.text() + " (needs a furnace; next: cook kelp " + plain + ")");
                else r.cooking = true;
                return;
            }
            end(r, "ok: gathered " + have + " kelp" + (plain > 0 ? " - next: cook kelp " + plain : ""));
            return;
        }
        if (now - r.started > r.goal.minutes() * 60L * 20L) { end(r, "stopped: gather kelp " + have + "/" + r.goal.n() + " after " + r.goal.minutes() + " min"); return; }
        if (now < r.nextAt || p.isDeadOrDying()) return;
        var refl = Core.INSTANCE.reflexes;
        if (refl.hold()) return;                                      // the air reflex, a fight, a meal: they go first
        if (c.jobs.running()) return;                                 // the cut or the walk to the drops
        if (!KelpRules.mayDive(p.isUnderWater(), p.getAirSupply(), p.getMaxAirSupply())) { r.why = "catching my breath"; return; }
        if (r.picked) {                                               // the last cut and its pickup walk are over: did it pay?
            r.picked = false;
            if (have > r.lastHave) r.dry = 0;
            else if (++r.dry >= MAX_DRY) {
                end(r, "stopped: gather kelp " + have + "/" + r.goal.n() + " - " + MAX_DRY + " cuts in a row gave no kelp (the dig, or the drops out of reach)");
                return;
            }
            r.lastHave = have;
        }
        if (r.dropAt != null) {                                       // walk over where the column was, for the drops
            c.handle(r.from, "goto " + r.dropAt[0] + " " + r.dropAt[1] + " " + r.dropAt[2], true, null);
            r.dropAt = null;
            r.picked = true;
            r.nextAt = now + 40;
            return;
        }
        KelpRules.Column col = KelpRules.pick(scan(p), p.getBlockX(), p.getBlockY(), p.getBlockZ(), this::inAreas);
        if (col == null) {
            r.why = "I see no kelp column of 2+ blocks inside my areas within " + KelpRules.RANGE + " blocks - walk me to some";
            if (r.idleSince < 0) r.idleSince = now;
            r.nextAt = now + 100;
            if (now - r.idleSince >= IDLE_END) end(r, "stopped: gather kelp " + have + "/" + r.goal.n() + " - " + r.why);
            return;
        }
        r.idleSince = -1;
        r.why = null;
        Chains.Reply rep = c.handle(r.from, KelpRules.digLine(col), true, null);
        r.cuts++;
        if (rep.text().startsWith("error") || rep.text().startsWith("busy")) {
            if (++r.refused >= 3) { end(r, "stopped: gather kelp " + have + "/" + r.goal.n() + " - the dig was refused 3 times: " + rep.text()); return; }
            r.nextAt = now + 60;
            LOG.info("[entropybot] gather kelp: dig refused: {}", rep.text());
            return;
        }
        r.refused = 0;
        int[] cut = col.cut();
        r.dropAt = new int[]{cut[0], cut[1], cut[2]};
        r.nextAt = now + 20;
    }

    private boolean inAreas(int[] cell) {
        String dim = Storage.dim();
        return c.inAreas(dim, cell[0], cell[2])
                || (Core.INSTANCE.guard.core.nearBox() != null && Core.INSTANCE.guard.core.nearBox().contains(dim, cell[0], cell[1], cell[2]));
    }

    /** The kelp columns within range (one scan per pick). */
    private List<KelpRules.Column> scan(LocalPlayer p) {
        var level = Minecraft.getInstance().level;
        List<int[]> blocks = new ArrayList<>();
        if (level == null) return List.of();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int R = KelpRules.RANGE;
        for (int x = p.getBlockX() - R; x <= p.getBlockX() + R; x++)
            for (int z = p.getBlockZ() - R; z <= p.getBlockZ() + R; z++)
                for (int y = p.getBlockY() - 24; y <= p.getBlockY() + 2; y++) {
                    var s = level.getBlockState(q.set(x, y, z));
                    if (s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT)) blocks.add(new int[]{x, y, z});
                }
        return KelpRules.columns(blocks);
    }
}

