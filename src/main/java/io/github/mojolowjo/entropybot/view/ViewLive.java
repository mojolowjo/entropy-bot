package io.github.mojolowjo.entropybot.view;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.32.0 (operator view): the game side of the reply shape's HERE and NEW lines and of {@code look}. Vanilla client
 * Level / Entity reads only (no loader events, no mixins). Heightmaps: MOTION_BLOCKING only (live: the client is never sent
 * MOTION_BLOCKING_NO_LEAVES, which reads as the bottom of the world there). Never throws out (a failure is a line saying so).
 */
public final class ViewLive {
    private ViewLive() {}

    public static final News NEWS = News.GLOBAL;
    public static final ReplyShaper SHAPER = new ReplyShaper();

    // ---- HERE ----

    public static String here(LocalPlayer p) {
        try {
            if (p == null) return null;
            Level lv = p.level();
            int x = (int) Math.floor(p.getX()), y = (int) Math.floor(p.getY()), z = (int) Math.floor(p.getZ());
            StringBuilder sb = new StringBuilder().append(x).append(' ').append(y).append(' ').append(z);
            Box a = Core.INSTANCE.guard.core.policy().innermostAreaAt(Guard.dimOf(lv), x, y, z);
            sb.append(a == null ? ", outside my areas" : ", in " + a.name + " (" + a.type.name().toLowerCase() + ")");
            int depth = io.github.mojolowjo.entropybot.commands.Jobs.underDepth(lv, x, y, z);      // 0.34.3: the shared rule (leaves ignored)
            if (depth > 0) sb.append(", under ground (surface ").append(depth).append(" up)");
            sb.append(", hp ").append(Math.round(p.getHealth())).append(" food ").append(p.getFoodData().getFoodLevel());
            return sb.toString();
        } catch (RuntimeException e) {
            return "(here unknown: " + e + ")";
        }
    }

    // ---- NEW: the client tick's own observations (every second) ----

    private static float lastHp = -1;
    private static long lastHostileMs;
    private static Boolean lastNight;
    static final ToolWear WEAR = new ToolWear();

    public static void tick(LocalPlayer p) {
        try {
            if (p == null || p.isDeadOrDying()) { lastHp = -1; return; }
            float hp = p.getHealth();
            if (lastHp >= 0 && lastHp - hp >= 2) NEWS.push(News.DAMAGE, "lost " + Math.round(lastHp - hp) + " hp (now " + Math.round(hp) + ")");
            lastHp = hp;
            java.util.Map<Integer, ToolWear.Worn> worn = new java.util.HashMap<>();
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var st = inv.getItem(i);
                if (!st.isEmpty() && st.isDamageableItem())
                    worn.put(i, new ToolWear.Worn(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
            }
            for (String n : WEAR.update(worn)) NEWS.push(News.TOOL, n);
            Level lv = p.level();
            long t = lv.getDayTime() % 24000;
            boolean night = t >= 13000 && t < 23000;
            if (lastNight != null && night != lastNight) NEWS.push(News.TIME, night ? "night fell" : "day broke");
            lastNight = night;
            long now = System.currentTimeMillis();
            if (now - lastHostileMs > 30000) {
                Entity best = null;
                double bd = 16 * 16;
                for (Entity e : lv.getEntities(p, new AABB(p.blockPosition()).inflate(16), e -> e instanceof Enemy && e.isAlive())) {
                    double d = e.distanceToSqr(p);
                    if (d < bd) { bd = d; best = e; }
                }
                if (best != null) {
                    lastHostileMs = now;
                    NEWS.push(News.HOSTILE, best.getType().getDescription().getString().toLowerCase() + " " + Math.round(Math.sqrt(bd)) + " "
                            + compass(best.getX() - p.getX(), best.getZ() - p.getZ()));
                }
            }
        } catch (RuntimeException ignored) {
            // the news is a convenience; a failed read only skips this second
        }
    }

    static String compass(double dx, double dz) {
        String ns = dz < -Math.abs(dx) / 2 ? "N" : dz > Math.abs(dx) / 2 ? "S" : "";
        String ew = dx > Math.abs(dz) / 2 ? "E" : dx < -Math.abs(dz) / 2 ? "W" : "";
        String s = ns + ew;
        return s.isEmpty() ? "here" : s;
    }

    // ---- look ----

    static char topChar(BlockState st, Level lv, BlockPos q) {
        if (!st.getFluidState().isEmpty()) return st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? '!' : '~';
        if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) return 'T';
        if (lv.getBlockEntity(q) instanceof net.minecraft.world.Container) return 'C';
        if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return 'D';
        if (Guard.INSTANCE.isProtectedBlock(st.getBlock())) return 'W';
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        if (id.contains("stone") || id.contains("deepslate") || id.endsWith("_ore") || id.contains("andesite") || id.contains("diorite")
                || id.contains("granite") || id.contains("tuff") || id.equals("gravel")) return '#';
        if (id.equals("cobblestone")) return 'W';
        return '.';
    }

