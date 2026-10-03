package io.github.mojolowjo.entropybot.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One recipe as the game has it, without Minecraft types: what {@link RecipeSource} hands the planner and
 * {@link GridLayout}. {@code cells} are the recipe's ingredients in order, each a list of alternative item ids (empty =
 * an empty cell): for a shaped recipe {@code width * height} cells row-major, for a shapeless one (and a furnace
 * recipe) just the ingredients ({@code width} and {@code height} are 0 then).
 *
 * @param type the recipe type's id: "minecraft:crafting", "minecraft:smelting", "minecraft:stonecutting"...
 */
public record RecipeData(String id, String type, String output, int outCount, int width, int height, boolean shapeless,
                         List<List<String>> cells) {
    public static final String CRAFTING = "minecraft:crafting";
    public static final String SMELTING = "minecraft:smelting";

    public RecipeData {
        List<List<String>> c = new ArrayList<>(cells.size());
        for (List<String> cell : cells) c.add(cell == null ? List.of() : List.copyOf(cell));
        cells = List.copyOf(c);
        if (outCount < 1) outCount = 1;
    }

    /** A shaped crafting recipe: {@code cells} row-major, {@code width * height} of them. */
    public static RecipeData shaped(String id, String output, int outCount, int width, int height, List<List<String>> cells) {
        if (cells.size() != width * height) throw new IllegalArgumentException("a " + width + "x" + height + " recipe needs " + width * height + " cells");
        return new RecipeData(id, CRAFTING, output, outCount, width, height, false, cells);
    }

    /** A shapeless crafting recipe. */
    public static RecipeData shapeless(String id, String output, int outCount, List<List<String>> ingredients) {
        return new RecipeData(id, CRAFTING, output, outCount, 0, 0, true, ingredients);
    }

    /** A furnace recipe (minecraft:smelting): one ingredient. */
    public static RecipeData smelting(String id, String output, int outCount, List<String> input) {
        return new RecipeData(id, SMELTING, output, outCount, 0, 0, true, List.of(input));
    }

    public boolean crafting() {
        return CRAFTING.equals(type);
    }

    /** How many cells hold something. */
    public int filledCells() {
        int n = 0;
        for (List<String> c : cells) if (!c.isEmpty()) n++;
        return n;
    }

    /** Does it fit a {@code size} x {@code size} crafting grid (2 = the inventory's, 3 = a table's)? */
    public boolean fits(int size) {
        return shapeless ? filledCells() <= size * size : width <= size && height <= size;
    }

    /** A crafting recipe that does not fit the inventory's 2x2 grid. */
    public boolean needsTable() {
        return crafting() && !fits(2);
    }

    /** Does any cell take {@code item}? (the bridge's usesItself: repair recipes use the item they make) */
    public boolean uses(String item) {
        for (List<String> c : cells) if (c.contains(item)) return true;
        return false;
    }

    /** The bridge's recipeNeeds: the ingredients merged by their alternatives (in order), one per cell. */
    public List<Crafter.Need> needs() {
        Map<String, List<String>> alts = new LinkedHashMap<>();
        Map<String, Integer> amount = new LinkedHashMap<>();
        for (List<String> c : cells) {
            if (c.isEmpty()) continue;
            String key = String.join(",", c);
            alts.putIfAbsent(key, c);
            amount.merge(key, 1, Integer::sum);
        }
        List<Crafter.Need> out = new ArrayList<>(alts.size());
        for (Map.Entry<String, List<String>> e : alts.entrySet()) out.add(new Crafter.Need(e.getValue(), amount.get(e.getKey())));
        return out;
    }

    /** As the contract's {@link Crafter.Recipe}. */
    public Crafter.Recipe toRecipe() {
        return new Crafter.Recipe(id, type, output, outCount, needs(), needsTable());
    }
}
