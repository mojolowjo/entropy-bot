package io.github.mojolowjo.entropybot.strip;

import java.util.List;

/** The sim's block registry, the ore part (oreIds): vanilla ores, a modded one, a non-ore. */
final class OreSpecIds {
    private OreSpecIds() {}

    static final List<String> IDS = List.of("minecraft:stone", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore", "minecraft:ancient_debris", "oritech:nickel_ore", "minecraft:iron_block");
}
