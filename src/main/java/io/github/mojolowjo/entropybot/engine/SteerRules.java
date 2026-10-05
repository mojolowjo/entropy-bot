package io.github.mojolowjo.entropybot.engine;

/**
 * {@code watch steer} (TLL 32b, docs/CAMERA_PLAN.md "Steer"): the pure rules of the camera-relative movement keys. In
 * our own third-person watch views W moves the bot away from the camera, S towards it, A/D to the camera's left/right.
 * Done as an input remap: the key vector (leftImpulse, forwardImpulse) is rotated from the camera's yaw frame into the
 * player's yaw frame, so the player's own rotation never changes (the camera stays render-only). JUnit-tested; the game
 * side is {@link WatchSteer}.
 *
 * <p>The maths: vanilla {@code Entity.getInputVector} turns the input (x = leftImpulse, z = forwardImpulse) into world
 * motion with the rotation R(yRot): x' = x cos - z sin, z' = x sin + z cos. We want R(playerYaw) v' = R(cameraYaw) v,
 * so v' = R(cameraYaw - playerYaw) v. A rotation keeps the length, so no clamp is needed (a diagonal stays sqrt 2, which
 * vanilla normalises itself, as it does for an unremapped diagonal).
 */
public final class SteerRules {
    private SteerRules() {}

    /** The effective state: OFF (master switch off), IDLE (on, but no watch view of ours), or active in one view. */
    public enum State {
        OFF("off (watch steer on to turn it back on)"),
        IDLE("idle (no watch view on: the keys work as normal)"),
        V1("active (watch: W/S/A/D move away from/towards/left/right of the camera)"),
        TUNNEL("active (watch tunnel: W/S/A/D move away from/towards/left/right of the camera)");

        public final String words;

        State(String words) { this.words = words; }

        public boolean active() { return this == V1 || this == TUNNEL; }
    }

    /**
     * The owner's rule (2026-10-04): on whenever one of our watch views is on, off in first person and with no view.
     * v1 counts only while the camera is detached (an F5 back to first person leaves the keys alone); the tunnel view
     * always places the camera itself, so it counts while it is on. Vanilla's own F5 third person never counts.
     */
    public static State effective(boolean master, boolean v1On, boolean v1Detached, boolean tunnelOn) {
        if (!master) return State.OFF;
        if (tunnelOn) return State.TUNNEL;
        if (v1On && v1Detached) return State.V1;
        return State.IDLE;
    }

    /**
     * Only keys a human pressed: the player's input is vanilla's own {@code KeyboardInput} (Baritone swaps in its
     * {@code PlayerMovementInput} while it is in control), no mod job, chain or open request, no reflex (fight, eating,
     * fetching food; the creeper duel presses W itself), Baritone neither pathing nor any process in control, and not
     * riding (a boat or horse steers by its own rules). Any doubt = false.
     */
    public static boolean humanOnly(boolean keyboardInput, boolean modIdle, boolean reflexHolds, boolean baritoneIdle, boolean passenger) {
        return keyboardInput && modIdle && !reflexHolds && baritoneIdle && !passenger;
    }

    /** {forward, left} turned from the camera's yaw frame into the player's (degrees, Minecraft yaw). */
    public static float[] rotate(float forward, float left, float cameraYaw, float playerYaw) {
        double d = Math.toRadians(cameraYaw - playerYaw);
        double c = Math.cos(d), s = Math.sin(d);
        float l2 = (float) (left * c - forward * s);
        float f2 = (float) (left * s + forward * c);
        return new float[]{clean(f2), clean(l2)};
    }

    /** Rounding noise (cos 90 = 6e-17) to a clean 0, so "no forward impulse" stays exactly that. */
    private static float clean(float v) {
        return Math.abs(v) < 1e-6f ? 0f : v;
    }

    /**
     * The tick's impulses: {forward, left}, rotated only when the state is active and the keys are a human's;
     * otherwise the input unchanged. No key pressed: unchanged too.
     */
    public static float[] remap(State state, boolean human, float forward, float left, float cameraYaw, float playerYaw) {
        if (state == null || !state.active() || !human || (forward == 0f && left == 0f)) return new float[]{forward, left};
        return rotate(forward, left, cameraYaw, playerYaw);
    }

