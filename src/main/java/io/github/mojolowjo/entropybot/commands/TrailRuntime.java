package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.TickPrice;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.trail.Trail;
import io.github.mojolowjo.entropybot.aa.trail.Trails;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D4 adapter: records the trail from the feet each client tick (only an on-ground cell change does any work), reads the
 * world's trail.json at join and writes it every trail.saveS when changed, notes the fork's saved routes (IPricedPath
 * routeCounts, read by name: rule 7 "not readable in this fork jar"), and prices one level up from Baritone's own costs
 * ({@link #climb}). Loader notes: vanilla LocalPlayer#blockPosition / onGround, Level block states and
 * ItemStack#getDestroySpeed through GameTimesLive; Baritone's ActionCosts and Settings; BotFiles for the file. Client
 * thread only; never throws.
 */
public final class TrailRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private TrailRuntime() {}

    private static BlockPos lastFeet;
    private static long lastSave;
    private static long forkSaved = -1;
    private static boolean forkSaidMissing;
    private static long probeLast = -1;

    /** The board's trails (null: no AA runtime yet). */
    static Trails trails() {
        Commands c = Core.INSTANCE.commands;
        return c == null || c.aaRuntime == null ? null : Trails.of(c.aaRuntime.bb);
    }

    /** The trail of the dimension the bot is in (null: no world or no runtime). */
    static Trail here() {
        Trails t = trails();
        var lv = Minecraft.getInstance().level;
        return t == null || lv == null ? null : t.of(io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv));
    }

    public static void tick(long tick) {
        try {
            tickInner(tick);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] trail: the recorder failed this tick: {}", e.toString());
        }
    }

    private static void tickInner(long tick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Trails ts = trails();
        if (p == null || mc.level == null || ts == null) return;
        String key = Core.INSTANCE.worldKey();
        var files = Core.INSTANCE.files();
        if (key == null || files == null) return;
        if (!ts.loaded() || !key.equals(ts.world())) {
            LOG.info("[entropybot] trail: {}", ts.load(key, files.readJson(Trails.FILE)));
            lastFeet = null;
        }
        Trail t = ts.of(io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level));
        t.loadedEmpty();
        noteForkRoute(t);
        if (p.onGround()) {
            BlockPos f = p.blockPosition();
            if (!f.equals(lastFeet)) {
                lastFeet = f;
                boolean out = Jobs.belowSurface(mc.level, f.getX(), f.getY(), f.getZ()) == 0;
                int before = t.segs().size();
                String line = t.offer(new Trail.Cell(f.getX(), f.getY(), f.getZ()), tick, out, Tuning.i("trail.jumpBlocks"));
                if (line != null) LOG.info("[entropybot] {}", line);
                if (probe() && t.segs().size() > before) {
                    var s = t.segs().get(t.segs().size() - 1);
                    LOG.info("[entropybot] probe trail: segment #{} {} -> {} {} {} s{} | {} segments", t.segs().size(), s.from().text(), s.to().text(),
                            s.oneWay() ? "ONE_WAY drop " + s.drop() : "WALK", Math.round(s.walkedS()), s.forkRoute() ? " (a fork route)" : "", t.segs().size());
                }
            }
        }
        if (probe() && tick - probeLast >= 20L * 60) {          // structural: one count line a minute (ticks)
            probeLast = tick;
            LOG.info("[entropybot] probe trail: {}", counts(t));
        }
        if (ts.dirty() && tick - lastSave >= 20L * Tuning.get("trail.saveS")) {   // structural: 20 ticks a second
            lastSave = tick;
            String r = files.writeJson(Trails.FILE, ts.save());
            if (r.startsWith("ok")) ts.written();
            else LOG.warn("[entropybot] trail: couldn't write {}: {}", Trails.FILE, r);
        }
    }

    static boolean probe() { return Tuning.get("debug.trailProbe") > 0; }

    /** "recorded N, chosen N, walked N, breaks N | OPEN, N segments". */
    static String counts(Trail t) {
        return "segments recorded " + t.recorded + ", trail chosen " + t.chosen + ", segments walked " + t.walked + ", breaks " + t.breaks
                + " | " + t.state() + ", " + t.segs().size() + " segments" + (t.broken() == null ? "" : ", " + t.broken());
    }

    /** D11 build 2: a route the fork saved while a walk segment was walked marks that segment's fork route. */
    private static void noteForkRoute(Trail t) {
        var b = Jobs.baritone();
        if (b == null) return;
        long[] r = ProbeLines.routeCounts(b.getPricedPath());
        if (r == null) {
            if (!forkSaidMissing) { forkSaidMissing = true; LOG.info("[entropybot] trail: the fork's saved routes are not readable in this fork jar; segments are recorded by the mod alone"); }
            return;
        }
        long saved = r[4];                                  // structural: {asked, reused, changed, not loaded, saved}
        if (forkSaved >= 0 && saved > forkSaved) t.forkSaved();
        forkSaved = saved;
    }

    /**
     * One level up in the chain's seconds, from Baritone's own costs: a pillar level (JUMP_ONE_BLOCK_COST + the walk lease's
     * blockPlacementPenalty, walk.placePenalty) and a dug stair level (ReflexGoals.digLevelTicks over the dearest of the
     * cells round the feet as Baritone prices a break: the best hotbar item's mining ticks + blockBreakAdditionalPenalty).
     */
    static Trail.Climb climb(LocalPlayer p) {
        double pillar = TickPrice.walkS(baritone.api.pathing.movement.ActionCosts.JUMP_ONE_BLOCK_COST + Tuning.get("walk.placePenalty"));
        double stair = Double.NaN;
        try {
            var lv = p.level();
            BlockPos f = p.blockPosition();
            double penalty = baritone.api.BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value;
            double dearest = 0;
            for (BlockPos q : new BlockPos[]{f.above(2), f.north().above(), f.south().above(), f.east().above(), f.west().above()}) {
                if (!lv.isLoaded(q)) continue;
                var st = lv.getBlockState(q);
                if (st.getCollisionShape(lv, q).isEmpty() || st.getDestroySpeed(lv, q) < 0) continue;
                double best = GameTimesLive.breakSeconds(p, st, net.minecraft.world.item.ItemStack.EMPTY);
                for (int i = 0; i < net.minecraft.world.entity.player.Inventory.getSelectionSize(); i++) {
                    var it = p.getInventory().getItem(i);
                    if (!it.isEmpty()) best = Math.min(best, GameTimesLive.breakSeconds(p, st, it));
                }
                dearest = Math.max(dearest, best * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS + penalty);
            }
            if (!Double.isInfinite(dearest)) stair = TickPrice.walkS(io.github.mojolowjo.entropybot.aa.moves.ReflexGoals.digLevelTicks(dearest));
        } catch (RuntimeException | LinkageError e) {
            LOG.info("[entropybot] trail: the stair level price was not read ({}); the pillar price alone", e.toString());
        }
        return new Trail.Climb(pillar, stair);
    }
}
