package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.FoodModel;
import io.github.mojolowjo.entropybot.brain.FailMemory;

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
 * <li>cook: raw meat, potatoes or kelp in the bag and a furnace (known or in the bag): {@code cook <raw> n}.</li>
 * <li>hunt: allowed animals within reach in my areas (hunting not switched off by the owner): {@code hunt n <animal>}
 *     then {@code cook <meat> n} with a furnace (raw beef/pork/mutton without one; never raw chicken).</li>
 * <li>berries: a ripe sweet-berry bush seen: {@code dig} its cell (a broken ripe bush drops 2-3 berries).</li>
 * <li>stew: a red and a brown mushroom seen or had, a bowl or 3 planks: dig them, {@code craft bowl}, {@code craft mushroom_stew}.</li>
 * <li>apples: oak or dark-oak trees near: {@code cut 16 <type>} (apples fall from the decaying leaves, about one per
 *     16 logs; the logs are kept).</li>
 * <li>wheat: no farm yet, 4+ seeds (or a grass patch to dig for them) and water near, not hungry: plant a field and mark
 *     it {@code place farm}: slow (8 min to grow) but endless; the planner's bread then has a source.</li>
 * <li>planner: {@code goal food n} (storage, bread from the farm).</li>
 * <li>Kelp in the water and fishing are named with why they cannot run (no under-water harvest or fishing verb yet).</li>
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
     * @param animals       allowed animals in reach inside my areas (cow, pig, sheep, chicken -> count)
     * @param animalsOutside allowed animals seen outside my areas
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
     * @param outAt         the nearest allowed animal outside my areas (within twice the hunt's reach), or null
     * @param waterSpot     a dry standing cell next to a water source inside my areas (a field spot by water), or null
     */
    public record FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                            Map<String, Integer> animals, int animalsOutside, String huntWhy,
                            int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                            int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me,
                            Map<String, Integer> animalsOut, float health, double outDist, int[] outAt, int[] waterSpot) {
        /** Without the nearest outside animal's place and a field spot by water. */
        public FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                         Map<String, Integer> animals, int animalsOutside, String huntWhy,
                         int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                         int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me,
                         Map<String, Integer> animalsOut, float health, double outDist) {
            this(food, saturation, bag, target, furnace, animals, animalsOutside, huntWhy, oak, oakType, oakDist, berry, berryDist, red, brown,
                    grass, waterNear, farmKnown, storedFood, kelpNear, me, animalsOut, health, outDist, null, null);
        }

        /** Without the nearest outside animal's distance (half the hunt's reach). */
        public FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                         Map<String, Integer> animals, int animalsOutside, String huntWhy,
                         int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                         int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me,
                         Map<String, Integer> animalsOut, float health) {
            this(food, saturation, bag, target, furnace, animals, animalsOutside, huntWhy, oak, oakType, oakDist, berry, berryDist, red, brown,
                    grass, waterNear, farmKnown, storedFood, kelpNear, me, animalsOut, health, io.github.mojolowjo.entropybot.vocab.HuntRules.OUT_R / 2.0);
        }

        /** Without the outside-areas facts (none seen, health 20). */
        public FoodFacts(int food, double saturation, Map<String, Integer> bag, double target, boolean furnace,
                         Map<String, Integer> animals, int animalsOutside, String huntWhy,
                         int[] oak, String oakType, double oakDist, int[] berry, double berryDist, int[] red, int[] brown,
                         int[] grass, boolean waterNear, boolean farmKnown, int storedFood, boolean kelpNear, int[] me) {
            this(food, saturation, bag, target, furnace, animals, animalsOutside, huntWhy, oak, oakType, oakDist, berry, berryDist, red, brown,
                    grass, waterNear, farmKnown, storedFood, kelpNear, me, Map.of(), 20);
        }

        int has(String path) { return bag == null ? 0 : bag.getOrDefault(path, 0); }
    }

    /** Meat per animal (the hunt verb's names) and the cooked food's energy. */
    public static final java.util.Set<String> MEAT_ANIMALS = java.util.Set.of("cow", "pig", "sheep", "chicken");
    static final Map<String, String> MEAT =Map.of("cow", "beef", "pig", "porkchop", "sheep", "mutton", "chicken", "chicken");

    static final double EAT_COST = 2, BERRY_YIELD = 2.5 * FoodModel.energyOf("sweet_berries"),
            APPLE_YIELD = FoodModel.energyOf("apple"), STEW_YIELD = FoodModel.energyOf("mushroom_stew");

    private static final Pattern END = Pattern.compile("no path|no progress|timeout|stuck|couldn't|can't|missing|no animal|nothing|outside|no furnace|no seeds|no water|not found|none");

    /** Ways that may end "done" with nothing gained (a tree without apples, a field still growing). */
    static final java.util.Set<String> SLOW = java.util.Set.of("berries", "stew", "apples", "wheat", "wheat bucket");

    public static final class Food implements Options.OptionSet<FoodFacts> {
        private String lastKey;
        private double lastE;

        @Override public Options.Choice choose(FoodFacts f, Options.OptionMemory m, long now, String planned, double pc, String plannerWhy, String afterFail) {
            Options.Choice c = choose0(f, m, now, planned, pc, plannerWhy);
            lastKey = c.best() == null ? null : c.best().memoryKey();
            lastE = FoodModel.energy(f.bag());
            return c;
        }

        private Options.Choice choose0(FoodFacts f, Options.OptionMemory m, long now, String planned, double pc, String plannerWhy) {
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            double bagE = FoodModel.energy(f.bag());
            // review: a slow way picked again with no food gained since is charged as a failure (no endless re-runs)
            if (lastKey != null && SLOW.contains(lastKey) && bagE <= lastE + 0.1 && !m.blocked(lastKey, now)) {
                m.failed(lastKey, "no food gained last time", now);
                lastKey = null;
            }
            double short_ = Math.max(FoodModel.MIN_STOCK, f.target() - bagE);
            boolean urgent = f.food() <= FoodModel.URGENT_FOOD;
            int best = FoodModel.bestNutrition(f.bag());

            // eat from the bag first
            if (best > 0 && (urgent || 20 - f.food() >= best))
                RungOptions.add(rows, dropped, m, now, Options.scored("eat", "eat", "eat", EAT_COST, "the bag's best gives " + best, "eat", false, m));

            // cook what is raw
            String raw = null;
            double gain = 0;
            for (var e : FoodModel.COOKS.entrySet()) {
                int n = f.has(e.getKey());
                double g = n * (FoodModel.energyOf(e.getValue()) - FoodModel.energyOf(e.getKey()));
                if (n > 0 && g > gain) { raw = e.getKey(); gain = g; }
            }
            if (raw != null) {
                int n = Math.min(16, f.has(raw));
                if (!f.furnace()) dropped.add("cook " + raw + ": no furnace known or in the bag");
                else RungOptions.add(rows, dropped, m, now, Options.scored("cook " + raw, "cook", "cook " + raw + " " + n,
                        Options.MINUTE * (0.5 + n / 6.0), n + " raw " + raw + " in the bag", "cook", false, m));
            }

            hunt(f, m, now, rows, dropped, short_);

            // berries, stew, apples: little per round, charged per round needed
            if (f.berry() != null)
                RungOptions.add(rows, dropped, m, now, Options.scored("berries", "forage", "dig " + box(f.berry(), f.berry()),
                        (f.berryDist() + Options.MINUTE * 0.3) * rounds(short_, BERRY_YIELD), "a ripe berry bush " + Math.round(f.berryDist()) + " away", "berries", false, m));
            stew(f, m, now, rows, dropped, short_);
            if (f.oak() != null)
                RungOptions.add(rows, dropped, m, now, Options.scored("apples", "apples", "cut 16 " + f.oakType(),
                        Options.MINUTE * 4 * rounds(short_, APPLE_YIELD), "apples fall from decaying " + f.oakType() + " leaves, about 1 per 16 logs", "apples", false, m));

            wheat(f, m, now, rows, dropped, urgent);

            if (f.kelpNear() && f.has("kelp") == 0) dropped.add("kelp: seen in the water, no under-water harvest verb yet");
            if (f.has("string") >= 2 || f.has("fishing_rod") > 0) dropped.add("fishing: no fishing verb yet");

            if (planned == null) dropped.add("planner: " + (plannerWhy == null ? "no plan" : plannerWhy));
            else RungOptions.add(rows, dropped, m, now, Options.scored("planner", "planner", planned, pc * Options.PLAN_UNIT,
                    "goal food (storage" + (f.farmKnown() ? ", bread from the farm" : "") + ")", "planner", true, m));
            return Options.Choice.of(rows, dropped, "no way to food");
        }

        @Override public boolean switchable(String end) {
            if (end == null || Options.ownerOrDanger(end)) return false;
            return FailMemory.classify(end) != null || END.matcher(end.toLowerCase(Locale.ROOT)).find();
        }
    }

    static void hunt(FoodFacts f, Options.OptionMemory m, long now, List<Options.Option> rows, List<String> dropped, double short_) {
        if (f.huntWhy() != null) { dropped.add("hunt: " + f.huntWhy()); return; }
        String animal = most(f.animals(), f.furnace());
        if (animal == null) dropped.add("hunt: no animal in reach inside my areas" + (f.animalsOutside() > 0 ? " (" + f.animalsOutside() + " outside them)" : "")
                + (f.furnace() || f.animals() == null || f.animals().getOrDefault("chicken", 0) == 0 ? "" : " (chickens only, no furnace)"));
        else huntRow(f, m, now, rows, dropped, short_, animal, f.animals().get(animal), false);
        // owner's rule 2026-10-19 (aa383: a food stock never walked out to animals): walking out is a costed option at any
        // hunger, beside the hunt inside: the travel (there and back) divided by the food urgency (Costs.huntOutTravel)
        String outside = most(f.animalsOut(), f.furnace());
        if (outside != null) huntRow(f, m, now, rows, dropped, short_, outside, f.animalsOut().get(outside), true);
    }

    /** The animal with the most head (the meatiest first on a tie; no chicken without a furnace: raw chicken poisons). */
    static String most(Map<String, Integer> animals, boolean furnace) {
        if (animals == null) return null;
        String animal = null;
        int most = 0;
        for (String a : List.of("cow", "pig", "sheep", "chicken")) {
            int n = animals.getOrDefault(a, 0);
            if (a.equals("chicken") && !furnace) continue;
            if (n > most) { animal = a; most = n; }
        }
        return animal;
    }

    private static void huntRow(FoodFacts f, Options.OptionMemory m, long now, List<Options.Option> rows, List<String> dropped, double short_,
                                String animal, int most, boolean out) {
        String meat = MEAT.get(animal), cooked = FoodModel.COOKS.get(meat);
        double each = f.furnace() ? FoodModel.energyOf(cooked) : FoodModel.energyOf(meat);
        int n = Math.max(2, Math.min(8, FoodModel.count(short_, each)));
        // an animal past the hunt's own reach (HuntRules.OUT_R from where the hunt starts): walk to it first
        boolean far = out && f.outAt() != null && f.outDist() > io.github.mojolowjo.entropybot.vocab.HuntRules.OUT_R;
        String chain = (far ? "goto " + RungOptions.xyz(f.outAt()) + " then " : "") + "hunt " + n + " " + animal + (out ? " out" : "")
                + (f.furnace() ? " then cook " + meat + " " + n : "");
        double cost = Options.MINUTE * (1 + n * 0.5) + (f.furnace() ? Options.MINUTE * (0.5 + n / 6.0) : 0)
                + (out ? io.github.mojolowjo.entropybot.aa.brain.Costs.huntOutTravel(f.outDist(), f.food(), f.health()) : 0);      // there and back / urgency
        RungOptions.add(rows, dropped, m, now, Options.scored(out ? "hunt out " + animal : "hunt " + animal, "hunt", chain, cost,
                most + " " + animal + (out ? " outside my areas (" + io.github.mojolowjo.entropybot.aa.brain.Costs.huntOutTerms(f.outDist(), f.food(), f.health()) + ")" : " in reach")
                        + (f.furnace() ? ", cooked" : ", eaten raw: no furnace"), out ? "hunt out" : "hunt", false, m));
    }

    static void stew(FoodFacts f, Options.OptionMemory m, long now, List<Options.Option> rows, List<String> dropped, double short_) {
        boolean red = f.has("red_mushroom") > 0 || f.red() != null, brown = f.has("brown_mushroom") > 0 || f.brown() != null;
        if (!red && !brown) return;
        if (!red || !brown) { dropped.add("stew: only a " + (red ? "red" : "brown") + " mushroom seen"); return; }
        int planks = 0;
        if (f.bag() != null) for (var e : f.bag().entrySet()) if (e.getKey().endsWith("_planks")) planks += e.getValue();
        if (f.has("bowl") == 0 && planks < 3) { dropped.add("stew: no bowl and under 3 planks"); return; }
        List<String> steps = new ArrayList<>();
        double walk = 0;
        if (f.has("red_mushroom") == 0) { steps.add("dig " + box(f.red(), f.red())); walk += dist(f.me(), f.red()); }
        if (f.has("brown_mushroom") == 0) { steps.add("dig " + box(f.brown(), f.brown())); walk += dist(f.me(), f.brown()); }
        if (f.has("bowl") == 0) steps.add("craft bowl 1");
        steps.add("craft mushroom_stew 1");
        RungOptions.add(rows, dropped, m, now, Options.scored("stew", "forage", String.join(" then ", steps),
                (walk + Options.MINUTE) * rounds(short_, STEW_YIELD), "a red and a brown mushroom", "stew", false, m));
    }

    static void wheat(FoodFacts f, Options.OptionMemory m, long now, List<Options.Option> rows, List<String> dropped, boolean urgent) {
        if (f.farmKnown()) return;                          // the planner's bread has its source
        if (urgent) { dropped.add("wheat: too slow while hungry (8 min to grow)"); return; }
        int seeds = f.has("wheat_seeds");
        String seedsStep;
        double seedsCost;
        if (seeds >= 4) { seedsStep = ""; seedsCost = 0; }
        else if (f.grass() != null) {
            int[] g = f.grass();
            seedsStep = "dig " + g[0] + " " + g[1] + " " + g[2] + " " + g[3] + " +1 then ";
            seedsCost = Options.MINUTE * 3;
        } else { dropped.add("wheat: " + seeds + "/4 seeds and no grass patch near"); return; }
        String seedWhy = seeds >= 4 ? seeds + " seeds" : "seeds from a grass patch";
        // aa383 (the field failed "no water within 4 - give me a water bucket"): the field goes where water is (a dry cell
        // next to a water source, AaRuntime.foodFacts), or here with a water bucket from the bag; each costed
        if (f.waterSpot() != null) {
            int[] w = f.waterSpot();
            RungOptions.add(rows, dropped, m, now, Options.scored("wheat by water", "farm",
                    seedsStep + "goto " + RungOptions.xyz(w) + " then farm plant wheat here 4 then place farm " + RungOptions.xyz(w),
                    Options.MINUTE * 6 + seedsCost + dist(f.me(), w), seedWhy + ", a field by the water at " + RungOptions.xyz(w) + " (slow, endless)", "wheat", false, m));
        } else dropped.add("wheat by water: no dry spot next to water in my areas near");
        if (f.has("water_bucket") > 0) {
            String mark = f.me() == null ? "place farm" : "place farm " + RungOptions.xyz(f.me());
            RungOptions.add(rows, dropped, m, now, Options.scored("wheat with a bucket", "farm", seedsStep + "farm plant wheat here 4 then " + mark,
                    Options.MINUTE * 7 + seedsCost, seedWhy + ", water from my bucket: a field here (slow, endless)", "wheat bucket", false, m));
        } else dropped.add("wheat with a bucket: no water bucket" + (f.has("bucket") > 0 ? " (an empty one: no verb fills it yet)" : ""));
    }

    /** The rounds a way that yields {@code each} needs for the shortfall (1..8). */
    static double rounds(double shortfall, double each) {
        return each <= 0 ? 8 : Math.max(1, Math.min(8, Math.ceil(shortfall / each)));
    }

    static String box(int[] a, int[] b) { return RungOptions.xyz(a) + " " + RungOptions.xyz(b); }

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
