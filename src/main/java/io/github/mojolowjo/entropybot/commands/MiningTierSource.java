package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AA_ROUND4 C adapter for {@link MiningTiers.Source}: the pickaxe tier each block needs, from the game's own data. Every
 * pickaxe item (vanilla or modded) carries a Tool whose "incorrect for drops" rule is its tier's incorrect_for_* tag (the
 * needs_stone/iron/diamond_tool tags and any modded tag inside it); a block needing the correct tool is mined by the lowest
 * pickaxe that is not locked out of it. A block that does not need a tool, or no pickaxe mines, needs tier 0; a block
 * every pickaxe is locked out of is {@link MiningTiers#UNREACHABLE}. Nothing is named. Tier = ItemWorthSource level + 1
 * (wooden 1 .. diamond 4). Loader notes: Tool rules, BlockTags and HolderSet are vanilla 1.21 (NeoForge adds nothing here).
 */
final class MiningTierSource implements MiningTiers.Source {
    private final Map<Block, Integer> tiers = new HashMap<>();
    private final Map<String, Integer> drops = new HashMap<>();
    int pickaxes, blocksNeedingTool, dropFailures;
    boolean dropsRead;

    private record Pick(int locked, List<HolderSet<Block>> sets) {}

    /** @param server the integrated server for the loot tables (null: drops unknown, only the block items count) */
    static MiningTierSource read(net.minecraft.server.MinecraftServer server) {
        MiningTierSource s = new MiningTierSource();
        List<Pick> picks = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Tool tool = item.getDefaultInstance().get(DataComponents.TOOL);
            if (tool == null) continue;
            boolean pickaxe = false;
            List<HolderSet<Block>> locked = new ArrayList<>();
            for (Tool.Rule r : tool.rules()) {
                if (r.speed().isPresent() && r.blocks() instanceof HolderSet.Named<Block> n && n.key().equals(BlockTags.MINEABLE_WITH_PICKAXE)) pickaxe = true;
                if (r.correctForDrops().isPresent() && !r.correctForDrops().get()) locked.add(r.blocks());
            }
            if (pickaxe) {
                java.util.Set<Block> lockedBlocks = new java.util.HashSet<>();
                for (HolderSet<Block> l : locked) for (var h : l) lockedBlocks.add(h.value());
                picks.add(new Pick(lockedBlocks.size(), locked));
            }
        }
        s.pickaxes = picks.size();
        if (picks.isEmpty()) throw new IllegalStateException("no pickaxe with a Tool component in the item registry");
        List<Integer> rank = MiningTiers.rankTiers(picks.stream().map(Pick::locked).toList());
        for (Block b : BuiltInRegistries.BLOCK) {
            var holder = BuiltInRegistries.BLOCK.wrapAsHolder(b);
            if (!b.defaultBlockState().requiresCorrectToolForDrops() || !holder.is(BlockTags.MINEABLE_WITH_PICKAXE)) { s.tiers.put(b, 0); continue; }
            int best = MiningTiers.UNREACHABLE;
            for (int i = 0; i < picks.size(); i++) {
                boolean out = false;
                for (HolderSet<Block> l : picks.get(i).sets()) if (l.contains(holder)) { out = true; break; }
                if (!out) best = Math.min(best, rank.get(i));
            }
            s.tiers.put(b, best);
            s.blocksNeedingTool++;
        }
        // the tags are data loaded with the world: none needing a tool means they were not there yet
        if (s.blocksNeedingTool == 0) throw new IllegalStateException("no block needs a tool: the block tags are not loaded yet");
        if (server != null) s.readDrops(server);
        return s;
    }

    /** Which items each block drops (the loot tables, on the server thread): the least tier among the blocks dropping it. */
    private void readDrops(net.minecraft.server.MinecraftServer server) {
        server.submit(() -> {
            var level = server.overworld();
            for (Block b : BuiltInRegistries.BLOCK) {
                try {
                    int t = tiers.getOrDefault(b, 0);
                    for (var st : Block.getDrops(b.defaultBlockState(), level, net.minecraft.core.BlockPos.ZERO, null)) {
                        if (st.isEmpty()) continue;
                        drops.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), t, Math::min);
                    }
                } catch (RuntimeException e) { dropFailures++; }
            }
            dropsRead = true;
        }).join();
    }

    @Override public int dropTier(String itemId) { return drops.getOrDefault(itemId, -1); }

    @Override public int familyTier(String family) {
        int least = Integer.MAX_VALUE;
        for (var e : tiers.entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(e.getKey()).toString();
            if (io.github.mojolowjo.entropybot.cave.OreSpec.oreId(id) && io.github.mojolowjo.entropybot.sense.KnownOre.family(id, family)) least = Math.min(least, e.getValue());
        }
        return least == Integer.MAX_VALUE ? -1 : least;
    }

    @Override public int blockTier(String itemId) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null) return -1;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (!(item instanceof BlockItem bi)) return -1;
        return tiers.getOrDefault(bi.getBlock(), 0);
    }
}

