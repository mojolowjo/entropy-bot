package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.aa.brain.FoodModel;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.brain.Failures;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * aa-food (live aaep1: hunger 6, 16 animals in reach, and the food step knew only storage and bread from a farm): the
 * FOOD rung's ways, scored like every rung's ({@link Options}). Cost in walk-blocks (1 minute = {@link Options#MINUTE});
 * a way that yields little is charged once per round it needs to fill the shortfall.
 *
 * <ul>
 * <li>eat: food in the bag and the bar down by at least what the best of it restores (or urgent): {@code eat}.</li>
 * <li>chain (one-planner): any plain food (the game's FOOD data), the cheapest per point ({@code NeedChain.resolveAny}):
 *     cook what is raw, hunt a known animal (the loot data's mob drops) and cook it when a furnace is at hand, a crop or
 *     a bush seen (the loot data's block drops), a recipe (bread, stew); else explore.</li>
 * <li>wheat: no farm yet, 4+ seeds (or a grass patch to dig for them) and water near, not hungry: plant a field and mark
 *     it {@code place farm}: slow (8 min to grow) but endless; the chain's bread then has a source.</li>
 * <li>Kelp in the water and fishing are named with why they cannot run (the kelp and fish steps of the steps machine).</li>
 * </ul>
 * {@link #noSource} is the desire's park reason: no row at all (re-checked whenever the facts refresh). Pure (JUnit
 * FoodOptionsTest). Loader notes: none (the facts come from AaRuntime.foodFacts, vanilla blocks and entities).
 */
public final class FoodOptions {
    private FoodOptions() {}

    public static final String FACTS = "food.facts";

    /**
     * @param bag           item path -> count
     * @param target        the stock target (FoodModel.target), energy points
     * @param furnace       a furnace known or in the bag
     * @param animals       allowed animals in the hunt's reach (cow, pig, sheep, chicken -> count)
     * @param huntWhy       why hunting may not run (null: it may)
     * @param oak           the nearest oak or dark-oak log ({x,y,z}) or null; oakType "oak" | "dark_oak"
     * @param berry         the nearest ripe sweet-berry bush or null
     * @param red,brown     the nearest red / brown mushroom or null
     * @param grass         a grass patch's two corners {x1,z1,x2,z2} or null
     * @param waterNear     water within reach (for a field)
     * @param farmKnown     a farm is marked (place farm)
     * @param storedFood    food items known in storage
     * @param kelpNear      kelp seen in water near
     * @param me            the bot's position
     * @param huntDist      the walk to the nearest allowed animal (0: none or unknown)
     * @param waterSpot     a dry standing cell next to a water source the policy allows (a field spot by water), or null
     */
    public record FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                            Map<String, Integer> animals, String huntWhy,
                            int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                            int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me,
                            float health, double huntDist, int[] waterSpot, List<Herd> herds) {
        /** AA_ROUND3 5: without the remembered herds. */
        public FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                         Map<String, Integer> animals, String huntWhy,
                         int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                         int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me,
                         float health, double huntDist, int[] waterSpot) {
            this(food, saturation, bag, target, furnace, animals, huntWhy, oak, oakType, oakDist, berry, berryDist, red, brown,
                    grass, waterNear, farmKnown, storedFood, kelpNear, me, health, huntDist, waterSpot, List.of());
        }

        /** Without health (20), the walk to the animals (0) and a field spot by water. */
        public FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                         Map<String, Integer> animals, String huntWhy,
                         int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                         int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me) {
            this(food, saturation, bag, target, furnace, animals, huntWhy, oak, oakType, oakDist, berry, berryDist, red, brown,
                    grass, waterNear, farmKnown, storedFood, kelpNear, me, 20, 0, null);
        }

        int has(String path) { return bag == null ? 0 : bag.getOrDefault(path, 0); }
    }

    /**
     * AA_ROUND3 5: a herd I saw earlier (Sightings kind ANIMAL): the type's short name ("cow"), where, how many, the
     * decayed confidence (0..1) and the walk there from me.
     */
    public record Herd(String type, int x, int y, int z, int count, double conf, double dist) {}

    /** Herds this far or more are not offered (a round trip past the horizon). */
    public static double herdMax() { return Tuning.get("foodOptions.herdMax"); }
    public static double herdMinConf() { return Tuning.get("foodOptions.herdMinConf"); }

    /** Meat per animal (the hunt verb's names) and the cooked food's energy. */
    /** The short names of the animals whose loot table drops a food, and the raw meat each gives (LootBook data via HuntRules). */
    static java.util.Set<String> meatAnimals() {
        java.util.Set<String> s = new java.util.TreeSet<>();
        for (String a : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().keySet()) s.add(a.substring(a.indexOf(':') + 1));
        return s;
    }
    static String meatOf(String animal) {
        String m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().get("minecraft:" + animal);
        return m == null ? null : m.substring(m.indexOf(':') + 1);
    }

    static double eatCost() { return Tuning.get("foodOptions.eatCost"); }
    /** Energy per round of the slow ways, read from the food data when scored (the data loads after the class does). */
    static double berryYield() { return Tuning.get("foodOptions.berryYieldK") * FoodModel.energyOf("sweet_berries"); }
    static double appleYield() { return FoodModel.energyOf("apple"); }
    static double stewYield() { return FoodModel.energyOf("mushroom_stew"); }

    private static final Pattern END = Pattern.compile("no path|no progress|timeout|stuck|couldn't|can't|missing|no animal|nothing|no furnace|no seeds|no water|not found|none");

    /** Ways that may end "done" with nothing gained (a tree without apples, a field still growing). */
    static final java.util.Set<String> SLOW = java.util.Set.of("berries", "stew", "apples", "wheat", "wheat bucket", "kelp", "fish");

    public static final class Food implements Options.OptionSet<FoodFacts> {
        private String lastKey;
        private double lastE;

        @Override public Options.Choice choose(FoodFacts f, Options.OptionMemory m, long now, List<Step> planned, double pc, String plannerWhy, String afterFail) {
            Options.Choice c = choose0(f, m, now, planned, pc, plannerWhy);
            lastKey = c.best() == null ? null : c.best().memoryKey();
            lastE = FoodModel.energy(f.bag());
            return c;
        }

        private Options.Choice choose0(FoodFacts f, Options.OptionMemory m, long now, List<Step> planned, double pc, String plannerWhy) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            double bagE = FoodModel.energy(f.bag());
            // review: a slow way picked again with no food gained since is charged as a failure (no endless re-runs)
            if (lastKey != null && SLOW.contains(lastKey) && bagE <= lastE + Tuning.get("foodOptions.noGain") && !m.blocked(lastKey, now)) {
                m.failed(lastKey, "no food gained last time", now);
                lastKey = null;
            }
            double short_ = Math.max(FoodModel.minStock(), f.target() - bagE);
            boolean urgent = f.food() <= FoodModel.urgentFood();
            int best = FoodModel.bestNutrition(f.bag());

            // eat from the bag first
            if (best > 0 && (urgent || 20 - f.food() >= best))  // game fact: the food bar is 20 points
                RungOptions.add(rows, dropped, m, now, Options.typed("eat", "eat", List.of(new Step.Eat()), eatCost(), "the bag's best gives " + best, "eat", m));

            wheat(f, m, now, rows, dropped, urgent);

            // water ways (aa-kelpfish): kelp columns -> dried kelp, a rod -> fish; costed per round like the other slow ways
            for (var w : io.github.mojolowjo.entropybot.camp.FoodPlan.waterWays(f.bag() == null ? Map.of() : f.bag(), f.kelpNear(), f.waterNear(), f.furnace(), dropped)) {
                boolean kelp = w.name().equals("kelp");
                double each = kelp ? Tuning.i("foodOptions.kelpPerTrip") * FoodModel.energyOf("dried_kelp") : Tuning.i("foodOptions.fishPerTrip") * FoodModel.energyOf(f.furnace() ? "cooked_cod" : "cod");
                RungOptions.add(rows, dropped, m, now, Options.typed(w.name(), "water", w.steps(),
                        Options.MINUTE * (kelp ? 4 : 5) * rounds(short_, each), w.why(), w.name(), m));
            }

            // one-planner: getting food is the chain's (any plain food, the cheapest per point: hunt a known animal and cook
            // it when a furnace is at hand, a crop, a recipe; else explore); eating, a field and the water ways stay rows
            if (planned == null) dropped.add("chain: " + (plannerWhy == null ? "no chain" : plannerWhy));
            else RungOptions.add(rows, dropped, m, now, Options.typed("chain", "chain", planned, pc, plannerWhy == null ? "the chain" : plannerWhy, "chain food", true, m));
            return Options.Choice.of(rows, dropped, "no way to food");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return Failures.classify(end) != null || END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    static void wheat(FoodFacts f, Options.OptionMemory m, long now, List<Options.Option> rows, List<String> dropped, boolean urgent) {
        if (f.farmKnown()) return;                          // the chain's bread has its source
        if (urgent) { dropped.add("wheat: too slow while hungry (8 min to grow)"); return; }
        int seeds = f.has("wheat_seeds");
        List<Step> seedsStep = new ArrayList<>();
        double seedsCost;
        if (seeds >= Tuning.i("foodOptions.seedsEnough")) seedsCost = 0;
        else if (f.grass() != null) {
            seedsStep.add(new Step.DigArea(f.grass(), 1));
            seedsCost = Options.MINUTE * Tuning.i("foodOptions.seedMin");
        } else { dropped.add("wheat: " + seeds + "/4 seeds and no grass patch near"); return; }
        String seedWhy = seeds >= Tuning.i("foodOptions.seedsEnough") ? seeds + " seeds" : "seeds from a grass patch";
        // aa383 (the field failed "no water within 4 - give me a water bucket"): the field goes where water is (a dry cell
        // next to a water source, AaRuntime.foodFacts), or here with a water bucket from the bag; each costed
        if (f.waterSpot() != null) {
            int[] w = f.waterSpot();
            RungOptions.add(rows, dropped, m, now, Options.typed("wheat by water", "farm",
                    Options.seq(seedsStep, new Step.Walk(w[0], w[1], w[2], 0), new Step.FarmPlant("wheat", 4), new Step.MarkFarm(w)),
                    Options.MINUTE * Tuning.i("foodOptions.wheatFieldMin") + seedsCost + dist(f.me(), w), seedWhy + ", a field by the water at " + w[0] + " " + w[1] + " " + w[2] + " (slow, endless)", "wheat", m));
        } else dropped.add("wheat by water: no dry spot next to water near");
        if (f.has("water_bucket") > 0) {
            RungOptions.add(rows, dropped, m, now, Options.typed("wheat with a bucket", "farm", Options.seq(seedsStep, new Step.FarmPlant("wheat", 4), new Step.MarkFarm(f.me())),
                    Options.MINUTE * Tuning.i("foodOptions.wheatBucketMin") + seedsCost, seedWhy + ", water from my bucket: a field here (slow, endless)", "wheat bucket", m));
        } else dropped.add("wheat with a bucket: no water bucket" + (f.has("bucket") > 0 ? " (an empty one: no verb fills it yet)" : ""));
    }

    /** The rounds a way that yields {@code each} needs for the shortfall (1..8). */
    static double rounds(double shortfall, double each) {
        return each <= 0 ? 8 : Math.max(1, Math.min(8, Math.ceil(shortfall / each)));
    }

    /** Break the one block at {@code a} (a bush, a mushroom). */
    static Step dig(int[] a) { return new Step.Dig(a, a, false, false); }

    static double dist(int[] a, int[] b) {
        if (a == null || b == null) return 16;
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The food desire's park reason: no way can run (the planner counts while storage holds food or a farm is marked).
     * Null: a source exists.
     */
    public static String noSource(FoodFacts f) {
        if (f == null) return null;                          // unknown: never park on missing facts
        if (f.storedFood() > 0 || f.farmKnown()) return null;
        Options.Choice c = new Food().choose(f, new Options.OptionMemory(), 0, null, 0, "no stored food and no farm", null);
        return c.best() != null ? null : "no food source: " + String.join("; ", c.dropped());
    }
}

