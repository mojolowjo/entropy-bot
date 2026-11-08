package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
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
 * Baritone goal - the tunnel's end, capped at the loaded edge and moved on as chunks load (job-tunnel2) - walked on the
 * fork's priced path into that cell under the dig leg's lease (TRAVEL intent, the band leg's priceInto / walkInto): Baritone
 * picks every move and break (rules 2, 7); the mod never names a cell to break. Every short end holds the heading from
 * its start (FactChange.tunnelHeldFact, a source fact). Each tick:
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
        /** job-tunnel2: the walked goal - the end, or the loaded edge short of it - and whether Baritone walks it now. */
        int[] goal;
        boolean walking;
        long lines0;
        /** job-tunnel3: legs walked to their goal; this leg's most blocks; whether this leg is the short retry. */
        int legs, legMax;
        boolean retried;
    }

    /**
     * Job-tunnel2 (run JAT 05:14:13-15: a plain GoalBlock 51 blocks through rock, Baritone's own search, ended in 2 s at the
     * start with nothing broken; the dig leg before it went 46 levels on the fork's PRICED path): the goal - capped at the
     * loaded edge - is priced into its cell (LivePathPrice.priceInto, the band leg's ask) and walked on that priced path
     * (walkInto) under the dig leg's TRAVEL intent; computing waits, a no-path or a not-known answer holds the heading.
     */
    private static Car.Status walkGoal(State s, IBaritone b, int[] me, long t) {
        io.github.mojolowjo.entropybot.aa.PathPrice.Answer a = LivePathPrice.INSTANCE.priceInto(s.goal[0], s.goal[1], s.goal[2],
                Math.abs(s.goal[0] - me[0]) + Math.abs(s.goal[2] - me[2]));
        var next = io.github.mojolowjo.entropybot.aa.moves.BandLeg.of(a, me, true);
        switch (next.act()) {
            case WALK -> {
                io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.aa.moves.BandLeg.INTENT, "tunnel");
                s.lines0 = io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines();
                LivePathPrice.walkInto(b, new BlockPos(s.goal[0], s.goal[1], s.goal[2]));
                s.walking = true;
                s.goalAt = t;
                LOG.info("[entropybot] tunnel: {} to {}: {}", s.h.name(), Jobs.fmt(s.goal), next.why());
                return Car.Status.running("tunnel " + s.h.name() + ": " + next.why() + " to " + Jobs.fmt(s.goal));
            }
            case WAIT -> { return Car.Status.running("tunnel " + s.h.name() + ": the path to " + Jobs.fmt(s.goal) + " is " + next.why()); }
            default -> { return null; }                                   // FACT / NOT_KNOWN: the caller holds the heading with these words
        }
    }

    /** job-tunnel2: the fork's last calc, as WalkEndText reads it (NONE: an older fork or none kept). */
    private static io.github.mojolowjo.entropybot.aa.moves.WalkEndText.Calc forkCalc(IBaritone b) {
        try {
            var wc = b.getCustomGoalProcess().lastCalc();
            return wc == null ? io.github.mojolowjo.entropybot.aa.moves.WalkEndText.Calc.NONE
                    : new io.github.mojolowjo.entropybot.aa.moves.WalkEndText.Calc(true, wc.exhausted(), wc.budget(), String.valueOf(wc.stop()));
        } catch (LinkageError e) {
            return io.github.mojolowjo.entropybot.aa.moves.WalkEndText.Calc.NONE;
        }
    }

    /** job-tunnel3: the leg's goal from {@code from} toward the end, at most s.legMax blocks, capped at the loaded edge (Tunnel.legSteps); its steps (0: none loaded). */
    private static int capGoal(State s, Level lv, int[] from) {
        int left = Math.abs(s.end[0] - from[0]) + Math.abs(s.end[2] - from[2]);
        int steps = Tunnel.legSteps(left, s.legMax, i -> lv.isLoaded(new BlockPos(from[0] + s.h.dx() * i, from[1], from[2] + s.h.dz() * i))
                && lv.isLoaded(new BlockPos(from[0] + s.h.dx() * i, from[1] + 1, from[2] + s.h.dz() * i)));
        s.goal = new int[]{from[0] + s.h.dx() * steps, s.end[1], from[2] + s.h.dz() * steps};
        s.walking = false;
        return steps;
    }

    /**
     * job-tunnel3: a leg that ended short (timed out, stopped, no priced path) is retried once from the feet with its goal
     * tunnel.shortLegBlocks ahead (Tunnel.shortLeg); null: this was the retry, the caller holds the heading.
     */
    private static Car.Status retryShort(State s, Level lv, int[] me, String why) {
        if (Tunnel.shortLeg(s.retried) == Tunnel.ShortLeg.HOLD) return null;
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        s.retried = true;
        s.legMax = Tuning.i("tunnel.shortLegBlocks");
        capGoal(s, lv, me);
        LOG.info("[entropybot] tunnel: leg {} ended short: {}; retried once from {} with tunnel.shortLegBlocks {} to {}", s.legs + 1, why, Jobs.fmt(me), s.legMax, Jobs.fmt(s.goal));
        return Car.Status.running("tunnel " + s.h.name() + ": the leg ended short, retried " + s.legMax + " blocks ahead");
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
        s.known.addAll(AIR_SEEN);                                                  // job-caveair: air already told is one fact, never again
        // job-caveair (run JBA: the feet read y 15 a tick after the start at y 16, so air 4 below the start counted as new):
        // the start's air is read one block past the sight box every way
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dy = -r - 1; dy <= r + 1; dy++) for (int dz = -r - 1; dz <= r + 1; dz++)   // structural: one block of margin
            if (lv.getBlockState(new BlockPos(me[0] + dx, me[1] + dy, me[2] + dz)).isAir()) s.known.add(Tunnel.key(me[0] + dx, me[1] + dy, me[2] + dz));
        for (int i = 1; i <= t.length(); i++) for (int dy = 0; dy <= 1; dy++) {      // structural: the 1 x 2 line, counted at the end
            int[] c = {me[0] + h.dx() * i, me[1] + dy, me[2] + h.dz() * i};
            BlockPos q = new BlockPos(c[0], c[1], c[2]);
            if (lv.isLoaded(q) && !lv.getBlockState(q).isAir()) s.solidAtStart.add(c);
        }
        done.add(h.name());
        s.legMax = Tuning.i("tunnel.legBlocks");
        int steps = capGoal(s, lv, me);
        if (steps == 0) {
            String w = "the first block " + h.name() + " of " + Jobs.fmt(me) + " is not loaded (rule 15: not known)";
            FactChange.set(FactChange.tunnelHeldFact(me, h.name(), w));
            return "error: " + t.text() + ": " + Tunnel.heldText(h.name(), me, w);
        }
        st = s;
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.aa.moves.BandLeg.INTENT, "tunnel");   // the dig leg's intent and lease
        LOG.info("[entropybot] tunnel: {} {} blocks from {} to {} for {} {} ore (Baritone's priced path into the cell, breaks under the dig leg's TRAVEL lease), in legs of tunnel.legBlocks {}, the first to {} - heading {}: {}",
                h.name(), t.length(), Jobs.fmt(me), Jobs.fmt(s.end), t.n(), s.fam, s.legMax, Jobs.fmt(s.goal), h.name(), why);
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
            s.walking = false;                                                    // the walk goes on: priced again from here
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
            if (cave != null && !handAir(s, lv, cave)) cave = null;               // job-caveair: a pocket - the tunnel goes on
        }
        boolean atEnd = me[0] == s.end[0] && me[1] == s.end[1] && me[2] == s.end[2];
        boolean atGoal = me[0] == s.goal[0] && me[1] == s.goal[1] && me[2] == s.goal[2];
        if (ore == null && fluid == null && cave == null && !atEnd) {
            if (atGoal && s.walking) {
                // job-tunnel3: the leg reached its goal - the next leg starts from the feet
                Jobs.cancel(b);
                s.legs++;
                s.retried = false;
                s.legMax = Tuning.i("tunnel.legBlocks");
                s.walking = false;
                int left = Math.abs(s.end[0] - me[0]) + Math.abs(s.end[2] - me[2]);
                LOG.info("[entropybot] tunnel: leg {} reached {}, {} block(s) to the end", s.legs, Jobs.fmt(me), left);
            }
            if (!s.walking) {
                if (capGoal(s, lv, me) == 0) return Car.Status.running("tunnel " + s.h.name() + ": at the loaded edge " + Jobs.fmt(me) + ", the next block not loaded yet");
                Car.Status w = walkGoal(s, b, me, t);
                if (w != null) return w;
                Car.Status r = retryShort(s, lv, me, "no priced path to " + Jobs.fmt(s.goal));
                if (r != null) return r;
                var a = LivePathPrice.INSTANCE.priceInto(s.goal[0], s.goal[1], s.goal[2], 0);
                String why = "no path along it to " + Jobs.fmt(s.goal) + " (" + io.github.mojolowjo.entropybot.aa.moves.BandLeg.of(a, me, true).why() + ")";
                FactChange.set(FactChange.tunnelHeldFact(s.from, s.h.name(), why));
                return end(p, false, Tunnel.heldText(s.h.name(), s.from, why));
            }
        }
        boolean stopped = s.walking && !b.getCustomGoalProcess().isActive() && t - s.goalAt >= 20 && !Core.INSTANCE.reflexes.hold();   // structural: a second for the goal to take
        if (stopped && ore == null && fluid == null && cave == null && !atEnd) {
            String w = "Baritone stopped short at " + Jobs.fmt(me) + " of " + Jobs.fmt(s.goal) + " (the fork: "
                    + io.github.mojolowjo.entropybot.aa.moves.WalkEndText.reason(forkCalc(b), s.lines0, io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines(),
                            io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine()) + ")";
            Car.Status r = retryShort(s, lv, me, w);
            if (r != null) return r;
        }
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
                    s.walking = false;
                    return Car.Status.running("tunnel: the vein at " + Jobs.fmt(ore) + " did not start");
                }
                LOG.info("[entropybot] tunnel: {}", next.why());
                return Car.Status.running("tunnel: mining the seen ore at " + Jobs.fmt(ore));
            }
            case END_FACT -> {
                // job-tunnel2 (run JAT): every short end holds this heading from its start - a source fact, never a price fact
                // (a failed step's price fact re-priced against the running pick, "kept the running pick", and stopped the order)
                String why = stopped && fluid == null && cave == null
                        ? "Baritone stopped short at " + Jobs.fmt(me) + " of " + Jobs.fmt(s.goal) + " (the fork: "
                          + io.github.mojolowjo.entropybot.aa.moves.WalkEndText.reason(forkCalc(b), s.lines0, io.github.mojolowjo.entropybot.baritone.BaritoneHook.lines(),
                                io.github.mojolowjo.entropybot.baritone.BaritoneHook.lastLine()) + ")"
                        : next.why();
                if (cave != null && fluid == null) {
                    // job-caveair: cave air is a price fact (the index knows the cave now), never a held heading
                    FactChange.set(FactChange.found(why));
                    return end(p, false, why);
                }
                FactChange.set(FactChange.tunnelHeldFact(s.from, s.h.name(), why));
                return end(p, false, Tunnel.heldText(s.h.name(), s.from, why));
            }
            case DONE -> { s.legs++; return end(p, true, next.why()); }
            default -> { return Car.Status.running("tunnel " + s.h.name() + " to " + Jobs.fmt(s.end) + " (" + s.mined + " of " + s.step.n() + " ore)"); }
        }
    }

    /** Job-caveair: the air cells a tunnel already told (cave or pocket) - one fact each, never another heading's end. */
    private static final Set<Long> AIR_SEEN = new HashSet<>();

    /**
     * Job-caveair: the air at {@code c} is handed to the cave index at once (CaveIndex.lipCredit: the tunnel is its lip).
     * A cave is stored as a known cave (Sightings.knowCave) and the tunnel ends (true); a pocket is said and the tunnel goes
     * on (false). Either way the air cell and the cells of a local flood from it are remembered (AIR_SEEN).
     */
    private static boolean handAir(State s, Level lv, int[] c) {
        long min = Tuning.i("tunnel.caveMinCells");
        Set<Long> flood = new HashSet<>();
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(c);
        flood.add(Tunnel.key(c[0], c[1], c[2]));
        while (!q.isEmpty() && flood.size() <= min) {
            int[] a = q.poll();
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int x = a[0] + d[0], y = a[1] + d[1], z = a[2] + d[2];
                BlockPos bp = new BlockPos(x, y, z);
                long kk = Tunnel.key(x, y, z);
                if (s.known.contains(kk) || !lv.isLoaded(bp) || !lv.getBlockState(bp).isAir() || !flood.add(kk)) continue;   // the tunnel's own air is not the pocket's
                q.add(new int[]{x, y, z});
            }
        }
        AIR_SEEN.addAll(flood);
        s.known.addAll(flood);
        var pass = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE;
        var idx = pass.caveIndex();
        var lip = idx == null ? new io.github.mojolowjo.entropybot.sense.CaveIndex.Lip(io.github.mojolowjo.entropybot.sense.CaveIndex.Credit.NO_VOLUME, 0, null)
                : idx.lipCredit(c[0], c[1], c[2], min);
        Tunnel.Air a = Tunnel.air(lip.what(), flood.size(), min);
        long n = lip.what() == io.github.mojolowjo.entropybot.sense.CaveIndex.Credit.NO_VOLUME ? flood.size() : lip.cells();
        switch (a) {
            case POCKET -> {
                LOG.info("[entropybot] tunnel: {} (the cave index: {}; tunnel.caveMinCells {})", Tunnel.pocketText(c, n), lip.what(), min);
                return false;
            }
            case CAVE -> {
                var k = lip.known();
                if (k != null && pass.sightings() != null && pass.sightings().knowCave(s.dim, k, System.currentTimeMillis()) != null)
                    LOG.info("[entropybot] sense: cave known at {} {} {} (size {}, depth {}; a tunnel broke into it from {}, {} cells)", k.x(), k.y(), k.z(), k.size(), k.depth(), Jobs.fmt(s.from), n);
                else LOG.info("[entropybot] tunnel: air at {} is a cave the index knows ({}, {} cells)", Jobs.fmt(c), lip.what(), n);
                return true;
            }
            default -> {
                LOG.info("[entropybot] tunnel: air at {} ({} cells or more) is not in the cave index (rule 15: not known); the tunnel ends for a re-price", Jobs.fmt(c), n);
                return true;
            }
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
            line = Tunnel.endLine(why, broken, faces, s.oreSeen.size(), s.mined, s.fam, s.legs);
        }
        LOG.info("[entropybot] tunnel: ended: {}", line);
        return ok ? Car.Status.done("ok: " + line) : Car.Status.failed("tunnel: " + line);
    }

    private static String id(Level lv, BlockPos q) { return BuiltInRegistries.BLOCK.getKey(lv.getBlockState(q).getBlock()).toString(); }
}
