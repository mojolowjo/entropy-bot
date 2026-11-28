package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.ghost.CheckBudget;
import io.github.mojolowjo.entropybot.ghost.GhostInput;
import io.github.mojolowjo.entropybot.ghost.GhostPlayer;
import io.github.mojolowjo.entropybot.nav.steer.Choice;
import io.github.mojolowjo.entropybot.nav.steer.Sim;
import io.github.mojolowjo.entropybot.nav.steer.Steerer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;

/**
 * Steering S2 (plan to-do/nav-steer.md, m0j 2026-11-27): the real ghost behind the {@link Pilot}'s steerer. Each try
 * starts from a fresh copy of the real player ({@link GhostPlayer#copyFrom}) and runs the game's own movement code, with
 * the keys turned into the impulses as {@link NavInput} does (the sneak key scales by the player's sneaking speed), the
 * head turning at most {@code walkTurn} a tick toward the wanted facing as the real head does, and sprint only with
 * forward held, enough food and no wall. A try "hurts" when it touches lava or fire, drowns, or lands a fall the game
 * would charge damage for.
 *
 * <p>The ghost's steps share the 9c budget numbers ({@code ghost.ticksPerTick}, {@code ghost.msPerTick}) per game tick,
 * counted from {@link #newTick}. Ghost steps and ms are counted for {@code aa nav status}.
 *
 * <p>Pure: no (the game side; the choosing is {@code nav.steer.Steerer}, JUnit SteererTest). Loader notes: vanilla
 * {@code LocalPlayer}, {@code Attributes.SNEAKING_SPEED}; the ghost's own notes ({@link GhostPlayer}).
 */
final class GhostSteer implements Pilot.Ghost, Sim {
    private final LocalPlayer real;
    private final float walkTurn;
    private final double sensitivity;
    private GhostPlayer g;
    private boolean hurt;
    private long tickStart;
    private int tickSteps;
    private long steps, maxNanos, sumNanos, ticks;

    GhostSteer(LocalPlayer real, float walkTurn, double sensitivity) {
        this.real = real;
        this.walkTurn = walkTurn;
        this.sensitivity = sensitivity;
    }

    /** True when the ghost can be built (its accessor mixins applied). */
    static boolean ready() {
        return GhostPlayer.ready();
    }

    /** A new game tick: the budget starts again; the last tick's time goes into the counters. */
    void newTick() {
        if (tickStart != 0 && tickSteps > 0) {
            long n = System.nanoTime() - tickStart;
            sumNanos += n;
            maxNanos = Math.max(maxNanos, n);
            ticks++;
        }
        tickStart = System.nanoTime();
        tickSteps = 0;
    }

    @Override public boolean inWater() {
        return real.isInWater() || real.isUnderWater();
    }

    @Override public Sim sim() { return this; }

    @Override public Steerer.Budget budget() {
        CheckBudget b = new CheckBudget(Tuning.i("ghost.ticksPerTick"), Tuning.get("ghost.msPerTick"), Integer.MAX_VALUE);
        return () -> b.mayStep(tickSteps, (System.nanoTime() - tickStart) / 1e6);
    }

    @Override public void reset() {
        if (g == null || g.level() != real.level()) g = GhostPlayer.of(real);
        else g.copyFrom(real);
        hurt = false;
    }

    @Override public void step(Choice c, float yaw) {
        tickSteps++;
        steps++;
        LookMath.Turn n = LookMath.step(g.getYRot(), g.getXRot(), yaw, g.getXRot(), walkTurn, sensitivity);
        Keys k = new Keys();
        k.set(Pilot.keysOf(c));
        Keys.Impulse i = k.impulse((float) real.getAttributeValue(Attributes.SNEAKING_SPEED));
        boolean food = real.getFoodData().getFoodLevel() > 6 || real.getAbilities().mayfly;   // structural: LocalPlayer.hasEnoughFoodToStartSprinting
        boolean sprint = c.sprint() && c.forward() > 0 && food && !g.horizontalCollision;
        g.step(new GhostInput(i.forward(), i.left(), c.jump(), sprint, c.sneak(), n.yaw(), n.pitch()));
        if (g.isInLava() || g.isOnFire() || g.drowned()) hurt = true;
        for (GhostPlayer.Fall f : g.falls()) if (f.damage() > 0) hurt = true;
    }

    @Override public double x() { return g.getX(); }
    @Override public double y() { return g.getY(); }
    @Override public double z() { return g.getZ(); }
    @Override public boolean hurt() { return hurt; }
    @Override public int air() { return g.getAirSupply(); }

    /** The ghost's work for {@code aa nav status}. */
    String status() {
        return String.format(Locale.ROOT, "ghost steps %d, %.2f ms a steered tick on average, worst %.2f ms", steps,
                ticks == 0 ? 0 : sumNanos / 1e6 / ticks, maxNanos / 1e6);
    }

    /** A ghost that would not build or step: logged once a minute by the caller, never thrown into the tick. */
    static void warn(Throwable e) {
        Quiet.warn("steer: the ghost failed (" + e + "); the move's own driver takes over", e);
    }
}
