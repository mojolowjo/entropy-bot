package io.github.mojolowjo.entropybot.ghost;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.ghost.GhostLedger.Outcome;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Kind;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Point;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Reason;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Verdict;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Dry-run package 3 (plan "Dry-run checker plan (ghost body)", step one: report only): whenever Baritone starts a new
 * path, a ghost of the player ({@link GhostPlayer}) walks it with the game's own physics, steered by {@link Pilot}, and
 * {@link PathCheck} says "pass" or "refuse" with the first reason. Meanwhile the real walk is watched: it arrived when the
 * player's feet reach the path's last point while that path is Baritone's current one, and failed when Baritone drops or
 * replaces the path first. Each path becomes one {@link GhostLedger.Row}; a disagreement (ghost pass and the real walk
 * failed, or the other way round) leaves a recorder incident (the blocks around the bot) and a full log line with both
 * tracks. Checks run under {@link CheckBudget} and never make the real bot wait; a path not judged in time is
 * "unchecked", never "pass".
 *
 * <p>Before stepping, the path's body blocks are read: a closed door or gate (Baritone opens it; the ghost cannot) makes
 * it "unchecked (door)", a block taller than a slab in the body space "refuse: needs a block broken" (plan step 4).
 *
 * <p>The ghost never presses a key or turns the camera: {@link GhostPlayer#aim} is its own facing (MoversTest). An
 * exception or error inside marks the path "unchecked (error)", logs it once in full and never reaches the real bot.
 *
 * <p>Pure: no (the game side; the logic is {@link Pilot}, {@link PathCheck}, {@link CheckBudget}, {@link GhostLedger}, all
 * with JUnit). Loader notes: Baritone API ({@code IPathingBehavior.getCurrent}, {@code IPath.positions/movements}); the
 * move kind from the movement's class name; vanilla blocks and tags; ticked from NeoForge {@code ClientTickEvent.Post}
 * (wired in {@code EntropyBot}; Fabric: {@code ClientTickEvents.END_CLIENT_TICK}); incidents through the mod's recorder.
 */
public final class GhostChecker {
    public static final GhostChecker INSTANCE = new GhostChecker();
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    static final int OPEN_MAX = 4;                 // structural: paths judged at once (the current one and those it replaced)

    private final GhostLedger ledger = new GhostLedger();
    private final List<Job> open = new ArrayList<>();
    private IPath current;
    private String lastPathEvent = "";
    private int incidents;
    private boolean errorLogged;
    private long errors;
    private int gameTick;

    private GhostChecker() {}

    /** On when {@code ghost.check} is 1 and the ghost's accessors applied. */
    public static boolean active() {
        return Tuning.get("ghost.check") >= 1 && GhostPlayer.ready();
    }

    /** Baritone's path events (via {@code BaritoneHook}): the last one names why a path ended. */
    public void pathEvent(String name) { lastPathEvent = name; }

    /** Baritone path events that hand the walk to a next segment rather than drop it. */
    static boolean handoff(String event) {
        return event.startsWith("NEXT_SEGMENT") || event.startsWith("SPLICING") || event.startsWith("CONTINUING")
                || event.equals("PATH_FINISHED_NEXT_STILL_CALCULATING");   // run 2, fence lane path 13
    }

    public String status() {
        String head = active() ? "ghost checker on (leak guard: overrides, no injection)" : GhostPlayer.ready() ? "ghost checker off (ghost.check 0)" : "ghost OFF (accessor mixins missing)";
        return head + "; " + ledger.status() + "; open " + open.size() + "; incidents " + incidents;
    }

    /** The last {@code n} paths, newest last. */
    public String last(int n) {
        List<GhostLedger.Row> r = ledger.recent();
        if (r.isEmpty()) return "no path judged yet";
        StringBuilder b = new StringBuilder();
        for (GhostLedger.Row row : r.subList(Math.max(0, r.size() - n), r.size())) b.append(b.length() == 0 ? "" : "\n").append(row.line());
        return b.toString();
    }

    /** Once per client tick (ClientTickEvent.Post). Never throws. */
    public void tick() {
        gameTick++;
        if (!active()) {
            dropAll("checker off");
            current = null;
            return;
        }
        long t0 = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            ClientLevel level = mc.level;
            IBaritone b = io.github.mojolowjo.entropybot.baritone.SafetyNet.primary();
            if (p == null || level == null || b == null) {
                dropAll("no player, level or Baritone");
                current = null;
                return;
            }
            IPathExecutor ex = b.getPathingBehavior().getCurrent();
            IPath path = ex == null ? null : ex.getPath();
            BlockPos feet = feet(p.getX(), p.getY(), p.getZ());
            for (Job j : open)
                if (j.outcome == null && j.path == current && feet.equals(j.dest)) j.real(Outcome.ARRIVED, "at " + str(feet));
            if (path != current) {
                for (Job j : open)
                    if (j.outcome == null) {
                        // package 3 run 1 (fence lane paths 13, 15): a segment handoff is not a failed walk.
                        if (handoff(lastPathEvent)) j.real(Outcome.UNKNOWN, "handed to the next segment after " + lastPathEvent);
                        else j.real(Outcome.FAILED, "Baritone left it at " + str(feet) + (lastPathEvent.isEmpty() ? "" : " after " + lastPathEvent));
                    }
                lastPathEvent = "";   // review: consumed, so a later drop is not judged by an old handoff
                current = path;
                if (path != null) start(path, p, level);
            }
            CheckBudget budget = new CheckBudget(Tuning.i("ghost.ticksPerTick"), Tuning.get("ghost.msPerTick"), Tuning.i("ghost.deadlineTicks"));
            int steps = 0;
            for (Job j : open) {
                if (j.verdict != null) continue;
                if (budget.late(gameTick - j.startTick)) {
                    j.judged(Verdict.unchecked(Reason.BUDGET, j.check.ticks() + " ghost ticks in " + (gameTick - j.startTick) + " game ticks"));
                    j.ghost = null;
                    continue;
                }
                while (j.verdict == null && budget.mayStep(steps, (System.nanoTime() - t0) / 1e6)) {
                    j.step(level);
                    steps++;
                }
            }
            for (Iterator<Job> it = open.iterator(); it.hasNext(); ) {
                Job j = it.next();
                if (j.verdict != null && j.outcome != null) {
                    close(j);
                    it.remove();
                }
            }
            while (open.size() > OPEN_MAX) {               // the oldest without an end yet: its outcome is unknown
                Job j = open.remove(0);
                if (j.verdict == null) j.judged(Verdict.unchecked(Reason.REPLACED, "too many paths at once"));
                if (j.outcome == null) j.real(Outcome.UNKNOWN, "dropped from the books");
                close(j);
            }
        } catch (RuntimeException | LinkageError e) {
            errors++;
            if (!errorLogged) LOG.warn("[entropybot] ghost check failed; open paths are unchecked (error)", e);
            else if (errors % 200 == 0) LOG.warn("[entropybot] ghost check failed {} times: {}", errors, e.toString());   // structural: about every 10 s
            errorLogged = true;
            for (Job j : open) if (j.verdict == null) j.judged(Verdict.unchecked(Reason.ERROR, e.toString()));
        } finally {
            ledger.gameTickCost(System.nanoTime() - t0);
        }
    }

    private void start(IPath path, LocalPlayer p, ClientLevel level) {
        List<BetterBlockPos> pos = path.positions();
        List<IMovement> moves = path.movements();
        List<Point> points = new ArrayList<>(pos.size());
        List<MoveKind> kinds = new ArrayList<>(pos.size());
        for (int i = 0; i < pos.size(); i++) {
            BetterBlockPos q = pos.get(i);
            points.add(new Point(q.x, q.y, q.z));
            kinds.add(i == 0 || i - 1 >= moves.size() ? MoveKind.OTHER : MoveKind.fromClassName(moves.get(i - 1).getClass().getSimpleName()));
        }
        if (points.isEmpty()) {   // review: an empty path has no destination to build a job on
            LOG.info("[entropybot] ghost: Baritone path with no positions, not checked");
            return;
        }
        Job j = new Job(ledger.newPath(), path, points, kinds, p, gameTick);
        for (int i = 1; i < points.size(); i++)
            j.route.append(' ').append(kinds.get(i).name().toLowerCase()).append('>').append(points.get(i).x()).append(',').append(points.get(i).y()).append(',').append(points.get(i).z());
        open.add(j);
        Verdict pre = j.check.start();
        if (pre == null) pre = scan(level, points);
        if (pre != null) {
            j.judged(pre);
            return;
        }
        try {
            j.ghost = GhostPlayer.of(p);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] ghost: no ghost for path {}: {}", j.no, e.toString());
            j.judged(Verdict.unchecked(Reason.ERROR, e.toString()));
        }
    }

    /** The body blocks along the path: a closed door or gate (unchecked), a block in the way (needs a break). */
    static Verdict scan(ClientLevel level, List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            Point q = points.get(i);
            for (int up = 0; up <= 1; up++) {
                BlockPos at = new BlockPos(q.x(), q.y() + up, q.z());
                BlockState s = level.getBlockState(at);
                boolean openable = s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof TrapDoorBlock;
                if (openable) {
                    if (s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN))
                        return Verdict.unchecked(Reason.DOOR, "at " + str(at));
                    continue;
                }
                if (s.is(BlockTags.CLIMBABLE)) continue;
                VoxelShape shape = s.getCollisionShape(level, at);
                if (!shape.isEmpty() && (up == 1 || shape.max(net.minecraft.core.Direction.Axis.Y) > 0.5))   // structural: taller than a bottom slab
                    return new Verdict(Kind.REFUSE, Reason.NEEDS_BREAK, at.getX(), at.getY(), at.getZ(), 0, s.getBlock().getName().getString());
            }
        }
        return null;
    }

    /** Closes every open job as unchecked / unknown, so the ledger totals keep matching its rows. */
    private void dropAll(String why) {
        for (Job j : open) {
            if (j.verdict == null) j.judged(Verdict.unchecked(Reason.REPLACED, why));
            if (j.outcome == null) j.real(Outcome.UNKNOWN, why);
            close(j);
        }
        open.clear();
    }

    private void close(Job j) {
        GhostLedger.Row row = new GhostLedger.Row(j.no, j.verdict, j.outcome, j.realNote);
        Boolean agree = ledger.close(row);
        if (Boolean.FALSE.equals(agree)) {
            if (incidents < Tuning.i("ghost.incidentsMax"))
                LOG.info("[entropybot] ghost DISAGREE {}; route{}; ghost track {}", row.line(), j.route, j.track);
            else LOG.info("[entropybot] ghost DISAGREE {} (incident cap reached, no track)", row.line());
            if (incidents < Tuning.i("ghost.incidentsMax")) {
                incidents++;
                try {
                    Core.INSTANCE.recorder.jobEnded("ghost", "path " + j.no, "error: ghost " + j.verdict.line() + ", real " + j.outcome + " " + j.realNote);
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] ghost incident not saved: {}", e.toString());
                }
            }
        }
    }

    static BlockPos feet(double x, double y, double z) {
        return BlockPos.containing(x, y + 0.1251, z);   // structural: Baritone's playerFeet (a bottom slab counts as its own block)
    }

    static String str(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    /** One path being judged. */
    private final class Job {
        final int no;
        final IPath path;
        final PathCheck check;
        final BlockPos dest;
        final int startTick;
        final boolean sprintAllowed;
        GhostPlayer ghost;
        Verdict verdict;
        Outcome outcome;
        String realNote = "";
        int fallsSeen;
        final StringBuilder track = new StringBuilder();
        final StringBuilder route = new StringBuilder();   // package 3 run 1: which move the pilot got wrong

        Job(int no, IPath path, List<Point> points, List<MoveKind> kinds, LocalPlayer p, int startTick) {
            this.no = no;
            this.path = path;
            this.check = new PathCheck(points, kinds, Tuning.i("ghost.pointTicks"), Tuning.i("ghost.hopAfterTicks"), Tuning.i("ghost.hops"));
            Point d = points.get(points.size() - 1);
            this.dest = new BlockPos(d.x(), d.y(), d.z());
            this.startTick = startTick;
            this.sprintAllowed = sprintSetting() && p.getFoodData().getFoodLevel() > 6;   // structural: vanilla sprint needs food above 6
        }

        void real(Outcome o, String note) {
            outcome = o;
            realNote = note;
        }

        void judged(Verdict v) {
            verdict = v;
            boolean full = ledger.verdict(v);
            if (v.kind() != Kind.REFUSE) return;
            if (full) LOG.info("[entropybot] ghost refuse: path {} to {}: {}", no, str(dest), v.line());
            else if (ledger.countLineDue()) LOG.info("[entropybot] ghost refuse: {} refusals so far (debug ghost status)", ledger.refusals());
        }

        void step(ClientLevel level) {
            PathCheck.Point s = check.source(), t = check.target();
            BlockPos f = feet(ghost.getX(), ghost.getY(), ghost.getZ());
            Pilot.Press press = Pilot.steer(new Pilot.View(ghost.getX(), ghost.getY(), ghost.getZ(), ghost.onGround(), ghost.onClimbable(),
                    f.getX(), f.getY(), f.getZ(), s.x(), s.y(), s.z(), t.x(), t.y(), t.z(), check.kind(), check.mayHop(), sprintAllowed));
            if (press.hop()) check.hopped();
            ghost.step(press.input(0f));
            List<GhostPlayer.Fall> falls = ghost.falls();
            int dmg = 0;
            for (int i = fallsSeen; i < falls.size(); i++) dmg = Math.max(dmg, falls.get(i).damage());
            fallsSeen = falls.size();
            BlockPos nf = feet(ghost.getX(), ghost.getY(), ghost.getZ());
            BlockState in = level.getBlockState(nf);
            boolean fire = level.getBlockStatesIfLoaded(ghost.getBoundingBox().deflate(1.0E-6)).anyMatch(st -> st.is(BlockTags.FIRE));
            boolean magma = ghost.onGround() && ghost.getBlockStateOn().getBlock() instanceof MagmaBlock;
            boolean stuck = in.getBlock() instanceof WebBlock || in.getBlock() instanceof SweetBerryBushBlock || in.getBlock() instanceof PowderSnowBlock;
            if (check.ticks() % 5 == 0) track.append(String.format(Locale.ROOT, " %.2f,%.2f,%.2f", ghost.getX(), ghost.getY(), ghost.getZ()));   // structural: a track point every 5 ticks
            Verdict v = check.tick(new PathCheck.Facts(ghost.getX(), ghost.getY(), ghost.getZ(), nf.getX(), nf.getY(), nf.getZ(), ghost.onGround(),
                    ghost.isInLava(), fire, magma, dmg, ghost.drowned(), stuck));
            if (v != null) {
                track.append(String.format(Locale.ROOT, " end %.2f,%.2f,%.2f", ghost.getX(), ghost.getY(), ghost.getZ()));
                judged(v);
                ghost = null;
            }
        }
    }

    private static boolean sprintSetting() {
        try {
            return baritone.api.BaritoneAPI.getSettings().allowSprint.value;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] ghost: Baritone's allowSprint not read ({}), the pilot sprints", e.toString());
            return true;
        }
    }
}
