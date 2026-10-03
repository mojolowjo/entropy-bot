package io.github.mojolowjo.entropybot.craft;

import java.util.List;

/**
 * The game side of the crafting planner, without Minecraft types: the recipes and the item registry. In game it is
 * {@link McRecipes} (the client's recipe manager); tests use a fake.
 */
public interface RecipeSource {
    /** Every recipe that makes {@code item} (crafting, smelting and any other type with fixed ingredients), in a stable order. */
    List<RecipeData> recipesFor(String item);

    /** Every recipe that takes {@code item} in one of its cells. */
    List<RecipeData> recipesUsing(String item);

    /** The recipe with this id, or null. */
    RecipeData recipe(String recipeId);

    /** Is {@code item} a registered item id? */
    boolean exists(String item);

    /** Every registered item id, in a stable order (the registry's key set, as the bridge's allItemIds; ties in resolveItem go to the first). */
    List<String> allItemIds();
}
