package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.cave.Tunnel;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.sense.KnownOre;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Job-tunnel (run JAS): the band way's tunnel step in the game (Step.Tunnel; the rules are cave.Tunnel's, pure). One
 * Baritone goal - a GoalBlock at the tunnel's end - under the ordinary dig lease (the band leg's TRAVEL intent): Baritone
 * picks every move and break (rules 2, 7); the mod never names a cell to break. Each tick:
 * <ul>
 * <li>a seen ore of the family within the mine's sight (Step.Collect.MINE_SIGHT, the fair filter) pauses the walk and the
 * vein mine (VeinMine, Baritone's MineProcess) takes it, as the caving step does since D15 pass three;</li>
 * <li>air round the feet (tunnel.caveSeeM) that was not air at the start and is not a cell of Baritone's own path ends the
 * tunnel: "cave air at x y z: the cave step takes over" - a price fact, the order re-plans with the new cave;</li>
 * <li>water or lava ahead (tunnel.fluidAheadM) ends it with the reason, its heading held for the order.</li>
 * </ul>
 * The end line says blocks broken, faces exposed, ore seen and mined. Loader notes: Baritone API (GoalBlock, the path's
 * positions), vanilla Level reads, the client thread only.
 */
final class TunnelStep {
    private static final Logger LOG = LogUtils.getLogger();

    private TunnelStep() {}

    /** The headings tunnelled from near a start (key: the start's 8-block cell), held for the order's re-plans. */
    private static final Map<Long, Set<String>> DONE = new HashMap<>();

    private static final class State {
        Step.Tunnel step;
        String dim, fam;
        Tunnel.Heading h;
        int[] from, end;
        final Set<Long> known = new HashSet<>();
        final List<int[]> solidAtStart = new ArrayList<>();
        final Set<Long> oreSeen = new HashSet<>();
        final Set<Long> veinFailed = new HashSet<>();
        int mined;
        VeinMine vein;
        List<int[]> veinCells;
        long goalAt = -1;
        long airAt = -1;
    }

    private static State st;

    private static long doneKey(int[] c) { return Tunnel.key(c[0] >> 3, c[1] >> 3, c[2] >> 3); }   // structural: an 8-block cell

    static String start(LocalPlayer p, Step.Tunnel t) {
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        String dim = pass.dim();
        if (pass.sightings() == null || dim == null) return "error: " + t.text() + ": the sightings are not loaded yet (rule 15)";
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: " + t.text() + ": baritone not loaded";
        int[] me = Jobs.here(p);
        Set<String> done = DONE.computeIfAbsent(doneKey(me), k -> new HashSet<>());
        Tunnel.Heading h;
        String why;
        if (t.heading() != null) {
            h = Tunnel.heading(t.heading());
            why = "the chain's heading";
            if (h == null) return "error: " + t.text() + ": no heading " + t.heading();
        } else {
            var notes = Core.INSTANCE.mineNotes;
            var pk = Tunnel.pick(me, t.length(), Tuning.i("tunnel.unseenReachM"), (cx, cz) -> notes.explored(ExploreRules.key(dim, cx, cz)), done, null, null);
            if (pk.heading() == null) {
                FactChange.exploreHere(me, pk.why());
                return "error: " + t.text() + ": " + pk.why();
            }
            h = pk.heading();
            why = pk.why();
        }
        State s = new State();
        s.step = t;
        s.dim = dim;
        s.fam = KnownOre.familyOf(t.item());
        s.h = h;
        s.from = me.clone();
        s.end = Tunnel.end(me, h, t.length());
        Level lv = p.level();
        int r = Tuning.i("tunnel.caveSeeM");
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++)
            if (lv.getBlockState(new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz)).isAir()) s.known.add(Tunnel.key(me[0] + dx, me[1] + dy, me[2] + dz));
        for (int i = 1; i <= t.length(); i++) for (int dy = 0; dy <= 1; dy++) {      // structural: the 1 x 2 line, counted at the end
            int[] c = {me[0] + h.dx() * i, me[1] + dy, me[2] + h.dz() * i};
            BlockPos q = new BlockPos(c[0], c[1], c[2]);
            if (lv.isLoaded(q) && !lv.getBlockState(q).isAir()) s.solidAtStart.add(c);
        }
        st = s;
        done.add(h.name());
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "tunnel");
        b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(s.end[0], s.end[1], s.end[2]));
        s.goalAt = Core.INSTANCE.tick();
        LOG.info("[entropybot] tunnel: {} {} blocks from {} to {} for {} {} ore (GoalBlock; Baritone digs) - heading {}: {}", h.name(), t.length(), Jobs.fmt(me), Jobs.fmt(s.end), t.n(), s.fam, h.name(), why);
        return null;
    }

    static Car.Status status(LocalPlayer p) {
        State s = st;
        if (s == null) return Car.Status.failed("the tunnel step has no state");
        IBaritone b = Jobs.baritone();
        if (b == null) return end(p, false, "baritone not loaded");
        Level lv = p.level();
        int[] me = Jobs.here(p);
        if (s.vein != null) {
            Car.Status vs = s.vein.poll(p);
            if (vs.state() == Car.State.RUNNING) return Car.Status.running("tunnel: mining the seen ore (" + s.mined + " of " + s.step.n() + ")");
            for (int[] c : s.veinCells) {
                BlockPos q = new BlockPos(c[0], c[1], c[2]);
                if (lv.isLoaded(q) && !KnownOre.family(id(lv, q), s.fam)) s.mined++;
            }
            if (vs.state() == Car.State.FAILED) s.veinFailed.add(Tunnel.key(s.veinCells.get(0)[0], s.veinCells.get(0)[1], s.veinCells.get(0)[2]));
            if (vs.state() == Car.State.FAILED) LOG.info("[entropybot] tunnel: the vein at {} ended: {}", Jobs.fmt(s.veinCells.get(0)), vs.why());
            s.vein = null;
            s.veinCells = null;
            if (s.mined >= s.step.n()) return end(p, true, "the need is met");
            b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(s.end[0], s.end[1], s.end[2]));   // the walk goes on
            s.goalAt = Core.INSTANCE.tick();
        }
        // the cells Baritone's path will open are the tunnel's own, never cave air
        try {
            var cur = b.getPathingBehavior().getCurrent();
            if (cur != null) for (var q : cur.getPath().positions()) for (int dy = 0; dy <= 2; dy++) s.known.add(Tunnel.key(q.getX(), q.getY() + dy, q.getZ()));
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("[entropybot] tunnel: the path's cells are not readable: {}", e.toString());
        }
        for (int dy = 0; dy <= 2; dy++) s.known.add(Tunnel.key(me[0], me[1] + dy, me[2]));
        // the facts of this tick: a seen ore of the family within the mine's sight (its vein), water or lava ahead, cave air
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        int[] ore = null;
        List<Pos> oreVein = new ArrayList<>();
        List<int[]> oreCells = new ArrayList<>();
        for (var hit : pass.nearest(s.dim, id -> true, me[0], me[1], me[2], Step.Collect.MINE_SIGHT, 8, (x, y, z) -> Mining.fairOk(s.dim, x, y, z))) {   // structural: a page of hits
            if (hit.family() == null || !KnownOre.family(hit.id(), s.fam)) continue;
            s.oreSeen.add(Tunnel.key(hit.x(), hit.y(), hit.z()));
            if (ore != null || s.veinFailed.contains(Tunnel.key(hit.x(), hit.y(), hit.z()))) continue;   // its vein failed with the ore still there: left out (rule 7)
            int[] cell = {hit.x(), hit.y(), hit.z()};
            for (int[] c : io.github.mojolowjo.entropybot.cave.MineRules.vein(cell, n -> {
                BlockPos q = new BlockPos(n[0], n[1], n[2]);
                return lv.isLoaded(q) && KnownOre.family(id(lv, q), s.fam);
            }, io.github.mojolowjo.entropybot.cave.MineRules.atVein())) { oreVein.add(new Pos(c[0], c[1], c[2])); oreCells.add(c); }
            if (!oreVein.isEmpty()) ore = cell;
        }
        String fluid = null;
        int ahead = Tuning.i("tunnel.fluidAheadM");
        for (int i = 1; i <= ahead && fluid == null; i++) for (int dy = 0; dy <= 1 && fluid == null; dy++) {
            BlockPos q = new BlockPos(me[0] + s.h.dx() * i, me[1] + dy, me[2] + s.h.dz() * i);
            if (!lv.isLoaded(q)) continue;
            var fl = lv.getFluidState(q);
            if (fl.isEmpty()) continue;
            fluid = (BuiltInRegistries.FLUID.getKey(fl.getType()).getPath().contains("lava") ? "lava" : "water") + " ahead at " + q.getX() + " " + q.getY() + " " + q.getZ();
        }
        long t = Core.INSTANCE.tick();
        int[] cave = null;
        if (s.airAt < 0 || t - s.airAt >= 20) {                                   // structural: once a second in ticks
            s.airAt = t;
            int r = Tuning.i("tunnel.caveSeeM");
            List<int[]> air = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                BlockPos q = new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz);
                if (lv.isLoaded(q) && lv.getBlockState(q).isAir()) air.add(new int[]{q.getX(), q.getY(), q.getZ()});
            }
            cave = Tunnel.caveAir(air, s.known);
        }
        boolean atEnd = me[0] == s.end[0] && me[1] == s.end[1] && me[2] == s.end[2];
        boolean stopped = !b.getCustomGoalProcess().isActive() && t - s.goalAt >= 20 && !Core.INSTANCE.reflexes.hold();   // structural: a second for the goal to take
        var next = Tunnel.next(ore, fluid, cave, atEnd, stopped, s.h, s.end, me);
        switch (next.act()) {
            case MINE -> {
                Jobs.cancel(b);
                s.vein = new VeinMine(oreVein, "tunnel: mine " + s.fam + " at " + Jobs.fmt(ore));
                s.veinCells = oreCells;
                String bad = s.vein.begin(lv);
                if (bad != null) {
                    s.vein = null;
                    s.veinCells = null;
                    s.veinFailed.add(Tunnel.key(ore[0], ore[1], ore[2]));
                    LOG.info("[entropybot] tunnel: the vein at {} did not start: {}", Jobs.fmt(ore), bad);
                    b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(s.end[0], s.end[1], s.end[2]));
                    s.goalAt = t;
                    return Car.Status.running("tunnel: the vein at " + Jobs.fmt(ore) + " did not start");
                }
                LOG.info("[entropybot] tunnel: {}", next.why());
                return Car.Status.running("tunnel: mining the seen ore at " + Jobs.fmt(ore));
            }
            case END_FACT -> {
                FactChange.set(FactChange.priced(next.why()));
                return end(p, false, next.why());
            }
            case DONE -> { return end(p, true, next.why()); }
            default -> { return Car.Status.running("tunnel " + s.h.name() + " to " + Jobs.fmt(s.end) + " (" + s.mined + " of " + s.step.n() + " ore)"); }
        }
    }

    static void cancel(String why) {
        State s = st;
        if (s == null) return;
        if (s.vein != null) s.vein.stop();
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        LOG.info("[entropybot] tunnel: stopped: {}", why);
        st = null;
    }

    private static Car.Status end(LocalPlayer p, boolean ok, String why) {
        State s = st;
        st = null;
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        if (s != null && s.vein != null) s.vein.stop();
        int broken = 0, faces = 0;
        String line = why;
        if (s != null) {
            Level lv = p.level();
            Set<Long> open = new HashSet<>();
            for (int[] c : s.solidAtStart) {
                BlockPos q = new BlockPos(c[0], c[1], c[2]);
                if (lv.isLoaded(q) && lv.getBlockState(q).isAir()) { broken++; open.add(Tunnel.key(c[0], c[1], c[2])); }
            }
            Set<Long> faced = new HashSet<>();
            for (int[] c : s.solidAtStart) {
                if (!open.contains(Tunnel.key(c[0], c[1], c[2]))) continue;
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                    BlockPos q = new BlockPos(x, y, z);
                    if (lv.isLoaded(q) && !lv.getBlockState(q).isAir()) faced.add(Tunnel.key(x, y, z));
                }
            }
            faces = faced.size();
            line = Tunnel.endLine(why, broken, faces, s.oreSeen.size(), s.mined, s.fam);
        }
        LOG.info("[entropybot] tunnel: ended: {}", line);
        return ok ? Car.Status.done("ok: " + line) : Car.Status.failed("tunnel: " + line);
    }

    private static String id(Level lv, BlockPos q) { return BuiltInRegistries.BLOCK.getKey(lv.getBlockState(q).getBlock()).toString(); }
}
