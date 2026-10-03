package io.github.mojolowjo.entropybot.farm;

import io.github.mojolowjo.entropybot.craft.Crafter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A Crafter with a handful of single-level recipes, for the farm and compact tests. */
final class StubCrafter implements Crafter {
    final List<Recipe> recipes = new ArrayList<>();
    int plans;

    StubCrafter add(String id, String output, int outCount, boolean table, Object... needs) {
        List<Need> list = new ArrayList<>();
        for (int i = 0; i < needs.length; i += 2) list.add(new Need(List.of(((String) needs[i]).split("\\|")), (Integer) needs[i + 1]));
        recipes.add(new Recipe(id, "minecraft:crafting", output, outCount, list, table));
        return this;
    }

    static StubCrafter standard() {
        return new StubCrafter()
                .add("mysticalagriculture:inferium_block", "mysticalagriculture:inferium_block", 1, true, "mysticalagriculture:inferium_essence", 9)
                .add("mysticalagriculture:inferium_essence_from_block", "mysticalagriculture:inferium_essence", 9, false, "mysticalagriculture:inferium_block", 1)
                .add("mysticalagriculture:prudentium_essence", "mysticalagriculture:prudentium_essence", 1, true,
                        "mysticalagriculture:inferium_essence", 4, "mysticalagriculture:infusion_crystal", 1)
                .add("minecraft:iron_block", "minecraft:iron_block", 1, true, "minecraft:iron_ingot", 9)
                .add("minecraft:iron_ingot_from_iron_block", "minecraft:iron_ingot", 9, false, "minecraft:iron_block", 1)
                .add("minecraft:iron_ingot_from_nuggets", "minecraft:iron_ingot", 1, true, "minecraft:iron_nugget", 9)
                .add("minecraft:iron_nugget", "minecraft:iron_nugget", 9, false, "minecraft:iron_ingot", 1)
                .add("minecraft:iron_trapdoor", "minecraft:iron_trapdoor", 1, false, "minecraft:iron_ingot", 4)
                .add("minecraft:quartz_block", "minecraft:quartz_block", 1, false, "minecraft:quartz", 4)
                .add("minecraft:oak_planks", "minecraft:oak_planks", 4, false, "minecraft:oak_log|minecraft:stripped_oak_log", 1)
                .add("minecraft:stick", "minecraft:stick", 4, false, "minecraft:oak_planks|minecraft:spruce_planks", 2);
    }

    @Override public Plan plan(String item, int n, Map<String, Integer> counts) {
        plans++;
        for (Recipe r : craftingRecipesFor(item)) {
            int times = (n + r.outCount() - 1) / r.outCount();
            Map<String, Integer> after = new HashMap<>(counts);
            String missing = null;
            for (Need need : r.needs()) {
                int want = need.amount() * times;
                for (String alt : need.alts()) {
                    int take = Math.min(want, after.getOrDefault(alt, 0));
                    after.merge(alt, -take, Integer::sum);
                    want -= take;
                }
                if (want > 0 && missing == null) missing = "missing " + want + " " + Compact.shortId(need.alts().get(0));
            }
            if (missing != null) return Plan.fail(missing);
            after.merge(item, times * r.outCount(), Integer::sum);
            counts.clear();
            counts.putAll(after);
            return new Plan(List.of(new Craft(r.recipeId(), item, n, times, r.needsTable())), null);
        }
        return Plan.fail("there is no crafting recipe for " + Compact.shortId(item));
    }

    @Override public List<Recipe> craftingRecipesFor(String item) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : recipes) if (r.output().equals(item)) out.add(r);
        return out;
    }

    @Override public List<Recipe> craftingRecipesUsing(String item) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : recipes) for (Need need : r.needs()) if (need.alts().contains(item)) { out.add(r); break; }
        return out;
    }
}
