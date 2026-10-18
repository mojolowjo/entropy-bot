package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The guard as the game sees it: the pure rules of {@link GuardCore} plus the protected-block set,
 * the dimension and block lookups, and the entry points the three Mixins call.
 */
public final class Guard {
    private static final Logger LOG = LogUtils.getLogger();
    public static final Guard INSTANCE = new Guard();

    /**
     * Building blocks that don't occur in the wild, by block id (the same list the KubeJS bridge
     * uses). Ores are never on the list; blocks with a block entity are added by the registry walk.
     */
    static final Pattern PROTECT_RE = Pattern.compile(String.join("|",
            "_planks$", "_stairs$", "_slab$", "_fence$", "_fence_gate$", "_door$", "_trapdoor$", "glass", "_pane$", "_wool$", "_carpet$",
            ":bricks$", "_bricks$", "_wall$", "_button$", "_pressure_plate$", "_concrete$", "_concrete_powder$", "glazed_terracotta$",
            ":(polished|chiseled|cut)_", ":smooth_(stone|sandstone|red_sandstone|quartz)", "_sapling$", ":potted_", ":flower_pot$", "candle",
            ":(waxed_)?((exposed|weathered|oxidized)_)?(cut|chiseled)_", ":waxed_", ":stripped_", "_wood$", "_hyphae$", "_tiles$", "_froglight$",
            ":(iron|gold|diamond|emerald|netherite|lapis|redstone|coal|copper)_block$",
            ":(quartz_block|quartz_pillar|dark_prismarine|smithing_table|stonecutter|grindstone|loom|cartography_table|fletching_table|"
                    + "composter|cauldron|water_cauldron|lava_cauldron|powder_snow_cauldron|anvil|chipped_anvil|damaged_anvil|lodestone|"
                    + "respawn_anchor|lightning_rod|dried_kelp_block|honey_block|slime_block|packed_mud|nether_wart|copper_bulb|copper_grate)$",
            ":(ladder|rail|powered_rail|detector_rail|activator_rail|torch|wall_torch|soul_torch|soul_wall_torch|redstone_torch|"
                    + "redstone_wall_torch|lantern|soul_lantern|farmland|scaffolding|iron_bars|chain|lever|hay_block|bookshelf|crafting_table|"
                    + "end_rod|sea_lantern|carved_pumpkin|jack_o_lantern|tnt|target|note_block|redstone_wire|repeater|piston|sticky_piston|"
                    + "observer|redstone_lamp)$",
            ":(wheat|carrots|potatoes|beetroots|melon_stem|pumpkin_stem|attached_melon_stem|attached_pumpkin_stem|cocoa|sweet_berry_bush)$",
            "_crop$", "farmland$"));

    public final GuardCore core = new GuardCore();
    private volatile Set<Block> protectedBlocks;   // null until built on the client thread
    private volatile int blockEntityCount, namedCount;
    private EventRing events;
    private long lastAstarLogTick = -1000;

    private Guard() {}

    public void attachEvents(EventRing ring) { this.events = ring; }

    public boolean floorReady() { return protectedBlocks != null; }

    public int floorSize() { Set<Block> s = protectedBlocks; return s == null ? 0 : s.size(); }

    /** B7e (E1): the protected blocks (immutable; null until built), for Baritone's blocksToDisallowBreaking. */
    public Set<Block> protectedBlocks() { return protectedBlocks; }

    public String floorInfo() {
        return floorReady() ? floorSize() + " blocks (" + blockEntityCount + " with a block entity, " + namedCount + " building blocks)" : "not built yet";
    }

