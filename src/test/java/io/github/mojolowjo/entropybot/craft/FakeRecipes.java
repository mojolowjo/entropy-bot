package io.github.mojolowjo.entropybot.craft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A RecipeSource for the tests: the items and recipes they add, in order. */
final class FakeRecipes implements RecipeSource {
    final Set<String> items = new LinkedHashSet<>();
    final List<RecipeData> recipes = new ArrayList<>();

    static String m(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    static List<String> alts(String... ids) {
        List<String> l = new ArrayList<>();
        for (String id : ids) l.add(m(id));
        return l;
    }

    FakeRecipes item(String... ids) {
        for (String id : ids) items.add(m(id));
        return this;
    }

    FakeRecipes add(RecipeData r) {
        recipes.add(r);
        items.add(r.output());
        for (List<String> c : r.cells()) items.addAll(c);
        return this;
    }

    /** Shaped from rows of cell names: "." empty, else a key of {@code keys} (pairs: key char, alternatives). */
    @SuppressWarnings("unchecked")
    FakeRecipes shaped(String id, String out, int n, String[] rows, Object... keys) {
        int h = rows.length, w = rows[0].length();
        List<List<String>> cells = new ArrayList<>();
        for (String row : rows) {
            for (char ch : row.toCharArray()) {
                List<String> cell = List.of();
                for (int i = 0; i < keys.length; i += 2) if (keys[i].equals(ch)) cell = (List<String>) keys[i + 1];
                cells.add(cell);
            }
        }
        return add(RecipeData.shaped(m(id), m(out), n, w, h, cells));
    }

    @SafeVarargs
    final FakeRecipes shapeless(String id, String out, int n, List<String>... ings) {
        return add(RecipeData.shapeless(m(id), m(out), n, List.of(ings)));
    }

    FakeRecipes smelting(String id, String out, int n, List<String> input) {
        return add(RecipeData.smelting(m(id), m(out), n, input));
    }

    @Override public List<RecipeData> recipesFor(String item) {
        List<RecipeData> l = new ArrayList<>();
        for (RecipeData r : recipes) if (r.output().equals(item)) l.add(r);
        return l;
    }

    @Override public List<RecipeData> recipesUsing(String item) {
        List<RecipeData> l = new ArrayList<>();
        for (RecipeData r : recipes) if (r.uses(item)) l.add(r);
        return l;
    }

    @Override public RecipeData recipe(String recipeId) {
        for (RecipeData r : recipes) if (r.id().equals(recipeId)) return r;
        return null;
    }

    @Override public boolean exists(String item) {
        return items.contains(item);
    }

    @Override public List<String> allItemIds() {
        return List.copyOf(items);
    }
}
