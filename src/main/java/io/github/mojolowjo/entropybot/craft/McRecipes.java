package io.github.mojolowjo.entropybot.craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link RecipeSource} over the CLIENT's recipe manager (what the server synced): every recipe with a fixed result and
 * fixed ingredients (crafting, smelting, stonecutting, modded types...), indexed by output and by input once and
 * rebuilt when the level, the recipe manager or its recipe count changes, or after {@link #invalidate()} (the wiring
 * can call it from RecipesUpdatedEvent / TagsUpdatedEvent). Special recipes (CustomRecipe: dyeing, repair, firework
 * stars...) are left out. The only class of the planner that touches Minecraft; call it on the client thread.
 */
public final class McRecipes implements RecipeSource {
    private ClientLevel builtLevel;
    private RecipeManager builtManager;
    private int builtSize = -1;
    private boolean dirty = true;
    private Map<String, List<RecipeData>> byOutput = Map.of(), byInput = Map.of();
    private Map<String, RecipeData> byId = Map.of();
    private List<String> allIds;

    /** Forget the index; the next query rebuilds it. */
    public synchronized void invalidate() {
        dirty = true;
    }

    private synchronized void ensure() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            byOutput = Map.of();
            byInput = Map.of();
            byId = Map.of();
            builtLevel = null;
            builtManager = null;
            dirty = true;
            return;
        }
        RecipeManager rm = level.getRecipeManager();
        int size = rm.getRecipes().size();
        if (!dirty && level == builtLevel && rm == builtManager && size == builtSize) return;
        rebuild(level, rm);
        builtLevel = level;
        builtManager = rm;
        builtSize = size;
        dirty = false;
    }

    private void rebuild(ClientLevel level, RecipeManager rm) {
        Map<String, List<RecipeData>> out = new HashMap<>(), in = new HashMap<>();
        Map<String, RecipeData> ids = new HashMap<>();
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            RecipeData d;
            try {
                d = convert(holder, level);
            } catch (RuntimeException e) {
                continue;   // a modded recipe that can't answer on the client: not one the bot can make
            }
            if (d == null) continue;
            ids.put(d.id(), d);
            out.computeIfAbsent(d.output(), k -> new ArrayList<>()).add(d);
            Set<String> seen = new LinkedHashSet<>();
            for (List<String> cell : d.cells()) seen.addAll(cell);
            for (String s : seen) in.computeIfAbsent(s, k -> new ArrayList<>()).add(d);
        }
        // a stable order (the manager's is a hash order): by recipe id
        Comparator<RecipeData> byRecipeId = Comparator.comparing(RecipeData::id);
        for (List<RecipeData> l : out.values()) l.sort(byRecipeId);
        for (List<RecipeData> l : in.values()) l.sort(byRecipeId);
        byOutput = out;
        byInput = in;
        byId = ids;
    }

    private static RecipeData convert(RecipeHolder<?> holder, ClientLevel level) {
        Recipe<?> r = holder.value();
        if (r.isSpecial() || r instanceof CustomRecipe) return null;
        ItemStack result = r.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) return null;
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        if (type == null) return null;
        List<List<String>> cells = new ArrayList<>();
        boolean any = false;
        for (Ingredient ing : r.getIngredients()) {
            List<String> alts = alternatives(ing);
            any |= !alts.isEmpty();
            cells.add(alts);
        }
        if (!any) return null;
        String output = itemId(result);
        int outCount = Math.max(1, result.getCount());
        if (r instanceof ShapedRecipe s && s.getWidth() * s.getHeight() == cells.size()) {
            return new RecipeData(holder.id().toString(), type.toString(), output, outCount, s.getWidth(), s.getHeight(), false, cells);
        }
        // shapeless, a furnace recipe, or a modded crafting recipe of its own kind: its ingredients in order
        return new RecipeData(holder.id().toString(), type.toString(), output, outCount, 0, 0, true, cells);
    }

    /** An ingredient's items (tags expanded by the game), each id once, in order; empty for an empty cell. */
    private static List<String> alternatives(Ingredient ing) {
        if (ing == null || ing.isEmpty()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack st : ing.getItems()) if (st != null && !st.isEmpty()) ids.add(itemId(st));
        return List.copyOf(ids);
    }

    private static String itemId(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    @Override
    public List<RecipeData> recipesFor(String item) {
        ensure();
        List<RecipeData> l = byOutput.get(item);
        return l == null ? List.of() : List.copyOf(l);
    }

    @Override
    public List<RecipeData> recipesUsing(String item) {
        ensure();
        List<RecipeData> l = byInput.get(item);
        return l == null ? List.of() : List.copyOf(l);
    }

    @Override
    public RecipeData recipe(String recipeId) {
        ensure();
        return byId.get(recipeId);
    }

    @Override
    public boolean exists(String item) {
        ResourceLocation rl = ResourceLocation.tryParse(item);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    @Override
    public synchronized List<String> allItemIds() {
        if (allIds == null) {
            List<String> l = new ArrayList<>();
            for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) l.add(rl.toString());
            allIds = List.copyOf(l);
        }
        return allIds;
    }
}
