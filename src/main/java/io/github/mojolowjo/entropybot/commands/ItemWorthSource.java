package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AA_ROUND4 C adapter: reads the game's own item data (Tool, MAX_DAMAGE, attribute modifiers, FOOD, block hardness and
 * light) and the recipe manager once, into the pure {@link ItemWorth.Source}. Vanilla and modded alike: nothing is named.
 * A failure for one item leaves it without facts (an explicit unknown in {@code aa worth}); a failure of the whole read is
 * thrown to the caller, which logs it once.
 */
final class ItemWorthSource implements ItemWorth.Source {
    private final Map<String, ItemWorth.Facts> facts = new HashMap<>();
    private final Map<String, List<ItemWorth.Recipe>> recipes = new HashMap<>();
    private final List<String> smelts = new ArrayList<>();
    private final Map<String, String> cooks = new HashMap<>();
    private final java.util.Set<String> stoneLike = new java.util.HashSet<>(), planks = new java.util.HashSet<>(), stations = new java.util.HashSet<>(), ores = new java.util.HashSet<>();
    int failed;

    static ItemWorthSource read(Level level) {
        ItemWorthSource s = new ItemWorthSource();
        ItemWorth.logTo(m -> org.slf4j.LoggerFactory.getLogger("entropybot").warn("[entropybot] {}", m));
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (item.getDefaultInstance().is(net.minecraft.tags.ItemTags.PLANKS)) s.planks.add(id);
            try { ItemWorth.Facts f = factsOf(item); if (f != ItemWorth.Facts.NONE) s.facts.put(id, f); }
            catch (RuntimeException e) { s.failed++; }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem bi)) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            try { s.blockRoles(id, bi.getBlock().defaultBlockState(), level); } catch (RuntimeException e) { s.failed++; }
        }
        RecipeManager rm = level.getRecipeManager();
        for (var h : rm.getRecipes()) {
            var r = h.value();
            if (r instanceof CustomRecipe) continue;
            ItemStack out = r.getResultItem(level.registryAccess());
            if (out == null || out.isEmpty()) continue;
            String oid = BuiltInRegistries.ITEM.getKey(out.getItem()).toString();
            List<String> in = new ArrayList<>();
            boolean ok = true;
            for (var ing : r.getIngredients()) {
                ItemStack[] items = ing.getItems();
                if (items.length == 0) { if (!ing.isEmpty()) ok = false; continue; }
                in.add(BuiltInRegistries.ITEM.getKey(items[0].getItem()).toString());
            }
            if (!ok || in.isEmpty()) continue;
            s.recipes.computeIfAbsent(oid, k -> new ArrayList<>()).add(new ItemWorth.Recipe(Math.max(1, out.getCount()), in));
            if (r.getType() == RecipeType.SMELTING && !s.smelts.contains(oid)) s.smelts.add(oid);
            if ((r.getType() == RecipeType.SMELTING || r.getType() == RecipeType.SMOKING || r.getType() == RecipeType.CAMPFIRE_COOKING)
                    && out.getItem().getDefaultInstance().get(DataComponents.FOOD) != null) {
                for (var ing : r.getIngredients()) for (ItemStack raw : ing.getItems()) s.cooks.putIfAbsent(BuiltInRegistries.ITEM.getKey(raw.getItem()).toString(), oid);   // the game's cooking recipes: raw -> cooked, any mod
            }
        }
        return s;
    }

    /** Block roles from the game's tags and block class: ore, workstation (opens a menu), stone-like filler. */
    private void blockRoles(String id, BlockState bs, Level level) {
        if (bs.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) ores.add(id);
        boolean entity = bs.getBlock() instanceof net.minecraft.world.level.block.EntityBlock;
        try { if (bs.getMenuProvider(level, BlockPos.ZERO) != null) stations.add(id); } catch (RuntimeException e) { failed++; }
        if (bs.is(BlockTags.BASE_STONE_OVERWORLD) || bs.is(BlockTags.BASE_STONE_NETHER)) { stoneLike.add(id); return; }
        double hard = bs.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (!entity && hard > 0 && hard <= io.github.mojolowjo.entropybot.aa.brain.Tuning.get("junk.hardBand")
                && (bs.is(BlockTags.MINEABLE_WITH_PICKAXE) || bs.is(BlockTags.MINEABLE_WITH_SHOVEL))
                && !bs.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) && !bs.is(net.neoforged.neoforge.common.Tags.Blocks.STORAGE_BLOCKS)) stoneLike.add(id);
    }

    private static ItemWorth.Facts factsOf(Item item) {
        ItemStack st = item.getDefaultInstance();
        double dur = st.getMaxDamage();
        double def = 0, tough = 0, atk = 0, nut = 0, sat = 0, hard = 0;
        int light = 0, level = 0;
        double speed = 0;
        for (var e : st.getAttributeModifiers().modifiers()) {
            if (e.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            if (e.attribute().is(Attributes.ARMOR.unwrapKey().get())) def += e.modifier().amount();
            else if (e.attribute().is(Attributes.ARMOR_TOUGHNESS.unwrapKey().get())) tough += e.modifier().amount();
            else if (e.attribute().is(Attributes.ATTACK_DAMAGE.unwrapKey().get())) atk += e.modifier().amount();
        }
        if (atk > 0) atk += 1;       // the player's own base damage
        Tool tool = st.get(DataComponents.TOOL);
        if (tool != null) {
            for (Tool.Rule r : tool.rules()) {
                if (r.speed().isPresent() && r.blocks() instanceof HolderSet.Named<Block> n && n.key().location().getPath().startsWith("mineable/")) {
                    speed = Math.max(speed, r.speed().get());
                }
            }
            if (speed > 0) level = levelOf(tool);
        }
        var food = st.get(DataComponents.FOOD);
        if (food != null) {
            boolean harmful = false;
            for (var pe : food.effects()) if (pe.effect().getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) harmful = true;
            if (!harmful) { nut = food.nutrition(); sat = food.saturation(); }
        }
        if (item instanceof BlockItem bi) {
            BlockState bs = bi.getBlock().defaultBlockState();
            hard = Math.max(0, bs.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            light = bs.getLightEmission();
        }
        // rule 4: the role is the game's item tag (a tagged modded tool gets it with or without a mineable tag), never the id
        ItemWorth.Role role = ItemWorth.roleOf(st.is(net.minecraft.tags.ItemTags.PICKAXES), st.is(net.minecraft.tags.ItemTags.AXES),
                st.is(net.minecraft.tags.ItemTags.SHOVELS), st.is(net.minecraft.tags.ItemTags.HOES), speed > 0, atk > 0 && dur > 0);
        if (dur == 0 && def == 0 && speed == 0 && nut == 0 && hard == 0 && light == 0 && atk == 0) return ItemWorth.Facts.NONE;
        return new ItemWorth.Facts(role, speed, level, dur, def, tough, atk, nut, sat, hard, light);
    }

    /** The harvest level from the tool's own "incorrect for" rule: how many of the needs_* block tags it is not locked out of. */
    static int levelOf(Tool tool) {
        List<HolderSet<Block>> locked = new ArrayList<>();
        for (Tool.Rule r : tool.rules()) if (r.correctForDrops().isPresent() && !r.correctForDrops().get()) locked.add(r.blocks());
        if (locked.isEmpty()) return 0;
        int level = 0;
        for (TagKey<Block> tag : List.of(BlockTags.NEEDS_STONE_TOOL, BlockTags.NEEDS_IRON_TOOL, BlockTags.NEEDS_DIAMOND_TOOL)) {
            var first = BuiltInRegistries.BLOCK.getTag(tag).flatMap(hs -> hs.stream().findFirst());
            if (first.isEmpty()) continue;
            boolean out = false;
            for (HolderSet<Block> l : locked) if (l.contains(first.get())) out = true;
            if (!out) level++;
        }
        return level;
    }

    @Override public ItemWorth.Facts facts(String id) { return facts.getOrDefault(id, ItemWorth.Facts.NONE); }
    @Override public List<ItemWorth.Recipe> recipes(String id) { return recipes.getOrDefault(id, List.of()); }
    @Override public List<String> smeltResults() { return smelts; }
    @Override public Map<String, String> cooks() { return cooks; }
    @Override public boolean stoneLike(String id) { return stoneLike.contains(id); }
    @Override public boolean station(String id) { return stations.contains(id); }
    @Override public boolean plank(String id) { return planks.contains(id); }
    @Override public boolean ore(String id) { return ores.contains(id); }
    int itemsWithFacts() { return facts.size(); }
    int itemsWithRecipes() { return recipes.size(); }
}


