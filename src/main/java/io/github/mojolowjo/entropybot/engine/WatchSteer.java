package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.watchview.TunnelView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * {@code watch steer} (TLL 32b): camera-relative movement keys in our own third-person watch views. The game side of
 * {@link SteerRules}.
 *
 * <p>Loader API: NeoForge's {@code MovementInputUpdateEvent} (posted by {@code ClientHooks.onMovementInputUpdate} from
 * {@code LocalPlayer.aiStep} right after {@code input.tick(isMovingSlowly, sneakMultiplier)} and before the
 * item-use slowdown, the auto-jump, the sprint checks and {@code xxa/zza = input.leftImpulse/forwardImpulse}). The
 * listener rotates {@code forwardImpulse} and {@code leftImpulse} in place; jumping, sneaking (already scaled into the
 * impulses) and the up/down/left/right booleans stay as the keys set them. The player's rotation is never written. On
 * Fabric there is no such event: a mixin at the tail of {@code KeyboardInput.tick} would do the same.
 *
 * <p>Only a human's keys are remapped ({@link SteerRules#humanOnly}): Baritone, the mod's jobs, chains and reflexes
 * drive the same input, so any of them running leaves the tick alone. The listener catches everything (the first 5
 * errors logged in full, then every 100th) and turns steer off for the session after 20 errors in a minute.
 *
 * <p>{@code watch turn} (2026-10-05, owner: "Roblox style"): in the same listener, A/D (read from the {@code Input}'s
 * own {@code left/right} booleans, so exactly the human's strafe keys) also turn the view's yaw at {@code watch turn
 * rate} degrees a second, and two key mappings of ours (the arrow keys by default, NeoForge
 * {@code RegisterKeyMappingsEvent} on the mod bus) turn it without strafing. Q/E are not used: the bot's window has
 * them on drop and inventory, and stealing them would need cancelling vanilla's key handling. Fabric:
 * {@code KeyBindingHelper.registerKeyBinding} for the keys, the same {@code KeyboardInput.tick} tail mixin for the rest.
 */
public final class WatchSteer {
    public static final WatchSteer INSTANCE = new WatchSteer();

    private volatile boolean master = true;
    private volatile boolean registered;
    private volatile String offByErrors;
    private final SteerRules.ErrorGate gate = new SteerRules.ErrorGate();
    private long applied, skippedForBot;
    private volatile String lastError;
    private volatile long lastAppliedMs;

    private WatchSteer() {}

    /** The human drove with remapped keys in the last second: the views hold their yaw (SteerRules.nextYaw). */
    public boolean humanDriving() {
        return lastAppliedMs != 0 && System.currentTimeMillis() - lastAppliedMs <= SteerRules.DRIVING_MS;
    }

    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, MovementInputUpdateEvent.class, INSTANCE::onInput);
        INSTANCE.registered = true;
        // watch turn: our own two key mappings (the arrow keys by default; rebindable in Controls, saved in options.txt)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent.class, INSTANCE::onRegisterKeys);
    }

    // ---- watch turn (2026-10-05) ----------------------------------------------------------------------------------

    private volatile boolean turnOn = true;
    private volatile float turnRate = SteerRules.TURN_RATE_DEFAULT;
    private volatile net.minecraft.client.KeyMapping turnLeftKey, turnRightKey;
    private volatile String keysError;
    private long turnedTicks;

    /**
     * Mod bus, once at start-up. Category "misc" (a vanilla category: no sort-order surprises); names from our lang
     * file. NeoForge's default conflict context is UNIVERSAL, but a KeyMapping is only "down" while no screen is open
     * (vanilla releases all keys on a screen change), so typing in chat never turns the camera.
     */
    private void onRegisterKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) {
        try {
            net.minecraft.client.KeyMapping l = new net.minecraft.client.KeyMapping("key.entropybot.watch_turn_left",
                    com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, "key.categories.misc");
            net.minecraft.client.KeyMapping r = new net.minecraft.client.KeyMapping("key.entropybot.watch_turn_right",
                    com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, "key.categories.misc");
            e.register(l);
            e.register(r);
            turnLeftKey = l;
            turnRightKey = r;
        } catch (Throwable t) {
            keysError = t.toString();
            com.mojang.logging.LogUtils.getLogger().error("[entropybot] watch turn: couldn't register the turn keys (only A/D will turn the camera): ", t);
        }
    }

    public boolean turnOn() { return turnOn; }

    public float turnRate() { return turnRate; }

    /** From the settings file, or {@code watch turn on|off}. */
    public void setTurnOn(boolean on) { turnOn = on; }

    /** From the settings file (checked there), or {@code watch turn rate N}. */
    public void setTurnRate(float r) { turnRate = r; }

    private static boolean down(net.minecraft.client.KeyMapping k) {
        return k != null && k.isDown();
    }

    /** The key names as bound now (the owner may have rebound them in Controls). */
    private String turnKeyNames() {
        try {
            net.minecraft.client.KeyMapping l = turnLeftKey, r = turnRightKey;
            if (l == null || r == null) return "turn keys NOT registered" + (keysError == null ? "" : " (" + keysError + ")");
            return l.getTranslatedKeyMessage().getString() + "/" + r.getTranslatedKeyMessage().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** {@code watch turn [on|off|status|rate 5-180]}. */
    public String turnCommand(String a) {
        String t = a == null ? "" : a.trim();
        if (t.isEmpty() || t.equals("status")) return turnStatus();
        if (t.equals("on") || t.equals("off")) {
            turnOn = t.equals("on");
            String saved = WatchSettings.INSTANCE.save();
            return "ok: watch turn " + t + (turnOn ? " - in watch and watch tunnel, A/D strafe and turn the camera, " + turnKeyNames() + " only turn it, "
                    + fmt(turnRate) + " degrees a second" + (master ? "" : " (watch steer is off: watch steer on as well)")
                    : " - A/D only strafe, the camera holds still while you drive") + saved;
        }
        if (t.startsWith("rate")) {
            float r = SteerRules.parseTurnRate(t.substring(4));
            if (r < 0) return "error: watch turn rate 5-180 (degrees a second; now " + fmt(turnRate) + ")";
            turnRate = r;
            return "ok: the watch views turn " + fmt(r) + " degrees a second while A/D or " + turnKeyNames() + " are held"
                    + (turnOn ? "" : " (watch turn is off: watch turn on)") + WatchSettings.INSTANCE.save();
        }
        return "error: watch turn [on|off|status|rate 5-180]";
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f);
    }

    public String turnStatus() {
        return "turn: " + (turnOn ? "on" + (master ? "" : " (but watch steer is off, so it does nothing)") : "off")
                + ", " + fmt(turnRate) + " deg/s, A/D strafe+turn, " + turnKeyNames() + " turn only; turned " + turnedTicks + " ticks";
    }

    public boolean master() { return master; }

    /** From the settings file, or {@code watch steer on|off}. On also clears an error trip. */
    public void setMaster(boolean on) {
        master = on;
        if (on && offByErrors != null) {
            offByErrors = null;
            gate.reset();
        }
    }

    public boolean registered() { return registered; }

    /** The effective state now (master switch, a watch view of ours on). */
    public SteerRules.State state() {
        boolean detached = false;
        try {
            detached = !Minecraft.getInstance().options.getCameraType().isFirstPerson();
        } catch (Throwable ignored) {
        }
        return SteerRules.effective(master && offByErrors == null, WatchCamera.INSTANCE.on(), detached, TunnelView.INSTANCE.on());
    }

    /** The yaw the camera is drawn with in that view. */
    static float cameraYaw(SteerRules.State s) {
        return s == SteerRules.State.TUNNEL ? TunnelView.INSTANCE.renderedYaw() : WatchCamera.INSTANCE.yaw();
    }

    private void onInput(MovementInputUpdateEvent e) {
        try {
            if (!master || offByErrors != null) return;
            SteerRules.State s = state();
            if (!s.active()) return;
            Input in = e.getInput();
            if (!(e.getEntity() instanceof LocalPlayer p) || in == null) return;
            boolean turnL = turnOn && down(turnLeftKey), turnR = turnOn && down(turnRightKey);
            boolean moving = in.forwardImpulse != 0f || in.leftImpulse != 0f;
            if (!moving && !turnL && !turnR) return;
            boolean human = SteerRules.humanOnly(in.getClass() == KeyboardInput.class, modIdle(), reflexHolds(), baritoneIdle(), p.isPassenger());
            if (!human) {
                skippedForBot++;
                return;
            }
            // watch turn: the camera turns first, then the keys are remapped with the turned yaw (same tick, same yaw)
            float[] r = SteerRules.steerTick(s, true, turnOn, in.left, in.right, turnL, turnR, turnRate, SteerRules.TICK_SECONDS,
                    in.forwardImpulse, in.leftImpulse, cameraYaw(s), p.getYRot());
            if (r[0] != 0f) {
                if (s == SteerRules.State.TUNNEL) TunnelView.INSTANCE.turnBy(r[0]);
                else WatchCamera.INSTANCE.turnBy(r[0]);
                turnedTicks++;
            }
            in.forwardImpulse = r[1];
            in.leftImpulse = r[2];
            if (moving) applied++;
            lastAppliedMs = System.currentTimeMillis();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static boolean modIdle() {
        io.github.mojolowjo.entropybot.Core core = io.github.mojolowjo.entropybot.Core.INSTANCE;
        return core.commands.idleForRoutes();
    }

    private static boolean reflexHolds() {
        return io.github.mojolowjo.entropybot.Core.INSTANCE.reflexes.hold();
    }

    /** Baritone neither pathing nor any process in control. Unknown (no Baritone, an error) = not idle: skip. */
    private static boolean baritoneIdle() {
        try {
            baritone.api.IBaritone b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            return b != null && !b.getPathingBehavior().isPathing() && b.getPathingControlManager().mostRecentInControl().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void fail(Throwable t) {
        try {
            lastError = t.toString();
            SteerRules.ErrorGate.Action a = gate.record(System.currentTimeMillis());
            if (a == SteerRules.ErrorGate.Action.LOG_FULL) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch steer: ", t);
            else if (a == SteerRules.ErrorGate.Action.LOG_SUMMARY)
                com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch steer: {} errors so far, last {}", gate.total(), lastError);
            if (gate.tripped() && offByErrors == null) {
                offByErrors = SteerRules.ErrorGate.TRIP + " errors within a minute, last " + lastError;
                String msg = "watch steer turned itself off: " + offByErrors + " (the keys work as normal; watch steer on tries again)";
                com.mojang.logging.LogUtils.getLogger().error("[entropybot] {}", msg);
                try {
                    io.github.mojolowjo.entropybot.Core.INSTANCE.events.push("job", msg, null);
                } catch (Throwable ignored) {
                }
                try {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        try {
                            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("[Entropy Bot] " + msg));
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // the error path itself never throws into the input tick
        }
    }

    /** {@code watch steer on|off|status}. */
    public String command(String a) {
        String t = a == null ? "" : a.trim();
        if (t.isEmpty() || t.equals("status")) return status();
        if (t.equals("on") || t.equals("off")) {
            setMaster(t.equals("on"));
            String saved = WatchSettings.INSTANCE.save();
            return "ok: watch steer " + t + " - " + state().words
                    + (t.equals("on") ? "; it is active only while watch or watch tunnel is on" : "") + saved;
        }
        return "error: watch steer [on|off|status]";
    }

    public String status() {
        SteerRules.State s = state();
        StringBuilder sb = new StringBuilder("steer: ").append(s.words);
        if (offByErrors != null) sb.append(" - TURNED ITSELF OFF: ").append(offByErrors);
        sb.append(" | remapped ").append(applied).append(" ticks, left alone ").append(skippedForBot).append(" ticks the bot drove itself");
        if (!registered) sb.append(" | input listener NOT registered (see check)");
        if (gate.total() > 0) sb.append(" | errors ").append(gate.total()).append(", last ").append(lastError);
        sb.append(" | ").append(turnStatus());
        return sb.toString();
    }

    /** For check. */
    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> f = new java.util.ArrayList<>(SteerRules.findings(master, registered, offByErrors));
        f.addAll(SteerRules.turnFindings(turnOn, master, registered, turnLeftKey != null && turnRightKey != null));
        return f;
    }
}
