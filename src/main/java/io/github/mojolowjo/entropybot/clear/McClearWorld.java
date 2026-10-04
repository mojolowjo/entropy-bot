package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@link ClearWorld} over the client's level: the only class of the clear engine that touches Minecraft. Client
 * thread. Built blocks come from the guard's registry-built floor (the bridge's protected list), with the id
 * pattern as the fallback until that is built; Baritone's avoid list is passed in by whoever builds this.
 */
public final class McClearWorld implements ClearWorld {
    private final Level level;
    private final Entity viewer;
    private final Predicate<Block> avoided;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    /**
     * @param viewer  the bot (for the ray's collision context); may be null
     * @param avoided Baritone's blocksToAvoidBreaking (null: none)
     */
    public McClearWorld(Level level, Entity viewer, Predicate<Block> avoided) {
        this.level = level;
        this.viewer = viewer;
        this.avoided = avoided;
    }

    private BlockState at(int x, int y, int z) {
        return level.getBlockState(m.set(x, y, z));
    }

    @Override public String id(int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(at(x, y, z).getBlock()).toString();
    }

    /** The bridge's blockName, from the description id: "block.minecraft.stone" -> "stone", "block.oritech.nickel_ore" -> "oritech:nickel_ore". */
    @Override public String name(int x, int y, int z) {
        return at(x, y, z).getBlock().getDescriptionId().replaceFirst("^block\\.", "").replaceFirst("^minecraft\\.", "").replaceFirst("\\.", ":");
    }

    @Override public boolean air(int x, int y, int z) { return at(x, y, z).isAir(); }

    @Override public boolean fluid(int x, int y, int z) { return !at(x, y, z).getFluidState().isEmpty(); }

    @Override public String fluidKind(int x, int y, int z) {
        var fs = at(x, y, z).getFluidState();
        if (fs.isEmpty()) return null;
        if (fs.is(net.minecraft.tags.FluidTags.LAVA)) return "lava";
        if (fs.is(net.minecraft.tags.FluidTags.WATER)) return "water";
        return BuiltInRegistries.FLUID.getKey(fs.getType()).toString();
    }

    @Override public FluidCell fluidCell(int x, int y, int z) {
        var fs = at(x, y, z).getFluidState();
        if (fs.isEmpty()) return null;
        boolean falling = fs.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
                && fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
        return new FluidCell(fs.isSource(), fs.getAmount(), falling);
    }

    @Override public boolean replaceable(int x, int y, int z) { return at(x, y, z).canBeReplaced(); }

    @Override public boolean blockEntity(int x, int y, int z) { return at(x, y, z).hasBlockEntity(); }

    @Override public boolean unbreakable(int x, int y, int z) {
        BlockState st = at(x, y, z);
        return st.getDestroySpeed(level, m) < 0;
    }

    @Override public boolean builtBlock(int x, int y, int z) {
        BlockState st = at(x, y, z);
        Guard g = Guard.INSTANCE;
        if (g.floorReady()) return g.isProtectedBlock(st.getBlock());
        return ClearRules.builtId(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
    }

    @Override public boolean avoided(int x, int y, int z) {
        return avoided != null && avoided.test(at(x, y, z).getBlock());
    }

    @Override public boolean ore(int x, int y, int z) { return at(x, y, z).is(Tags.Blocks.ORES); }

    @Override public boolean noCollision(int x, int y, int z) {
        BlockState st = at(x, y, z);
        return st.getCollisionShape(level, m).isEmpty();
    }

    @Override public boolean fullBlock(int x, int y, int z) {
        BlockState st = at(x, y, z);
        return st.isCollisionShapeFullBlock(level, m);
    }

    @Override public double collisionHeight(int x, int y, int z) {
        BlockState st = at(x, y, z);
        VoxelShape shape = st.getCollisionShape(level, m);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    @Override public int blockLight(int x, int y, int z) {
        return level.getBrightness(LightLayer.BLOCK, m.set(x, y, z));
    }

    @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) {
        CollisionContext ctx = viewer == null ? CollisionContext.empty() : CollisionContext.of(viewer);
        BlockHitResult hit = level.clip(new ClipContext(new Vec3(fx, fy, fz), new Vec3(tx, ty, tz), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        return new Hit(p.getX(), p.getY(), p.getZ(), Face.values()[hit.getDirection().ordinal()]);
    }

    /** The game's face for an engine face (to click the block with). */
    public static Direction direction(Face f) {
        return Direction.values()[f.ordinal()];
    }

    /** Where the player is, for the engine. */
    public static Bot botOf(Entity e) {
        return new Bot(e.getX(), e.getY(), e.getZ(), e.getEyeY());
    }

    // ---- tools (holdBestTool's inputs) ----

    /** The player's main inventory (0..35, non-empty slots) and what each item does to the block at x y z. */
    public List<Tools.Slot> toolSlots(Player p, int x, int y, int z) {
        BlockState st = at(x, y, z);
        Inventory inv = p.getInventory();
        List<Tools.Slot> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            out.add(new Tools.Slot(i, BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.isCorrectToolForDrops(st), s.getDestroySpeed(st)));
        }
        return out;
    }

    private static ItemStack stonePick;

    /** Package B: a stone pickaxe could break the block at x y z as well (it drops, and the pickaxe is a tool for it). */
    public boolean stoneCanBreak(int x, int y, int z) {
        BlockState st = at(x, y, z);
        if (stonePick == null) stonePick = new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE);
        return st.requiresCorrectToolForDrops() ? stonePick.isCorrectToolForDrops(st) : stonePick.getDestroySpeed(st) > 1;
    }

    /** The block only drops with the right tool (stone, ores...). */
    public boolean needsCorrectTool(int x, int y, int z) {
        return at(x, y, z).requiresCorrectToolForDrops();
    }

    /** Break progress per tick with what the player holds now (beginBreak's getDestroyProgress). */
    public float destroyProgress(Player p, int x, int y, int z) {
        BlockState st = at(x, y, z);
        return st.getDestroyProgress(p, level, m);
    }
}
