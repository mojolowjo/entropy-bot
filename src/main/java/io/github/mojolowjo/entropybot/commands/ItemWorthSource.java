package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
    private final Map<String, String> cooks = new HashMap<>(), mobFood = new HashMap<>();
    private final Map<String, java.util.Set<String>> tags = new HashMap<>();
    private final java.util.Set<String> stoneLike = new java.util.HashSet<>(), junkFood = new java.util.HashSet<>(), effectFood = new java.util.HashSet<>(), risky = new java.util.HashSet<>(), shields = new java.util.HashSet<>(), planks = new java.util.HashSet<>(), stations = new java.util.HashSet<>(), containers = new java.util.HashSet<>(), ores = new java.util.HashSet<>();
    int failed;
    /** A harmful effect that happens at least this often makes a food poison (rotten flesh 0.8, spider eye 1.0; raw chicken 0.3 is still food). */
    static float harmP() { return (float) Tuning.get("itemWorthSource.harmP"); }

    static ItemWorthSource read(Level level) {
        ItemWorthSource s = new ItemWorthSource();
        ItemWorth.logTo(m -> org.slf4j.LoggerFactory.getLogger("entropybot").warn("[entropybot] {}", m));
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (item.getDefaultInstance().is(net.minecraft.tags.ItemTags.PLANKS)) s.planks.add(id);
            var fp = item.getDefaultInstance().get(DataComponents.FOOD);
            if (fp != null) {
                boolean bad = fp.nutrition() <= 1;
                for (var pe : fp.effects()) if (pe.probability() >= harmP() && pe.effect().getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) bad = true;
                for (var pe : fp.effects()) if (pe.effect().getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) s.risky.add(id);
                if (item instanceof net.minecraft.world.item.ChorusFruitItem) bad = true;       // it teleports: by its class, any mod
                if (bad) s.junkFood.add(id);
                else for (var pe : fp.effects()) if (pe.probability() >= harmP()) s.effectFood.add(id);
            }
            try { ItemWorth.Facts f = factsOf(item); if (f != ItemWorth.Facts.NONE) s.facts.put(id, f); }
            catch (RuntimeException e) { s.failed++; }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem bi)) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            try { s.blockRoles(id, bi.getBlock().defaultBlockState(), level); } catch (RuntimeException e) { s.failed++; }
        }
        for (var b : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            try { b.defaultBlockState().getTags().forEach(k -> s.tags.computeIfAbsent(id, x -> new java.util.HashSet<>()).add(k.location().toString())); }
            catch (RuntimeException e) { s.failed++; }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            try {
                ItemStack st = item.getDefaultInstance();
                st.getTags().forEach(k -> s.tags.computeIfAbsent(id, x -> new java.util.HashSet<>()).add(k.location().toString()));
                if (st.getUseAnimation() == net.minecraft.world.item.UseAnim.BLOCK) s.shields.add(id);
            } catch (RuntimeException e) { s.failed++; }
        }
        s.readMobs(level);
        s.readOres();
        s.readFishing();
        s.readMobFood(level, s);
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
            if (r.getType() == RecipeType.SMELTING) s.smeltInputs.putIfAbsent(oid, in.get(0));
            if (s.recipes.get(oid).size() == 1) {      // the first recipe is the one the planner reads: does it need a table?
                boolean big = r instanceof net.minecraft.world.item.crafting.ShapedRecipe sr ? sr.getWidth() > 2 || sr.getHeight() > 2 : in.size() > 4;
                if (big) s.tableItems.add(oid);
            }
            if ((r.getType() == RecipeType.SMELTING || r.getType() == RecipeType.SMOKING || r.getType() == RecipeType.CAMPFIRE_COOKING)
                    && out.getItem().getDefaultInstance().get(DataComponents.FOOD) != null) {
                for (var ing : r.getIngredients()) for (ItemStack raw : ing.getItems()) s.cooks.putIfAbsent(BuiltInRegistries.ITEM.getKey(raw.getItem()).toString(), oid);   // the game's cooking recipes: raw -> cooked, any mod
            }
        }
        return s;
    }

    /** Mob -> its food drop from the loot tables (singleplayer server only: a client has none, said once by ItemWorth). */
    private void readMobFood(Level level, ItemWorthSource s) {
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        try {
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            var rr = server.reloadableRegistries();
            java.util.function.Function<String, com.google.gson.JsonElement> lookup = id -> {
                try {
                    var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, ResourceLocation.parse(id));
                    var t = rr.getLootTable(key);
                    return t == net.minecraft.world.level.storage.loot.LootTable.EMPTY ? null : net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, t).result().orElse(null);
                } catch (RuntimeException e) { failed++; return null; }
            };
            for (var e : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                var type = e.getValue();
                if (type.getCategory() != net.minecraft.world.entity.MobCategory.CREATURE) continue;       // the friendly land animals; the owner's rules sort the rest
                var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(type.getDefaultLootTable().location().toString()), lookup);
                String best = null; double bestN = 0;
                for (var d : parsed.entrySet()) {
                    var f = facts.getOrDefault(d.getKey(), ItemWorth.Facts.NONE);
                    if (f.nutrition() <= 0) continue;
                    double n = d.getValue()[0] * d.getValue()[1];
                    if (n > bestN) { bestN = n; best = d.getKey(); }
                }
                if (best != null) mobFood.put(e.getKey().location().toString(), best);
            }
        } catch (RuntimeException e) { failed++; }
    }

    private final Map<String, String> smeltInputs = new HashMap<>(), oreDrops = new HashMap<>();
    private final java.util.Set<String> tableItems = new java.util.HashSet<>();
    @Override public String smeltInput(String result) { return smeltInputs.get(result); }
    @Override public boolean needsTable(String id) { return tableItems.contains(id); }
    @Override public String oreDropping(String item) { return oreDrops.get(item); }

    /** Ore block -> what its loot table drops (singleplayer server only: a client has none; the planner then knows no ore). */
    private void readOres() {
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        try {
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            var rr = server.reloadableRegistries();
            java.util.function.Function<String, com.google.gson.JsonElement> lookup = id -> {
                try {
                    var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, ResourceLocation.parse(id));
                    var t = rr.getLootTable(key);
                    return t == net.minecraft.world.level.storage.loot.LootTable.EMPTY ? null : net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, t).result().orElse(null);
                } catch (RuntimeException e) { failed++; return null; }
            };
            for (var b : BuiltInRegistries.BLOCK) {
                if (!b.defaultBlockState().is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) continue;
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(b.getLootTable().location().toString()), lookup);
                for (String drop : parsed.keySet()) if (!drop.equals(id)) oreDrops.putIfAbsent(drop, id);
            }
        } catch (RuntimeException e) { failed++; }
    }

    private final java.util.Set<String> fishing = new java.util.HashSet<>();
    @Override public java.util.Set<String> fishingFish() { return fishing; }

    /** The fish pool of the fishing loot (singleplayer server only), less the harmful foods (a pufferfish poisons). */
    private void readFishing() {
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        try {
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            var rr = server.reloadableRegistries();
            java.util.function.Function<String, com.google.gson.JsonElement> lookup = id -> {
                try {
                    var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, ResourceLocation.parse(id));
                    var t = rr.getLootTable(key);
                    return t == net.minecraft.world.level.storage.loot.LootTable.EMPTY ? null : net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, t).result().orElse(null);
                } catch (RuntimeException e) { failed++; return null; }
            };
            var parsed = io.github.mojolowjo.entropybot.aa.brain.LootBook.parse(lookup.apply(net.minecraft.world.level.storage.loot.BuiltInLootTables.FISHING_FISH.location().toString()), lookup);
            for (String item : parsed.keySet()) if (!junkFood.contains(item) && !risky.contains(item)) fishing.add(item);
        } catch (RuntimeException e) { failed++; }
    }

    private final Map<String, ItemWorth.Mob> mobs = new HashMap<>();
    @Override public ItemWorth.Mob mob(String id) { return mobs.get(id); }

    /**
     * Every living entity type: max health and attack damage from its default attributes; how it moves and whether it sets its
     * aggressive flag from its class (a probe entity made once, never added to the world), any mod alike.
     */
    @SuppressWarnings("unchecked")
    private void readMobs(Level level) {
        for (var en : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            var type = en.getValue();
            try {
                if (!net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type)) continue;
                var sup = net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier((net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity>) type);
                double hp = sup.hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) ? sup.getBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) : Double.NaN;
                double dmg = sup.hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) ? sup.getBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) : Double.NaN;
                boolean flySpeed = sup.hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FLYING_SPEED);
                var probe = type.create(level);
                ItemWorth.MobMove move = ItemWorth.MobMove.WALK;
                boolean flagged = true;
                if (probe instanceof net.minecraft.world.entity.Mob m) {
                    if (m.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.WallClimberNavigation) move = ItemWorth.MobMove.SPIDER;
                    else if (m instanceof net.minecraft.world.entity.monster.EnderMan) move = ItemWorth.MobMove.TELEPORT;
                    else if (flySpeed || m instanceof net.minecraft.world.entity.FlyingMob || m instanceof net.minecraft.world.entity.animal.FlyingAnimal
                            || m instanceof net.minecraft.world.entity.monster.Blaze || m instanceof net.minecraft.world.entity.monster.breeze.Breeze) move = ItemWorth.MobMove.FLY;
                    else if (m instanceof net.minecraft.world.entity.monster.RangedAttackMob || m instanceof net.minecraft.world.entity.monster.CrossbowAttackMob
                            || m instanceof net.minecraft.world.entity.monster.SpellcasterIllager || m instanceof net.minecraft.world.entity.monster.Shulker
                            || m instanceof net.minecraft.world.entity.monster.Guardian) move = ItemWorth.MobMove.RANGED;
                    flagged = !(m instanceof net.minecraft.world.entity.monster.Slime || m instanceof net.minecraft.world.entity.FlyingMob
                            || m instanceof net.minecraft.world.entity.monster.Blaze || m instanceof net.minecraft.world.entity.monster.Witch
                            || m instanceof net.minecraft.world.entity.monster.Guardian || m instanceof net.minecraft.world.entity.monster.Shulker
                            || m instanceof net.minecraft.world.entity.monster.Vex || m instanceof net.minecraft.world.entity.monster.breeze.Breeze
                            || m instanceof net.minecraft.world.entity.monster.hoglin.HoglinBase || m instanceof net.minecraft.world.entity.monster.Ravager);
                } else if (flySpeed) move = ItemWorth.MobMove.FLY;
                if (probe != null) probe.discard();
                mobs.put(en.getKey().location().toString(), new ItemWorth.Mob(hp, dmg, move, flagged));
            } catch (RuntimeException ex) { failed++; }
        }
    }

    /** Block roles from the game's tags and block class: ore, workstation (opens a menu), stone-like filler. */
    private void blockRoles(String id, BlockState bs, Level level) {
        if (bs.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) ores.add(id);
        boolean entity = bs.getBlock() instanceof net.minecraft.world.level.block.EntityBlock;
        try { if (bs.getMenuProvider(level, BlockPos.ZERO) != null) stations.add(id); } catch (RuntimeException e) { failed++; }
        try {
            if (bs.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb && eb.newBlockEntity(BlockPos.ZERO, bs) instanceof net.minecraft.world.Container) containers.add(id);
        } catch (RuntimeException e) { failed++; }
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
            for (var pe : food.effects()) if (pe.probability() >= harmP() && pe.effect().getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) harmful = true;
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
    @Override public boolean container(String id) { return containers.contains(id); }
    @Override public java.util.Set<String> tags(String id) { return tags.getOrDefault(id, java.util.Set.of()); }
    @Override public java.util.Set<String> withTag(String tag) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (var e : tags.entrySet()) if (e.getValue().contains(tag)) out.add(e.getKey());
        return out;
    }
    @Override public Map<String, String> mobFood() { return mobFood; }
    @Override public boolean shield(String id) { return shields.contains(id); }
    @Override public boolean riskyFood(String id) { return risky.contains(id); }
    @Override public boolean effectFood(String id) { return effectFood.contains(id); }
    @Override public boolean junkFood(String id) { return junkFood.contains(id); }
    @Override public boolean plank(String id) { return planks.contains(id); }
    @Override public boolean ore(String id) { return ores.contains(id); }
    int itemsWithFacts() { return facts.size(); }
    int itemsWithRecipes() { return recipes.size(); }
}


