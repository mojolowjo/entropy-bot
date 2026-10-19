package io.github.mojolowjo.entropybot.aa.brain;

/**
 * Owner's idea (aa-sprint): with no clear food source and little food in the bag, spend less hunger: no sprinting
 * (Baritone allowSprint off, our own raw-run sprint off) and no parkour; danger (fleeing) may still sprint. Normal
 * movement returns when a food source is known again or the bag covers the stock target (FoodModel.target).
 *
 * <p>Pure decision plus a self-expiring flag the movement package reads: the AA tick renews it, so if AA stops (off,
 * death, world left) the flag lapses after {@link #LAPSE_MS} and the settings are restored by Profiles.conserve.
 */
public final class HungerConserve {
    private HungerConserve() {}

    /** The flag lapses this long after the last renewal (the AA tick runs every 250 ms). */
    public static final long LAPSE_MS = 3_000;

    /**
     * @param drains    hunger drains at all (survival)
     * @param noSource  FoodOptions.noSource(facts) != null: the food desire is parked, no way to food
     * @param bagEnergy FoodModel.energy(bag)
     * @param target    FoodModel.target (the predicted need, energy points)
     */
    public static boolean decide(boolean drains, boolean noSource, double bagEnergy, double target) {
        return drains && noSource && bagEnergy < target;
    }

    /**
     * aa-costs (replaces the yes/no rule above in the AA tick): sprinting's hunger cost (measured drain) against the
     * time it saves, weighted by food scarcity (Costs.sprint); its terms say why.
     */
    public static Costs.Sprint weigh(boolean drains, boolean noSource, double bagEnergy, double target, double drainScale) {
        return Costs.sprint(drains, noSource, bagEnergy, target, drainScale);
    }

    /** True when the bag already covers the predicted need (the cheap pre-check before asking for a source). */
    public static boolean covered(double bagEnergy, double target) { return bagEnergy >= target; }

    private static volatile long renewedAt = -1;

    /** The AA tick: conserving now (renews the flag) or not (clears it). */
    public static void set(boolean on, long now) { renewedAt = on ? now : -1; }

    public static boolean active(long now) {
        long t = renewedAt;
        return t >= 0 && now - t < LAPSE_MS;
    }
}