    static LookMap.Cells cells(Level lv) {
        return new LookMap.Cells() {
            @Override public char top(int x, int z) {
                BlockPos b = new BlockPos(x, lv.getMinBuildHeight(), z);
                if (!lv.isLoaded(b)) return '?';
                int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                BlockPos q = new BlockPos(x, y, z);
                return topChar(lv.getBlockState(q), lv, q);
            }

            @Override public int topY(int x, int z) {
                return lv.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            }

            @Override public char at(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                if (!lv.isLoaded(q)) return '?';
                BlockState st = lv.getBlockState(q);
                if (!st.getFluidState().isEmpty()) return st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? '!' : '~';
                if (st.getCollisionShape(lv, q).isEmpty()) return st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock ? 'D' : '.';
                char t = topChar(st, lv, q);
                if (t == '#' && BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore")) return 'o';
                return t == '.' || t == 'T' ? '#' : t;
            }
        };
    }

    static List<int[]> mobs(LocalPlayer p, int r) {
        List<int[]> out = new ArrayList<>();
        for (Entity e : p.level().getEntities(p, new AABB(p.blockPosition()).inflate(r + 1, 8, r + 1), e -> e instanceof Enemy && e.isAlive()))
            out.add(new int[]{(int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ())});
        return out;
    }

    /** "look [area]": the map around me, or centred on an area box (its size, at most 31 wide). */
    public static String look(LocalPlayer p, String rest) {
        try {
            if (p == null) return "error: not in a world";
            Level lv = p.level();
            int[] me = {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
            String a = rest == null ? "" : rest.trim();
            if (!a.isEmpty()) {
                Box b = null;
                for (Box x : Core.INSTANCE.guard.core.policy().areas) if (x.name.equalsIgnoreCase(a)) { b = x; break; }
                if (b == null) return "error: I have no area called " + a + " - next: area list";
                int cx = (b.x1 + b.x2) / 2, cz = (b.z1 + b.z2) / 2;
                int r = Math.min(15, Math.max(7, Math.max(b.x2 - b.x1, b.z2 - b.z1) / 2 + 2));
                // live: from under ground every column read "^": an area map is levelled at its floor (or its centre's ground)
                int ref = b.y1 > Box.ALL_Y_MIN ? b.y1 + 1 : lv.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
                return LookMap.overhead(cells(lv), cx, cz, ref, r, me, mobs(p, 40), "area " + b.name);
            }
            if (io.github.mojolowjo.entropybot.commands.Jobs.underDepth(lv, me[0], me[1], me[2]) > 0)      // 0.34.3: the shared rule
                return LookMap.slices(cells(lv), me[0], me[1], me[2], 7, 3, mobs(p, 8));
            return LookMap.overhead(cells(lv), me[0], me[2], me[1], 7, me, mobs(p, 8), "around me");
        } catch (RuntimeException e) {
            return "error: look failed (" + e + ")";
        }
    }
}
