package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dry-run package 1, the body test: "debug ghost shadow &lt;ticks&gt;" builds a ghost of the player now, then records the
 * real player's input and feet after each of its next ticks (however it is driven: Baritone, keys). When the ticks are
 * in (or on "debug ghost stop"), the ghost replays the same inputs from the start in one go, and one
 * {@code [entropybot] ghost shadow} line says how far apart the two ended, the worst gap and its tick, what the ghost
 * landed on (falls and the damage the game would deal), and what it cost: milliseconds per ghost tick (average and worst)
 * and to build the ghost. It never moves the real player and never stops the bot; an error ends the run with its reason.
 *
 * <p>The input is read at {@code PlayerTickEvent.Post} (inside {@code Player.tick}, after the move): {@code Input}'s
 * impulses as {@code LocalPlayer.aiStep} left them and the sprint flag. The facing is the one the move really used:
 * Baritone's mixin swaps the yaw inside {@code travel} and {@code jumpFromGround} for its look target and fires
 * {@code RotationMoveEvent} (MOTION_UPDATE, JUMP), whatever {@code freeLook} says; the player's own yaw catches up only
 * after the move (cloud test 2026-11-26: tick 51 of the gap jump moved by the yaw the player showed at tick 52). So the
 * yaw comes from that event when Baritone sent one this tick ({@link #rotationMove}, via {@code BaritoneHook}), else from
 * the player.
 *
 * <p>Pure: no (the game side; the comparison is {@link ShadowRun}, JUnit ShadowRunTest). Loader notes: NeoForge
 * {@code PlayerTickEvent.Post} (wired in {@code EntropyBot}); on Fabric a mixin at the tail of {@code Player.tick} or the
 * client tick end; the rest is vanilla.
 */
public final class GhostShadow {
    public static final GhostShadow INSTANCE = new GhostShadow();
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    static final double DRIFT_AT = 0.01;       // structural: report the first tick the tracks are 1 cm apart
    static final int MAX_TICKS = 6000;         // structural: 5 minutes of game ticks
    static final int DETAIL = 6;               // structural: ticks logged each side of the first drift

    private GhostPlayer ghost;
    private ShadowRun run;
    private final List<String> realState = new ArrayList<>();
    private double copyMs = Double.NaN;
    private Float moveYaw, movePitch, jumpYaw;
    private int fromBaritone, jumpYawDiffers;
    private boolean replaying;
    private String last = "no shadow run yet";

    private GhostShadow() {}

    /** Starts a run of up to {@code ticks} player ticks. Game thread. */
    public String start(int ticks) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: no player (not in a world)";
        if (!GhostPlayer.ready()) return "error: the ghost is off: its accessor mixins did not apply (see the game log)";
        if (run != null) return "error: a shadow run is going (" + run.size() + " of " + run.maxTicks() + " ticks); debug ghost stop first";
        int n = Math.max(1, Math.min(MAX_TICKS, ticks));
        long t0 = System.nanoTime();
        ghost = GhostPlayer.of(p);
        realState.clear();
        copyMs = (System.nanoTime() - t0) / 1e6;
        run = new ShadowRun(pos(p), n);
        moveYaw = movePitch = jumpYaw = null;
        fromBaritone = jumpYawDiffers = 0;
        LOG.info("[entropybot] ghost shadow: started, {} ticks from {}, ghost built in {} ms", n, run.start(), fmt(copyMs));
        return "ok: shadowing the next " + n + " player ticks from " + run.start() + " (ghost built in " + fmt(copyMs) + " ms)";
    }

    /**
     * A ghost-only walk: a ghost of the player now walks straight ahead (the player's yaw, forward held, sprint if asked)
     * for {@code ticks} ticks; the real player does not move. Answers where it ended, its landings and the cost. A test
     * tool for the leak guards (walk the ghost over redstone ore, then {@code debug block} it).
     */
    public String walk(int ticks, boolean sprint) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: no player (not in a world)";
        if (!GhostPlayer.ready()) return "error: the ghost is off: its accessor mixins did not apply (see the game log)";
        int n = Math.max(1, Math.min(MAX_TICKS, ticks));
        try {
            long t0 = System.nanoTime();
            GhostPlayer g = GhostPlayer.of(p);
            double build = (System.nanoTime() - t0) / 1e6;
            TickTimes times = new TickTimes();
            GhostInput in = new GhostInput(1f, 0f, false, sprint, false, p.getYRot(), p.getXRot());
            replaying = true;
            for (int i = 0; i < n; i++) {
                long t1 = System.nanoTime();
                g.step(in);
                times.add(System.nanoTime() - t1);
            }
            String out = "ghost walk " + n + " ticks" + (sprint ? " sprinting" : "") + " from " + pos(p) + " to " + pos(g) + ", "
                    + g.falls().size() + " landings; ms per tick: " + times + ", build " + fmt(build) + " ms";
            LOG.info("[entropybot] {}", out);
            return out;
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] ghost walk failed", ex);
            return "error: the ghost walk stopped: " + ex;
        } finally {
            replaying = false;
        }
    }

    /** Ends the run now (replays what was recorded). */
    public String stop() {
        if (run == null) return "no shadow run going; last: " + last;
        if (run.size() == 0) {
            run = null;
            ghost = null;
            return "stopped before any tick was recorded";
        }
        finish();
        return last;
    }

    public String status() {
        String ready = GhostPlayer.ready() ? "ghost ready (leak guard: overrides, no injection)" : "ghost OFF (accessor mixins missing)";
        if (run != null) return ready + "; shadow run going: " + run.size() + " of " + run.maxTicks() + " ticks";
        return ready + "; last: " + last;
    }

    /** Baritone's {@code RotationMoveEvent} after its own listeners set it: the facing this tick's move or jump used. */
    public void rotationMove(boolean jump, float yaw, float pitch) {
        if (run == null || replaying) return;
        if (jump) jumpYaw = yaw;
        else {
            moveYaw = yaw;
            movePitch = pitch;
        }
    }

    /** NeoForge {@code PlayerTickEvent.Post}: one recorded tick of the local player. */
    public void afterPlayerTick(Player e) {
        if (run == null || !(e instanceof LocalPlayer p) || p != Minecraft.getInstance().player) return;
        try {
            float yaw = moveYaw != null ? moveYaw : jumpYaw != null ? jumpYaw : p.getYRot();
            float pitch = movePitch != null ? movePitch : p.getXRot();
            if (moveYaw != null || jumpYaw != null) fromBaritone++;
            if (moveYaw != null && jumpYaw != null && Math.abs(moveYaw - jumpYaw) > 0.01f) jumpYawDiffers++;
            moveYaw = movePitch = jumpYaw = null;
            run.add(new GhostInput(p.input.forwardImpulse, p.input.leftImpulse, p.input.jumping, p.isSprinting(), p.input.shiftKeyDown,
                    yaw, pitch), pos(p));
            realState.add(state(p));
            if (run.full()) finish();
        } catch (RuntimeException ex) {
            fail(ex);
        }
    }

    private void finish() {
        try {
            TickTimes times = new TickTimes();
            List<ShadowRun.Pos> track = new ArrayList<>(run.size());
            List<String> ghostState = new ArrayList<>(run.size());
            replaying = true;
            for (GhostInput in : run.inputs()) {
                long t0 = System.nanoTime();
                ghost.step(in);
                times.add(System.nanoTime() - t0);
                track.add(pos(ghost));
                ghostState.add(state(ghost));
            }
            replaying = false;
            ShadowRun.Report rep = run.compare(track, DRIFT_AT, Tuning.get("ghost.shadowPassBlocks"));
            if (rep.firstDriftTick() > 0) {
                // the ticks around the first drift, one line each, so the cause can be read from the log
                int from = Math.max(1, rep.firstDriftTick() - DETAIL), to = Math.min(run.size(), rep.firstDriftTick() + DETAIL);
                for (int i = from; i <= to; i++)
                    LOG.info("[entropybot] ghost shadow tick {}: {} | real {} {} | ghost {} {}", i, run.inputs().get(i - 1), run.real(i - 1), realState.get(i - 1), track.get(i - 1), ghostState.get(i - 1));
            }
            StringBuilder falls = new StringBuilder();
            for (GhostPlayer.Fall f : ghost.falls())
                if (f.distance() > 0) falls.append(String.format(Locale.ROOT, " [tick %d: %.2f blocks x%.1f -> %d damage]", f.tick(), f.distance(), f.multiplier(), Math.max(0, f.damage())));
            last = "ghost shadow " + rep.line() + "; real: " + run.summary() + "; ghost: " + ghost.jumps() + " jumps, falls" + (falls.length() == 0 ? " none" : falls)
                    + (ghost.drowned() ? ", out of air" : "") + "; ghost ms per tick: " + times + ", build " + fmt(copyMs) + " ms"
                    + "; yaw from Baritone's move event on " + fromBaritone + " ticks" + (jumpYawDiffers > 0 ? ", the jump's yaw differed on " + jumpYawDiffers : "");
            LOG.info("[entropybot] {}", last);
        } catch (RuntimeException ex) {
            fail(ex);
        } finally {
            replaying = false;
            run = null;
            ghost = null;
        }
    }

    private void fail(RuntimeException ex) {
        last = "error: the shadow run stopped: " + ex;
        LOG.warn("[entropybot] ghost shadow: {}", last, ex);
        run = null;
        ghost = null;
    }

    private static String state(Player p) {
        net.minecraft.world.phys.Vec3 v = p.getDeltaMovement();
        return String.format(Locale.ROOT, "v (%.4f, %.4f, %.4f) ground %s sprint %s speed %.4f", v.x, v.y, v.z, p.onGround(), p.isSprinting(), p.getSpeed());
    }

    private static ShadowRun.Pos pos(Player p) { return new ShadowRun.Pos(p.getX(), p.getY(), p.getZ()); }

    private static String fmt(double ms) { return String.format(Locale.ROOT, "%.3f", ms); }
}
