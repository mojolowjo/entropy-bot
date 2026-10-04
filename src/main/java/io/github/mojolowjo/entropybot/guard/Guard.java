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
        return GuardCore.BlockInfo.of(true, st.hasBlockEntity(), s.contains(st.getBlock()));
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

    // ---- entry points for the Mixins (client thread unless noted) ----

    /** Every block break this client starts or continues. True = refuse. */
    public boolean vetoBreak(BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        return refuse(level, pos, "break", core.check(dimOf(level), pos.getX(), pos.getY(), pos.getZ(), "break", infoFor(level, pos)));
    }

    /** Every block placement (BlockItem.place) and every liquid, fire, egg or entity put down. True = refuse. */
    public boolean vetoPlace(Level level, BlockPos pos) {
        if (level == null) level = Minecraft.getInstance().level;
        if (level == null) return false;
        // water plan: a seal lease (just outside the areas) only fills air or water
        GuardCore.BlockInfo info = GuardCore.BlockInfo.PLAIN;
        if (level.isLoaded(pos)) {
            var st = level.getBlockState(pos);
            boolean airOrWater = st.isAir() || (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && st.canBeReplaced());
            info = GuardCore.BlockInfo.placing(airOrWater);
        }
        return refuse(level, pos, "place", core.check(dimOf(level), pos.getX(), pos.getY(), pos.getZ(), "place", info));
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
    public boolean astarDenied(Level world, int x, int y, int z) {
        GuardCore.Verdict v = core.checkBoxes(dimOf(world), x, y, z, "break");
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
}
