package io.github.mojolowjo.entropybot.farm;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FarmWorld} over the client (client thread), plus the effects a {@link FarmRound} asks for: walk, cancel the
 * walk, sneak, get a harmless hand and right-click a crop. The only farm class that touches Minecraft.
 */
public final class McFarmWorld implements FarmWorld {
    private final Minecraft mc = Minecraft.getInstance();
    private final LocalPlayer p;
    private final Level level;

    public McFarmWorld(LocalPlayer p) {
        this.p = p;
        this.level = p.level();
    }

    private BlockState at(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)); }

    @Override public boolean isAir(int x, int y, int z) { return at(x, y, z).isAir(); }

    @Override public String blockId(int x, int y, int z) { return BuiltInRegistries.BLOCK.getKey(at(x, y, z).getBlock()).toString(); }

    @Override public Age age(int x, int y, int z) {
        BlockState st = at(x, y, z);
        for (Property<?> prop : st.getProperties()) {
            if (!prop.getName().equals("age")) continue;
            Object v = st.getValue(prop);
            if (v instanceof Integer i) return new Age(i, prop.getPossibleValues().size() - 1);
        }
        return null;
    }

    @Override public boolean standable(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z), head = feet.above(), below = feet.below();
        BlockState f = level.getBlockState(feet), h = level.getBlockState(head), b = level.getBlockState(below);
        if (!f.getCollisionShape(level, feet).isEmpty() || !f.getFluidState().isEmpty()) return false;
        if (!h.getCollisionShape(level, head).isEmpty() || !h.getFluidState().isEmpty()) return false;
        return !b.getCollisionShape(level, below).isEmpty() && b.getFluidState().isEmpty();
    }

    @Override public double[] pos() { return new double[]{p.getX(), p.getY(), p.getZ()}; }

    @Override public double[] eye() {
        Vec3 e = p.getEyePosition();
        return new double[]{e.x, e.y, e.z};
    }

    @Override public String dim() { return level.dimension().location().toString(); }

    @Override public List<Drop> drops() {
        List<Drop> out = new ArrayList<>();
        if (mc.level == null) return out;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemEntity it) || !e.isAlive()) continue;
            out.add(new Drop(String.valueOf(e.getId()), Gui.itemId(it.getItem()), e.getX(), e.getY(), e.getZ()));
        }
        return out;
    }

    private ItemEntity entity(String key) {
        if (mc.level == null) return null;
        try {
            Entity e = mc.level.getEntity(Integer.parseInt(key));
            return e instanceof ItemEntity it ? it : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override public boolean dropAlive(String key) {
        ItemEntity e = entity(key);
        return e != null && e.isAlive() && !e.isRemoved();
    }

    @Override public boolean roomFor(Drop d) {
        Inventory inv = p.getInventory();
        if (inv.getFreeSlot() >= 0) return true;
        ItemEntity e = entity(d.key());
        return e != null && inv.getSlotWithRemainingSpace(e.getItem()) >= 0;
    }

    @Override public List<String> slots() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            out.add(s.isEmpty() ? "" : Gui.itemId(s));
        }
        return out;
    }

    @Override public int selected() { return p.getInventory().selected; }

    @Override public Map<String, Integer> inventory() { return Gui.inventory(p); }

    @Override public boolean pathing() {
        IBaritone b = baritone();
        return b != null && (b.getPathingBehavior().isPathing() || b.getPathingControlManager().mostRecentInControl().isPresent());
    }

    // ---- what compact needs of the bag ----

    /** The 36 bag slots for {@link Compact#bagSpace}. */
    public List<Compact.Slot> bag() {
        List<Compact.Slot> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            out.add(s.isEmpty() ? new Compact.Slot("", 0, 64) : new Compact.Slot(Gui.itemId(s), s.getCount(), s.getMaxStackSize()));
        }
        return out;
    }

    // ---- effects ----

    private static IBaritone baritone() {
        try { return BaritoneAPI.getProvider().getPrimaryBaritone(); } catch (Throwable t) { return null; }
    }

    /** Applies one of the round's effects other than Craft and Deposit (those are steps for the engine to splice). */
    public void apply(FarmRound.Effect fx) {
        if (fx instanceof FarmRound.Walk w) walk(w);
        else if (fx instanceof FarmRound.CancelWalk) {
            IBaritone b = baritone();
            if (b != null) b.getPathingBehavior().cancelEverything();
        } else if (fx instanceof FarmRound.Sneak s) mc.options.keyShift.setDown(s.down());
        else if (fx instanceof FarmRound.Use u) {
            hold(u.hand());
            // never right-click the farm with a block, seeds or bone meal in hand
            if (FarmRules.harmless(slots().get(selected()))) use(u.x(), u.y(), u.z());
        }
    }

    public void walk(FarmRound.Walk w) {
        IBaritone b = baritone();
        if (b == null) return;
        BlockPos pos = new BlockPos(w.x(), w.y(), w.z());
        b.getCustomGoalProcess().setGoalAndPath(w.range() == 0 ? new GoalBlock(pos) : new GoalNear(pos, w.range()));
    }

    /** Selects a hotbar slot, or SWAP-clicks a bag slot into the selected hotbar slot. */
    public void hold(FarmRules.Hand h) {
        if (h == null || h.kind().equals("held")) return;
        Inventory inv = p.getInventory();
        if (h.kind().equals("select")) inv.selected = h.slot();
        else if (h.kind().equals("swap") && p.containerMenu == p.inventoryMenu) {
            mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, h.slot(), inv.selected, ClickType.SWAP, p);
        }
    }

    /** Right-clicks the block (the bridge's useBlock): look at its center, use the main hand on it, swing. */
    public void use(int x, int y, int z) {
        Vec3 center = new Vec3(x + 0.5, y + 0.5, z + 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(center, Direction.UP, new BlockPos(x, y, z), false));
        p.swing(InteractionHand.MAIN_HAND);
    }
}
