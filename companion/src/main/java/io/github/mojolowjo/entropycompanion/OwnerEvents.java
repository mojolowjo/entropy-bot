package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

/**
 * Companion 0.3.1: the Minecraft side of {@link OwnerActions}. Called from the game-mode mixin on the client thread;
 * every entry catches its own errors (logged once each), so a hook never breaks the owner's game. Loader notes: plain
 * vanilla client classes, no loader API.
 */
public final class OwnerEvents {
    private static final Logger LOG = LogUtils.getLogger();
    public static final OwnerActions ACTIONS = new OwnerActions();
    private static ItemStack usedStack = ItemStack.EMPTY;
    private static boolean warned;

    private OwnerEvents() {}

    public static void beforeDestroy(BlockPos pos) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || pos == null) return;
            BlockState s = mc.level.getBlockState(pos);
            if (s.isAir()) return;
            ACTIONS.broke(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            warn("broke", e);
        }
    }

    public static void beforeUse(LocalPlayer player, InteractionHand hand) {
        try {
            usedStack = player == null ? ItemStack.EMPTY : player.getItemInHand(hand).copy();
        } catch (RuntimeException e) {
            usedStack = ItemStack.EMPTY;
            warn("use", e);
        }
    }

    public static void afterUse(BlockHitResult hit, InteractionResult r) {
        try {
            ItemStack used = usedStack;
            usedStack = ItemStack.EMPTY;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || hit == null || r == null || !r.consumesAction() || !(used.getItem() instanceof BlockItem bi)) return;
            BlockPos at = hit.getBlockPos();
            if (mc.level.getBlockState(at).getBlock() != bi.getBlock()) at = at.relative(hit.getDirection());
            if (mc.level.getBlockState(at).getBlock() != bi.getBlock()) return;       // nothing of it there: not a placement
            ACTIONS.placed(BuiltInRegistries.ITEM.getKey(used.getItem()).toString(), at.getX(), at.getY(), at.getZ(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            warn("placed", e);
        }
    }

    public static void attacked() {
        ACTIONS.attacked(System.currentTimeMillis());
    }

    /** What is under the crosshair now, or null (client thread). */
    static OwnerActions.Target target(Minecraft mc) {
        try {
            HitResult h = mc.hitResult;
            if (h == null || mc.level == null || h.getType() == HitResult.Type.MISS) return null;
            if (h instanceof BlockHitResult b) {
                BlockPos p = b.getBlockPos();
                BlockState s = mc.level.getBlockState(p);
                if (s.isAir()) return null;
                return new OwnerActions.Target(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), p.getX(), p.getY(), p.getZ(), false);
            }
            if (h instanceof EntityHitResult eh) {
                Entity e = eh.getEntity();
                BlockPos p = e.blockPosition();
                return new OwnerActions.Target(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), p.getX(), p.getY(), p.getZ(), true);
            }
        } catch (RuntimeException e) {
            warn("target", e);
        }
        return null;
    }

    private static void warn(String where, RuntimeException e) {
        if (warned) return;
        warned = true;
        LOG.warn("[entropycompanion] owner {} hook: {} (further errors not logged)", where, e.toString());
    }
}