    /** How long after the last remapped tick the human counts as driving (the views hold their yaw meanwhile). */
    public static final long DRIVING_MS = 1000;

    /**
     * The view's next yaw. While the human drives, the walk-following must stop: it would turn the camera towards the
     * walking direction, which turns the keys with it (D would walk a circle, S would swing the camera round). So the
     * camera holds its yaw and only turns with the player's own turn (the mouse, when it is grabbed). Otherwise the
     * view's own follow result. {@code watch turn} (2026-10-05) turns the held yaw from the input listener itself
     * (A/D and the turn keys, {@link #steerTick}), before this runs in the same tick, so the hold keeps that turn.
     */
    public static float nextYaw(boolean humanDriving, float cameraYaw, float followedYaw, float playerTurn) {
        return humanDriving ? cameraYaw + playerTurn : followedYaw;
    }

    // ---- watch turn (owner, 2026-10-05: "Roblox style": A/D strafe AND turn the camera; the arrow keys only turn) ----

    /** Degrees per second: the default and the range of {@code watch turn rate N}. */
    public static final float TURN_RATE_DEFAULT = 45f, TURN_RATE_MIN = 5f, TURN_RATE_MAX = 180f;
    /** One client tick in seconds. The turn is stepped per tick, like the movement, so a held D walks the same circle at any frame rate. */
    public static final float TICK_SECONDS = 0.05f;

    /** Pure: the rate asked for, or -1 when the text is not a number in 5-180. */
    public static float parseTurnRate(String s) {
        try {
            float r = Float.parseFloat(s.trim());
            return r >= TURN_RATE_MIN && r <= TURN_RATE_MAX ? r : -1f;
        } catch (RuntimeException e) {
            return -1f;
        }
    }

    /**
     * Pure: which way the keys turn the camera: -1 left, +1 right, 0 none. A (strafe left) and the turn-left key count
     * left, D and turn-right count right; opposite keys cancel, two keys the same way do not turn faster.
     */
    public static int turnDirection(boolean strafeLeft, boolean strafeRight, boolean turnLeft, boolean turnRight) {
        int d = (strafeLeft ? -1 : 0) + (strafeRight ? 1 : 0) + (turnLeft ? -1 : 0) + (turnRight ? 1 : 0);
        return Integer.signum(d);
    }

    /** Pure: a yaw wrapped into [-180, 180), so a long turn never grows the number (float precision). */
    public static float wrap(float yaw) {
        float y = (yaw + 180f) % 360f;
        if (y < 0f) y += 360f;
        return y - 180f;
    }

    /**
     * Pure: the camera's turn for one step, in degrees (Minecraft's yaw grows turning right: south 0, west 90). rate is
     * degrees per second, dt the step in seconds. 0 when no turn key is held or the values make no sense.
     */
    public static float turnDelta(boolean strafeLeft, boolean strafeRight, boolean turnLeft, boolean turnRight, float rate, float dt) {
        int d = turnDirection(strafeLeft, strafeRight, turnLeft, turnRight);
        if (d == 0 || !(rate > 0f) || !(dt > 0f) || Float.isInfinite(rate) || Float.isInfinite(dt)) return 0f;
        return d * rate * dt;
    }

    /** Pure: the new yaw after one step (wrapped). */
    public static float turnYaw(boolean strafeLeft, boolean strafeRight, boolean turnLeft, boolean turnRight, float rate, float dt, float yaw) {
        float d = turnDelta(strafeLeft, strafeRight, turnLeft, turnRight, rate, dt);
        return d == 0f ? yaw : wrap(yaw + d);
    }

