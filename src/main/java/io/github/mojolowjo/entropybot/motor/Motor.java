package io.github.mojolowjo.entropybot.motor;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.engine.EngineProcess;
import io.github.mojolowjo.entropybot.engine.WatchSteer;
import io.github.mojolowjo.entropybot.threat.ReachGrid;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 0.27.0 motor adapter: runs the arbiter's move each client tick (called from the reflexes' tick) and writes the key
 * vector in NeoForge's {@code MovementInputUpdateEvent} (posted by {@code LocalPlayer.aiStep} right after
 * {@code Input.tick}, before the impulses become {@code xxa/zza}). Baritone is paused through {@link EngineProcess#hold()}
 * while a move runs. Never throws; errors are counted, the first 5 logged in full, and 20 turn the motor off.
 */
public final class Motor {
    /** AA_CLEANUP 3: a motor move ends after this many ticks (the old BrainConfig motor.moveTicks default). */
    public static final int MOVE_TICKS = 60;
    private static final Logger LOG = LogUtils.getLogger();
    public static final Motor INSTANCE = new Motor();
    static final int ERROR_LIMIT = 20;
    static final double ARRIVE = 0.35;

    final Arbiter arb = new Arbiter();
    private volatile EngineProcess engine;
    private volatile boolean registered;
    private volatile boolean writing;
    private volatile float fwd, left;
    private volatile boolean jump;
    private long events, written;
    private int errors, idx;
    private String offBy, lastError;
    private long lastErrorLog;
    private int faceId = -1;
    private String line;
    private long lineMs;

    private Motor() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, MovementInputUpdateEvent.class, INSTANCE::onInput);
        INSTANCE.registered = true;
    }

    public void bind(EngineProcess e) { engine = e; }

    public Arbiter arbiter() { return arb; }

    public boolean active() { return arb.active(); }

    public MotorPlan.Kind kind() {
        MotorPlan.Plan p = arb.plan();
        return p == null ? null : p.kind();
    }

    /** The motor may run (the key on, not stopped by errors). */
    public boolean enabled() {
        try {
            return offBy == null && registered;      // AA_CLEANUP 3: the BrainConfig "motor" key (default on) went
        } catch (RuntimeException e) {
            return false;
        }
    }

    Arbiter.Gate gate(Minecraft mc, boolean reflexHolds) {
        boolean human = false;
        try {
            human = WatchSteer.INSTANCE.humanDriving();
        } catch (RuntimeException ignored) {
        }
        return new Arbiter.Gate(enabled(), mc.screen != null, human, reflexHolds);
    }

    /** The fresh threat grid, or null. */
    public static ReachGrid grid() {
        try {
            return ThreatRuntime.INSTANCE.grid();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Start a move; null when it runs, else the refusal. face: the entity to keep facing (back off, strafe, hold) or null. */
    public String start(MotorPlan.Plan plan, Arbiter.Owner owner, Entity face, String why, long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            int budget = MOVE_TICKS;
            String r = arb.request(plan, owner, tick, budget, gate(mc, owner == Arbiter.Owner.REFLEX), why, System.currentTimeMillis());
            if (r != null) return r;
            idx = 0;
            faceId = face == null ? -1 : face.getId();
            line = why == null || why.isEmpty() ? plan.name() : why;
            lineMs = System.currentTimeMillis();
            EngineProcess e = engine;
            if (e != null) e.hold();
            return null;
        } catch (Throwable t) {
            error("start", t);
            return "motor error: " + t;
        }
    }

    /** End the running move (the caller's stop). */
    public void stop(String why, long tick) {
        try {
            Arbiter.Owner o = arb.owner();
            if (arb.stop(tick, why, System.currentTimeMillis()) != null) ended(o);
        } catch (Throwable t) {
            error("stop", t);
        }
    }

    /** Once per client tick (from the reflexes). Never throws. */
    public void tick(Minecraft mc, LocalPlayer p, long tick, boolean reflexHolds) {
        try {
            MotorPlan.Plan plan = arb.plan();
            if (plan == null || p == null) {
                writing = false;
                return;
            }
            Arbiter.Owner owner = arb.owner();
            int[] end = plan.end();
            // advance along the cells
            while (idx < plan.cells().size() && at(p, plan.cells().get(idx))) idx++;
            boolean done = plan.kind() != MotorPlan.Kind.HOLD
                    && (idx >= plan.cells().size() || (plan.kind() == MotorPlan.Kind.OUT_OF_WATER && !p.isInWater() && p.onGround()));
            double remaining = plan.kind() == MotorPlan.Kind.HOLD || end == null ? Double.NaN
                    : Math.sqrt(sq(p.getX() - (end[0] + 0.5)) + sq(p.getY() - end[1]) + sq(p.getZ() - (end[2] + 0.5)));
            String ended = arb.tick(tick, remaining, done, gate(mc, reflexHolds || owner == Arbiter.Owner.REFLEX), System.currentTimeMillis());
            if (ended != null) {
                ended(owner);
                return;
            }
            EngineProcess e = engine;
            if (e != null && e.mode() != EngineProcess.Mode.HOLD) e.hold();
            Entity face = faceId >= 0 && mc.level != null ? mc.level.getEntity(faceId) : null;
            if (face != null) {
                try {
                    p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, face.getEyePosition());
                } catch (RuntimeException ignored) {
                }
            }
            if (plan.kind() == MotorPlan.Kind.HOLD || idx >= plan.cells().size()) {
                fwd = 0f;
                left = 0f;
                jump = false;
            } else {
                int[] c = plan.cells().get(idx);
                float[] k = MotorSteer.impulses(p.getYRot(), c[0] + 0.5 - p.getX(), c[2] + 0.5 - p.getZ());
                fwd = k[0];
                left = k[1];
                jump = p.isInWater() || (c[1] > p.getY() + 0.5 && p.onGround()) || (p.horizontalCollision && p.onGround());
            }
            writing = true;
        } catch (Throwable t) {
            writing = false;
            error("tick", t);
        }
    }

    private void ended(Arbiter.Owner owner) {
        writing = false;
        fwd = 0f;
        left = 0f;
        jump = false;
        faceId = -1;
        EngineProcess e = engine;
        if (owner == Arbiter.Owner.JOB && e != null) e.release();     // a reflex move leaves Baritone to the reflexes' settle()
    }

    private static boolean at(LocalPlayer p, int[] c) {
        return Math.abs(p.getX() - (c[0] + 0.5)) <= ARRIVE && Math.abs(p.getZ() - (c[2] + 0.5)) <= ARRIVE && Math.abs(p.getY() - c[1]) < 0.6;
    }

    static double sq(double d) { return d * d; }

    private void onInput(MovementInputUpdateEvent ev) {
        try {
            events++;
            if (!writing) return;
            if (!(ev.getEntity() instanceof LocalPlayer)) return;
            Input in = ev.getInput();
            if (in == null) return;
            in.forwardImpulse = fwd;
            in.leftImpulse = left;
            in.up = fwd > 0;
            in.down = fwd < 0;
            in.left = left > 0;
            in.right = left < 0;
            in.jumping = jump;
            in.shiftKeyDown = false;
            written++;
        } catch (Throwable t) {
            writing = false;
            error("input", t);
        }
    }

    private void error(String where, Throwable t) {
        errors++;
        lastError = where + ": " + t;
        long now = System.currentTimeMillis();
        if (errors <= 5 || now - lastErrorLog >= 60_000) {
            lastErrorLog = now;
            LOG.error("[entropybot] motor error #{} in {}: {}", errors, where, t.toString());
        }
        if (errors >= ERROR_LIMIT && offBy == null) {
            offBy = errors + " errors (last: " + lastError + ")";
            LOG.error("[entropybot] motor: turned off after {}", offBy);
            try {
                arb.stop(0, "motor off by errors", now);
            } catch (Throwable ignored) {
            }
            writing = false;
        }
    }

    // ---- reporting ----

    /** For "why threats": the running move or the last one (within a minute). */
    public String whyLine() {
        MotorPlan.Plan p = arb.plan();
        if (p != null) return "motor: " + line + " (running)";
        if (line != null && System.currentTimeMillis() - lineMs < 60_000) {
            var last = arb.last();
            String end = last.isEmpty() ? "" : " - " + last.get(last.size() - 1).end();
            return "motor: " + line + end;
        }
        return null;
    }

    /** "debug motor". */
    public String debug() {
        MotorPlan.Plan p = arb.plan();
        long ms = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("motor: ");
        b.append(offBy != null ? "OFF by errors: " + offBy : enabled() ? "on" : registered ? "off (motor 0)" : "NOT running: the movement-input listener is not registered");
        b.append(" | running: ").append(p == null ? "none" : p.name() + " [" + arb.owner().name().toLowerCase() + "] cell " + idx + "/" + p.cells().size() + (line == null ? "" : " - " + line));
        b.append(" | events ").append(events).append(", written ").append(written)
                .append(" | moves ").append(arb.acceptedCount()).append(", refused ").append(arb.refusedCount())
                .append(" | failures last hour ").append(arb.failuresLastHour(ms)).append(arb.lastFailure() == null ? "" : " (last: " + arb.lastFailure() + ")")
                .append(" | errors ").append(errors).append(lastError == null ? "" : " (last: " + lastError + ")");
        b.append("\nlast moves (oldest first):\n").append(arb.lastText());
        return b.toString();
    }

    /** For the recorder's incident. */
    public String incidentText() { return "motor, last moves:\n" + arb.lastText(); }

    /** For check: {key, line, fix} findings. */
    public java.util.List<String[]> findings() {
        java.util.List<String[]> f = new java.util.ArrayList<>();
        String[] x = arb.finding(System.currentTimeMillis());
        if (x != null) f.add(x);
        if (offBy != null) f.add(new String[]{"motoroff", "the motor turned itself off: " + offBy, "debug motor; the game log has [entropybot] motor lines"});
        if (!registered) f.add(new String[]{"motorhook", "the motor's movement-input listener (MovementInputUpdateEvent) is not registered: no footwork", "debug motor"});
        return f;
    }
}
