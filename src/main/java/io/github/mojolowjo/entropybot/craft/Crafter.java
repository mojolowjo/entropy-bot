package io.github.mojolowjo.entropybot.craft;

import java.util.List;
import java.util.Map;

/**
 * B7c's shared contract: the crafting planner the rest of the mod (craft/smelt/get/restock/need, the farm round,
 * compact) talks to. A faithful port of the bridge's planCraft/planSmelt/recipeNeeds (claude_bridge.js), with the
 * client's RecipeManager instead of EMI behind it. No Minecraft types here, so everything that uses it can be tested
 * with a stub.
 *
 * <p>Item ids are full ids ("minecraft:stick", "mysticalagriculture:inferium_essence"); callers resolve names first.
 * Error texts use the bridge's wording and short ids (no "minecraft:" prefix), e.g. "missing 3 stick",
 * "missing 2 oak_planks (or similar)", "there is no crafting recipe for bedrock",
 * "no fuel (coal or charcoal) to smelt 4 raw_iron".
 *
 * <p>Do not change this file without the owner and the session that wires it agreeing: three branches build on it.
 */
public interface Crafter {

    /** One step of a plan, in the order it has to run. */
    sealed interface Step permits Craft, Smelt {
        /** The item this step makes. */
        String item();

        /** How many of it the plan counts on from this step (the bridge's step.want). */
        int want();
    }

    /**
     * A crafting-grid step: run {@code recipeId} {@code times} times, making {@code want} of {@code item} (want is
     * what was asked for; times * the recipe's output count may be more). {@code needsTable}: the recipe does not fit
     * the inventory's 2x2 grid.
     */
    record Craft(String recipeId, String item, int want, int times, boolean needsTable) implements Step {}

    /**
     * A furnace step (minecraft:smelting): put {@code n} of {@code input} (one kind) and {@code fuelCount} of
     * {@code fuel} into a furnace, collect {@code want} of {@code item} (n * the recipe's output count).
     */
    record Smelt(String recipeId, String item, int want, String input, int n, String fuel, int fuelCount)
            implements Step {}

    /**
     * A plan: {@code steps} in order (missing ingredients first, the asked-for item last) and {@code error} null, or
     * no steps and {@code error} = why not, in the bridge's words (planCraft's reason, without the "item: " prefix
     * the bridge's planAll adds for a list of targets).
     */
    record Plan(List<Step> steps, String error) {
        public boolean ok() {
            return error == null;
        }

        public static Plan fail(String why) {
            return new Plan(List.of(), why);
        }
    }

    /**
     * Plans making {@code n} of {@code item} from {@code counts} (item id -> how many are available: the inventory,
     * plus whatever storage the caller counts in), crafting or smelting missing ingredients up to two levels deep,
     * like the bridge. On success {@code counts} is updated in place to what is left afterwards (ingredients and fuel
     * used up, the made items added), so planning several targets in a row threads one map through, like planAll; on
     * failure {@code counts} is left as it was.
     */
    Plan plan(String item, int n, Map<String, Integer> counts);

    /** One ingredient of a recipe, merged: any of {@code alts} (item ids, in the recipe's order), {@code amount} in all. */
    record Need(List<String> alts, int amount) {}

    /**
     * A recipe as the planner sees it: its {@code needs} merged by ingredient (the bridge's recipeNeeds), making
     * {@code outCount} of {@code output}. {@code type} is "minecraft:crafting" or "minecraft:smelting".
     */
    record Recipe(String recipeId, String type, String output, int outCount, List<Need> needs, boolean needsTable) {}

    /** The crafting-grid recipes that make {@code item}, without repair recipes (the bridge's craftingRecipes). */
    List<Recipe> craftingRecipesFor(String item);

    /** The crafting-grid recipes that use {@code item} as an ingredient (what compactPair asked EMI's getRecipesByInput). */
    List<Recipe> craftingRecipesUsing(String item);
}