    /** Builds the protected-block set from the registry. Client thread, once registries are complete. */
    public void ensureProtectedBlocks() {
        if (protectedBlocks != null) return;
        Set<Block> set = new HashSet<>();
        int be = 0, named = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            try {
                BlockState st = b.defaultBlockState();
                if (st.is(Tags.Blocks.ORES)) continue;
                if (io.github.mojolowjo.entropybot.clear.ClearRules.choppedLog(BuiltInRegistries.BLOCK.getKey(b).toString())) continue;    // P3: a tree's log
                if (st.hasBlockEntity()) { set.add(b); be++; }
                else if (PROTECT_RE.matcher(BuiltInRegistries.BLOCK.getKey(b).toString()).find()) { set.add(b); named++; }
            } catch (RuntimeException ignored) {}
        }
        blockEntityCount = be;
        namedCount = named;
        protectedBlocks = Set.copyOf(set);
        LOG.info("[entropybot] protected blocks: {}", floorInfo());
    }

    public boolean isProtectedBlock(Block b) {
        Set<Block> s = protectedBlocks;
        return s != null && s.contains(b);
    }

    public GuardCore.BlockInfo infoFor(Level level, BlockPos pos) {
        Set<Block> s = protectedBlocks;
        if (s == null || level == null) return GuardCore.BlockInfo.UNKNOWN;
        BlockState st = level.getBlockState(pos);
        String bid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        boolean be = st.hasBlockEntity() && !io.github.mojolowjo.entropybot.clear.ClearRules.choppedLog(bid)
                // 0.33.0 (A3): my own station inside my areas is a built block here (a force lease may take it), not floor
                && !(io.github.mojolowjo.entropybot.clear.OwnStations.station(bid) && io.github.mojolowjo.entropybot.clear.OwnStations.mineAt(pos.getX(), pos.getY(), pos.getZ()));
        return GuardCore.BlockInfo.of(true, be, s.contains(st.getBlock()));
    }

    public static String dimOf(Level level) {
        return level.dimension().location().toString();
    }

    /** "nether portal" / "end portal" when one lies within r blocks of x y z (loaded chunks only), else null. */
    public static String portalNear(Level level, int x, int y, int z, int r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(x + dx, y + dy, z + dz);
                    if (!level.isLoaded(m)) continue;
                    Block b = level.getBlockState(m).getBlock();
                    if (b instanceof net.minecraft.world.level.block.NetherPortalBlock) return "nether portal";
                    if (b instanceof net.minecraft.world.level.block.EndPortalBlock || b instanceof net.minecraft.world.level.block.EndGatewayBlock) return "end portal";
                }
            }
        }
        return null;
    }

    /**
     * Wave 1 (item 3): "lava" or "water" in a cell next to x y z (above and the four sides, the cells the digging
     * looks at: it never breaks such a block), lava first; null when there is none. Loaded chunks only.
     */
    public static String liquidNextTo(Level level, int x, int y, int z) {
        return GuardCore.liquidNextTo((cx, cy, cz) -> {
            BlockPos p = new BlockPos(cx, cy, cz);
            if (!level.isLoaded(p)) return null;
            var fluid = level.getFluidState(p);
            if (fluid.isEmpty()) return null;
            return fluid.is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water";
        }, x, y, z);
    }

    /**
     * 0.36.8: the liquid next to x y z that forbids our own dig-out/escape breaking: lava always, water only outside a
     * mine job (commands.MineWater). Loaded chunks only.
     */
    public static String breakLiquid(Level level, int x, int y, int z) {
        return GuardCore.breakLiquid(liquidNextTo(level, x, y, z), io.github.mojolowjo.entropybot.commands.MineWater.active());
    }

    // ---- entry points for the Mixins (client thread unless noted) ----

    /** Every block break this client starts or continues. True = refuse. */
    public boolean vetoBreak(BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        String dim = dimOf(level);
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        if (pr.travelLive() && pr.intent() == BreakPolicy.Intent.TRAVEL) return vetoTravelBreak(level, pos, dim, pr);
        GuardCore.Verdict v = core.check(dim, pos.getX(), pos.getY(), pos.getZ(), "break", infoFor(level, pos));
        shadowClick(level, pos, dim, v);
        return refuse(level, pos, "break", v);
    }

    /**
     * Mining plan phase 1: a break while a walk runs (TRAVEL intent). The floor and the fence's boxes as before; where
     * only a box rule would refuse (outside every area, no lease), the policy decides (GuardCore.checkTravelBreak). A
     * break let through is a travel break: counted toward the cap and handed to the restore ledger as a path break.
     */
    private boolean vetoTravelBreak(Level level, BlockPos pos, String dim, PolicyRuntime pr) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        GuardCore.TravelCheck t = core.checkTravelBreak(dim, x, y, z, infoFor(level, pos), pr.snapshot(), pr.facts(level, pos, false), pr.where());
        GuardCore.Verdict v = t.guard();
        if (t.travel() == null) {
            // the fence decided alone (allowed, or the floor): exactly as before
            if (!v.allowed() || v.wouldVeto()) core.log().record(core.tick(), "break", dim, x, y, z, v.reason(), v.floor(), !v.allowed());
            return refuse(level, pos, "break", v);
        }
        pr.stats.ask(PolicyStats.SITE_CLICK);
        // the exemption needs a running job (the same condition under which safeSettings/SafetyNet keep A* breaking on,
        // so the plan and the click agree) and a terrain block (what the ledger can put back; logs and crops keep the fence)
        boolean jobRunning = false;
        try { jobRunning = io.github.mojolowjo.entropybot.Core.INSTANCE.commands.jobs.running(); } catch (Throwable ignored) {}
        boolean terrain = io.github.mojolowjo.entropybot.restore.RestoreRules.natural(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        if (t.travel().allowed() && jobRunning && terrain) {
            pr.logTravelOverride(PolicyStats.SITE_CLICK, x, y, z, t.travel(), v.reason());
            pr.markTravelBreak(dim + " " + x + " " + y + " " + z);
            return false;
        }
        pr.stats.deny(PolicyStats.SITE_CLICK, t.travel().reason());
        if (!v.allowed() || v.wouldVeto()) core.log().record(core.tick(), "break", dim, x, y, z, v.reason(), v.floor(), !v.allowed());
        return refuse(level, pos, "break", v);
    }


    /** Mining plan phase 0: the policy's answer next to the fence's, counted and logged when they differ. Never throws. */
    private void shadowClick(Level level, BlockPos pos, String dim, GuardCore.Verdict v) {
        try {
            PolicyRuntime pr = PolicyRuntime.INSTANCE;
            BreakPolicy.Intent in = pr.intent();
            BreakPolicy.Verdict pv = pr.judge(PolicyStats.SITE_CLICK, BreakPolicy.Action.BREAK, in, dim, pos.getX(), pos.getY(), pos.getZ(),
                    pr.facts(level, pos, false), pr.where());
            // in log mode the fence lets a would-be veto through; the comparison is against what it WOULD do
            pr.shadow(PolicyStats.SITE_CLICK, v.allowed() && !v.wouldVeto(), v.reason(), pv, in, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            PolicyRuntime.INSTANCE.stats.error("click shadow: " + t);
        }
    }

    /** Every block placement (BlockItem.place) and every liquid, fire, egg or entity put down. True = refuse. */
    public boolean vetoPlace(Level level, BlockPos pos) { return vetoPlace(level, pos, false); }

    /** 0.23.1: torch = the item put down is a torch (roaming outside the areas places nothing else). */
    public boolean vetoPlace(Level level, BlockPos pos, boolean torch) {
        if (level == null) level = Minecraft.getInstance().level;
        if (level == null) return false;
        // water plan: a seal lease (just outside the areas) only fills air or water
        GuardCore.BlockInfo info = GuardCore.BlockInfo.PLAIN;
        if (level.isLoaded(pos)) {
            var st = level.getBlockState(pos);
            boolean airOrWater = st.isAir() || (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && st.canBeReplaced());
            info = GuardCore.BlockInfo.placing(airOrWater, torch);
        }
        return refuse(level, pos, "place", core.check(dimOf(level), pos.getX(), pos.getY(), pos.getZ(), "place", info));
    }

    /**
     * 0.23.6 the fire reflex's water bucket at the bot's own feet: restore-kind leases (one cell each, air or water only,
     * never in a protect box or a denied dimension) for the feet cell and the block clicked under it, then a dry run.
     * Returns the lease ids to release once the water is picked up again, or null (refused; nothing held).
     */
    public String[] waterAtFeet(Level level, BlockPos feet) {
        String dim = dimOf(level);
        String a = core.restoreLease("survival", "fire water", new Box(null, dim, feet.getX(), feet.getY(), feet.getZ(), feet.getX(), feet.getY(), feet.getZ()));
        if (a.startsWith("error")) {
            LOG.info("[entropybot] fire water: no lease at my feet: {}", a);
            return null;
        }
        BlockPos b = feet.below();
        String c = core.restoreLease("survival", "fire water", new Box(null, dim, b.getX(), b.getY(), b.getZ(), b.getX(), b.getY(), b.getZ()));
        if (c.startsWith("error")) {
            core.release(a);
            return null;
        }
        GuardCore.Verdict v = core.checkUnlogged(dim, feet.getX(), feet.getY(), feet.getZ(), "place", GuardCore.BlockInfo.placing(true, false));
        if (!v.allowed() || v.wouldVeto()) {
            LOG.info("[entropybot] fire water: the guard says no at my feet: {}", v.reason());
            core.release(a);
            core.release(c);
            return null;
        }
        return new String[]{a, c};
    }

    public void releaseLeases(String[] ids) {
        if (ids != null) for (String id : ids) core.release(id);
    }

    private boolean refuse(Level level, BlockPos pos, String action, GuardCore.Verdict v) {
        if (v.allowed() && !v.wouldVeto()) return false;
        String line = (v.allowed() ? "would refuse " : "refused ") + action + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": " + v.reason();
        EventRing ring = events;
        if (ring != null) {
            JsonObject d = new JsonObject();
            d.addProperty("action", action);
            d.addProperty("pos", pos.getX() + " " + pos.getY() + " " + pos.getZ());
            d.addProperty("reason", v.reason());
            d.addProperty("floor", v.floor());
            d.addProperty("enforced", !v.allowed());
            ring.push("guard", line, d);
        }
        if (!v.allowed()) LOG.info("[entropybot] guard {}", line);
        return !v.allowed();
    }

    /** Items that put something into the world without being a BlockItem (those go through BlockItem.place). */
    static boolean putsSomethingDown(Item item) {
        return item instanceof BucketItem || item instanceof SolidBucketItem || item instanceof FlintAndSteelItem
                || item instanceof FireChargeItem || item instanceof SpawnEggItem || item instanceof HangingEntityItem
                || item instanceof ArmorStandItem || item instanceof EndCrystalItem || item instanceof MinecartItem
                || item instanceof BoatItem;
    }

    /** MultiPlayerGameMode.useItemOn: a right-click on a block with a liquid, fire, egg or entity item in hand. */
    public boolean vetoUseOn(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() || !putsSomethingDown(stack.getItem())) return false;
        Level level = player.level();
        BlockPos at = hit.getBlockPos();
        BlockState clicked = level.getBlockState(at);
        // opening a chest or a machine, even with a bucket in hand, is an interaction, not a placement
        if (!player.isSecondaryUseActive() && (clicked.hasBlockEntity() || clicked.getMenuProvider(level, at) != null)) return false;
        return vetoPlace(level, at.relative(hit.getDirection())) || vetoPlace(level, at);
    }

    /** MultiPlayerGameMode.useItem: a right-click into the air with a bucket or a boat. */
    public boolean vetoUse(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (!(item instanceof BucketItem || item instanceof SolidBucketItem || item instanceof BoatItem)) return false;
        Level level = player.level();
        HitResult hr = Minecraft.getInstance().hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            return vetoPlace(level, bhr.getBlockPos().relative(bhr.getDirection())) || vetoPlace(level, bhr.getBlockPos());
        }
        return vetoPlace(level, player.blockPosition());
    }

    /**
     * Baritone's A* asks this for every block it might break or place (pathfinder thread). Box rules
     * only: no block lookups, no logging beyond one line per 100 ticks in log mode.
     */
    public boolean astarDenied(Level world, int x, int y, int z) { return astarDenied(world, null, x, y, z); }

    /**
     * @param at the context's {@code BlockStateInterface} (bsi.get0) when the mixin could shadow it, for the policy's
     *           block facts on this thread; null = box rules only.
     */
    public boolean astarDenied(Level world, PolicyRuntime.StateAt at, int x, int y, int z) {
        String dim = dimOf(world);
        PolicyRuntime pr = PolicyRuntime.INSTANCE;
        BreakPolicy.Intent in = pr.intentNow();
        if (pr.travelLive() && (in == BreakPolicy.Intent.TRAVEL || in == BreakPolicy.Intent.NONE)) {
            // phase 1: a walk's A* plans by the policy: denied dims, protect boxes, no-break areas here (facts-free); the
            // cost hook (CostAdvisorHook) adds the block facts and the multiplier. Leases are not a walk's business.
            pr.stats.ask(PolicyStats.SITE_ASTAR_BOXES);
            BreakPolicy.Verdict pv = BreakPolicy.boxes(pr.snapshot(), in, dim, x, y, z);
            if (!pv.allowed()) pr.stats.deny(PolicyStats.SITE_ASTAR_BOXES, pv.reason());
            return !pv.allowed();
        }
        GuardCore.Verdict v = core.checkBoxes(dim, x, y, z, "break");
        shadowAstar(dim, at, x, y, z, v);
        if (!v.allowed()) return true;
        if (v.wouldVeto()) {
            long t = core.tick();
            if (t - lastAstarLogTick >= 100) {
                lastAstarLogTick = t;
                EventRing ring = events;
                if (ring != null) ring.push("guard", "A* would refuse breaking at " + x + " " + y + " " + z + ": " + v.reason(), null);
            }
        }
        return false;
    }

    /**
     * Mining plan phase 0: the policy's answer (boxes, and with the bsi the block facts too) next to the A* hook's.
     * Pathfinder thread: snapshot and bsi reads only. Never throws.
     */
    private void shadowAstar(String dim, PolicyRuntime.StateAt at, int x, int y, int z, GuardCore.Verdict v) {
        try {
            PolicyRuntime pr = PolicyRuntime.INSTANCE;
            BreakPolicy.Intent in = pr.intentNow();
            boolean today = v.allowed() && !v.wouldVeto();
            BreakPolicy.Verdict pv;
            if (at == null) {
                pr.stats.ask(PolicyStats.SITE_ASTAR_BOXES);
                pv = BreakPolicy.boxes(pr.snapshot(), in, dim, x, y, z);
                if (!pv.allowed()) pr.stats.deny(PolicyStats.SITE_ASTAR_BOXES, pv.reason());
                pr.shadow(PolicyStats.SITE_ASTAR_BOXES, today, v.reason(), pv, in, x, y, z);
            } else {
                BreakPolicy.BlockFacts f = pr.facts(at, x, y, z, false);
                // isPossiblyProtected is asked for placements too (an air or water cell): no break verdict to compare there
                if (f.loaded() && f.inCell() != BreakPolicy.Fluid.NONE) return;
                pv = pr.judge(PolicyStats.SITE_ASTAR_COST, BreakPolicy.Action.BREAK, in, dim, x, y, z, f, pr.where());
                pr.shadow(PolicyStats.SITE_ASTAR_COST, today, v.reason(), pv, in, x, y, z);
            }
        } catch (Throwable t) {
            PolicyRuntime.INSTANCE.stats.error("astar shadow: " + t);
        }
    }
}