    /**
     * Pure: one tick of steer with the turn, in the order the game must do it: first the camera turns (A/D only when
     * {@code turnOn}; the turn keys too), then the movement keys are remapped with the yaw AFTER that turn, so the
     * remap and the camera use the same yaw in the same tick (no one-tick lag: the bot's step and the view's turn are
     * the same tick's). Returns {turn in degrees, forward, left}. Not active or not a human: {0, forward, left}.
     *
     * @param cameraYaw the yaw the remap would use before the turn (the drawn yaw of that view)
     */
    public static float[] steerTick(State state, boolean human, boolean turnOn, boolean strafeLeftKey, boolean strafeRightKey,
                                    boolean turnLeftKey, boolean turnRightKey, float rate, float dt,
                                    float forward, float left, float cameraYaw, float playerYaw) {
        if (state == null || !state.active() || !human) return new float[]{0f, forward, left};
        float turn = turnOn ? turnDelta(strafeLeftKey, strafeRightKey, turnLeftKey, turnRightKey, rate, dt) : 0f;
        float[] r = remap(state, true, forward, left, cameraYaw + turn, playerYaw);
        return new float[]{turn, r[0], r[1]};
    }

    /** The check findings for the turn: the turn needs the input listener; the arrow keys need their key mappings. */
    public static java.util.List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> turnFindings(boolean turnOn, boolean steerOn,
                                                                                                         boolean listenerRegistered, boolean keysRegistered) {
        java.util.List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> out = new java.util.ArrayList<>();
        if (!turnOn || !steerOn) return out;
        if (!listenerRegistered) out.add(new io.github.mojolowjo.entropybot.commands.SelfCheck.Finding("turnhook",
                "watch turn is on, but the movement-input listener that turns the camera (MovementInputUpdateEvent) is not registered: neither A/D nor the arrow keys turn the watch views",
                "look for [entropybot] errors at the start of the game log"));
        else if (!keysRegistered) out.add(new io.github.mojolowjo.entropybot.commands.SelfCheck.Finding("turnkeys",
                "watch turn is on, but its turn keys (RegisterKeyMappingsEvent, the arrow keys by default) are not registered: only A/D turn the camera",
                "look for [entropybot] errors at the start of the game log"));
        return out;
    }

    /** The check findings: the input listener not registered while the setting is on, or steer off after errors. */
    public static java.util.List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> findings(boolean master, boolean registered, String offByErrors) {
        java.util.List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> out = new java.util.ArrayList<>();
        if (master && !registered) out.add(new io.github.mojolowjo.entropybot.commands.SelfCheck.Finding("steerhook",
                "watch steer is on, but its movement-input listener (MovementInputUpdateEvent) is not registered: the keys stay as normal in the watch views",
                "look for [entropybot] errors at the start of the game log"));
        if (offByErrors != null) out.add(new io.github.mojolowjo.entropybot.commands.SelfCheck.Finding("steererror",
                "watch steer turned itself off after errors: " + offByErrors,
                "watch status; the log has [entropybot] watch steer lines; watch steer on tries again"));
        return out;
    }

    /**
     * The handler's error gate (docs/PLANNING.md: no silent failures): the first 5 errors are logged in full, then
     * every 100th; 20 errors within a minute turn steer off for the session. Not thread-safe (client thread only).
     */
    public static final class ErrorGate {
        public static final int FULL = 5, EVERY = 100, TRIP = 20;
        public static final long WINDOW_MS = 60_000;

        public enum Action { LOG_FULL, LOG_SUMMARY, QUIET }

        private long total;
        private final long[] recent = new long[TRIP];
        private int next, filled;
        private boolean tripped;

        /** Counts one error at nowMs; the log action for it. */
        public Action record(long nowMs) {
            total++;
            recent[next] = nowMs;
            next = (next + 1) % TRIP;
            if (filled < TRIP) filled++;
            // the oldest of the last 20 is where next points once the ring is full
            if (filled == TRIP && nowMs - recent[next] <= WINDOW_MS) tripped = true;
            if (total <= FULL) return Action.LOG_FULL;
            return total % EVERY == 0 ? Action.LOG_SUMMARY : Action.QUIET;
        }

        /** 20 errors within a minute have been seen. */
        public boolean tripped() { return tripped; }

        public long total() { return total; }

        /** A fresh start ({@code watch steer on} after a trip). */
        public void reset() {
            total = 0;
            next = 0;
            filled = 0;
            tripped = false;
        }
    }
}
