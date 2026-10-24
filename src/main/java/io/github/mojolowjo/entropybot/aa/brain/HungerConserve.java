package io.github.mojolowjo.entropybot.aa.brain;

/**
 * Owner's idea (aa-sprint): with no clear food source and little food in the bag, sprinting's hunger cost is weighed
 * against the time it saves (Costs.sprint, noted for aa why). 0.39.z38 (owner: parkour needs sprint): the mod no longer
 * sets Baritone's {@code allowSprint}; the old flag that flipped it is gone. Baritone sprints as it plans, so no walk
 * is slower and no walk price changes with it. Pure.
 */
public final class HungerConserve {
    private HungerConserve() {}

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

}
