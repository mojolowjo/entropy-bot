/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's PricedPath.stampOf (z57 + review 8) over our values: the fork named every Baritone
 * setting the costs read (ALL_SETTINGS, BREAK_SETTINGS, PLACE_SETTINGS, by reflection); here they are NavConfig's
 * records, so a setting a cost reads cannot be left off the list.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a price used besides the blocks (the memory drops a kept answer when it differs): in {@code all} the advisor's rules
 * epoch, what the context read from the body (sprint, water bucket, frost walker, water speed), the move settings and the
 * search's favouring and cached-only rule; in {@code breaking} the break settings, the hotbar's tools and the mining
 * effects; in {@code placing} the place settings, the placement price and whether there is a block to place. Pure (JUnit:
 * PriceStampTest). Loader notes: vanilla DataComponents.TOOL and ENCHANTMENTS and the item registry, for the tool names only.
 */
public final class PriceStamp {
    private PriceStamp() {}

    /** The stamp of a search built from {@code c} for {@code body}. */
    public static PathMemory.Stamp of(SearchContext c, BodySnapshot body) {
        Map<String, Object> all = new LinkedHashMap<>(), breaking = new LinkedHashMap<>(), placing = new LinkedHashMap<>();
        all.put("rules epoch", c.advisor == null ? 0L : c.advisor.rulesEpoch());
        all.put("canSprint", c.canSprint);
        all.put("hasWaterBucket", c.hasWaterBucket);
        all.put("frostWalker", c.frostWalker);
        all.put("waterWalkSpeed", c.waterWalkSpeed);
        all.put("moves", c.config.moves());
        all.put("backtrackCostFavoringCoefficient", c.config.search().backtrackCostFavoringCoefficient());
        all.put("pathThroughCachedOnly", c.config.search().pathThroughCachedOnly());
        breaking.put("breaking", c.config.breaking());
        TreeSet<String> tools = new TreeSet<>();
        for (ItemStack st : body.hotbar()) {
            if (!st.isEmpty() && st.has(DataComponents.TOOL)) {
                // review of 2026-11-27: the enchantments change break speed (efficiency), so they are in the stamp
                StringBuilder name = new StringBuilder(String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem())));
                ItemEnchantments ench = st.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                TreeSet<String> levels = new TreeSet<>();
                for (Holder<Enchantment> e : ench.keySet()) {
                    levels.add(e.getRegisteredName() + " " + ench.getLevel(e));
                }
                if (!levels.isEmpty()) {
                    name.append(' ').append(levels);
                }
                tools.add(name.toString());
            }
        }
        breaking.put("tools on the hotbar", tools);
        breaking.put("haste", body.hasteAmplifier());
        breaking.put("mining fatigue", body.fatigueAmplifier());
        placing.put("placing", c.config.placing());
        placing.put("hasThrowaway", c.hasThrowaway);
        placing.put("placement price", c.placeBucketCost());
        return new PathMemory.Stamp(all, breaking, placing);
    }
}
