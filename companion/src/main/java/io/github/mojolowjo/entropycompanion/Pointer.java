package io.github.mojolowjo.entropycompanion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * The Minecraft side of point-and-command: a 64-block ray from the owner's eyes (vanilla's hitResult reaches only
 * about 4.5 blocks), the nearest entity on it before the first block, turned into a {@link PointRules.Hit}.
 */
final class Pointer {
    static final double RANGE = 64;

    private Pointer() {}

    static PointRules.Hit hit(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return PointRules.Hit.miss();
        float pt = 1f;
        Vec3 eye = p.getEyePosition(pt), look = p.getViewVector(pt);
        HitResult block = p.pick(RANGE, pt, false);
        double maxD = block.getType() == HitResult.Type.MISS ? RANGE : block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(look.scale(maxD));
        AABB box = p.getBoundingBox().expandTowards(look.scale(maxD)).inflate(1.0);
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p, eye, end, box,
                e -> e != p && !e.isSpectator() && (e.isPickable() || e instanceof ItemEntity), maxD * maxD);
        if (eh != null) return entity(eh.getEntity());
        if (block instanceof BlockHitResult bh && block.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bh.getBlockPos();
            BlockState st = mc.level.getBlockState(pos);
            BlockEntity be = mc.level.getBlockEntity(pos);
            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            boolean container = be instanceof MenuProvider || be instanceof Container;
            return PointRules.Hit.block(id, pos.getX(), pos.getY(), pos.getZ(), st.is(Tags.Blocks.ORES), st.is(BlockTags.LOGS), container);
        }
        return PointRules.Hit.miss();
    }

    private static PointRules.Hit entity(Entity e) {
        boolean monster = e instanceof Enemy || e.getType().getCategory() == MobCategory.MONSTER;
        boolean owned = (e instanceof OwnableEntity o && o.getOwnerUUID() != null) || (e instanceof TamableAnimal t && t.isTame());
        boolean pet = owned || (!monster && e.hasCustomName());
        BlockPos b = e.blockPosition();
        return PointRules.Hit.entity(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), e.getId(), b.getX(), b.getY(), b.getZ(),
                monster, e instanceof Player, pet, e instanceof AbstractVillager || e instanceof Npc, e instanceof ItemEntity);
    }

    /** C3: {x, y, z, 1} for the block under the crosshair within 64, else {feet x, y, z, 0}. */
    static int[] cornerBlock(Minecraft mc) {
        LocalPlayer p = mc.player;
        HitResult h = p.pick(RANGE, 1f, false);
        if (h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK) {
            BlockPos b = bh.getBlockPos();
            return new int[] {b.getX(), b.getY(), b.getZ(), 1};
        }
        BlockPos f = p.blockPosition();
        return new int[] {f.getX(), f.getY(), f.getZ(), 0};
    }
}
