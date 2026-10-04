package io.github.mojolowjo.entropybot.engine;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Act;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Cell;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Mode;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * The creeper duel (B7e C, 2026-10-04): with a sword (or an axe) the bot kills a lone creeper by hitting it once with a
 * sprint hit and backing straight off along a checked line until the fuse cools, then again, instead of running. The
 * rules are {@link CreeperRules}; this side reads the world and drives the keys (as the stuck-step does), with Baritone
 * held by the {@link EngineProcess}. It never breaks or places blocks. {@link Reflexes} owns it: the reflex shows as
 * FIGHTING, so the current job is held and carries on after.
 */
public final class CreeperDuel {
    private static final Logger LOG = LogUtils.getLogger();

    /** How a duel ended. flee: the reflexes run from the creeper as before. */
    public record End(String text, boolean flee, boolean exploded, boolean killed, int x, int y, int z) {}

    private final EventRing events;
    private volatile Mode mode = Mode.MELEE;
    private Creeper creeper;
    private CreeperRules.Cycle cycle;
    private long startTick, nextCheck, nextDraw, bestBackTick;
    private float creeperHp, maxSwelling, lastSwelling;
    private double dist, backX, backZ, bestBack, lastCx, lastCz, cvx, cvz;
    private Act lastAct;
    private int arrows;
    private final Map<Integer, Long> banned = new HashMap<>();
    private int refusedId = -1;
    private long refusedAt;
    private String refusedWhy;

    public CreeperDuel(EventRing events) {
        this.events = events;
    }

    public Mode mode() { return mode; }

    public void setMode(Mode m) {
        mode = m == null ? Mode.MELEE : m;
    }

    public boolean active() { return creeper != null; }

    public double dist() { return dist; }

    public String describe() {
        if (creeper == null) return "none";
        return "dueling a creeper (" + cycle.phase().name().toLowerCase() + ", " + cycle.hits() + " hits" + (arrows > 0 ? ", " + arrows + " arrows" : "") + ")";
    }

    // ---- starting ----

    /** Whether to take the creeper on: null = yes ({@link #start} next), else why not (an event once per creeper and reason). */
    public String check(Minecraft mc, LocalPlayer p, Creeper c, long now, boolean hurt) {
        if (mode == Mode.FLEE) return "flee is set";
        if (c.getId() == refusedId && now - refusedAt < 10) return refusedWhy;
        banned.values().removeIf(until -> until <= now);
        CreeperRules.Situation s = situation(mc, p, c, hurt, true, now);
        String why = CreeperRules.refusal(s);
        // review: no sprint at food 6 or less (the back-off is too slow for the fuse); never with a menu open (a job's chest)
        if (why == null && p.getFoodData().getFoodLevel() <= 6) why = "food " + p.getFoodData().getFoodLevel() + " is too low to sprint";
        if (why == null && (mc.screen != null || p.containerMenu != p.inventoryMenu)) why = "a menu is open";
        if (why != null) {
            if (c.getId() != refusedId || !why.equals(refusedWhy)) {
                events.push("reflex", "not fighting the creeper " + CreeperRules.fmt(p.distanceTo(c)) + " away: " + why, null);
            }
            refusedId = c.getId();
            refusedAt = now;
            refusedWhy = why;
            return why;
        }
        return null;
    }

    /** Starts the duel with the creeper {@link #check} allowed. */
    public void start(Minecraft mc, LocalPlayer p, Creeper c, long now) {
        creeper = c;
        cycle = new CreeperRules.Cycle();
        startTick = now;
        nextCheck = now + 20;
        creeperHp = c.getHealth();
        maxSwelling = 0;
        lastSwelling = 0;
        lastAct = null;
        arrows = 0;
        lastCx = c.getX();
        lastCz = c.getZ();
        cvx = cvz = 0;
        dist = p.distanceTo(c);
        mc.options.keyShift.setDown(false);
        LOG.info("[entropybot] creeper duel: taking on the creeper at {} {} {} ({} away, mode {})", c.getBlockX(), c.getBlockY(), c.getBlockZ(),
                CreeperRules.fmt(dist), mode.word());
        events.push("reflex", "fighting a creeper " + CreeperRules.fmt(dist) + " away (hit and back off)", null);
    }

    private CreeperRules.Situation situation(Minecraft mc, LocalPlayer p, Creeper c, boolean hurt, boolean withLine, long now) {
        Level lv = mc.level;
        int creepers = 0;
        boolean others = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || !(e instanceof Enemy)) continue;
            double d = p.distanceTo(e);
            if (e instanceof Creeper) {
                if (d <= CreeperRules.LOOK) creepers++;
                continue;
            }
            if (e instanceof NeutralMob && !hurt) continue;
            if (d > ReflexRules.lookRadius(hurt)) continue;
            if (ReflexRules.counts(d, hurt, hurt || d <= 2.5 || p.hasLineOfSight(e))) others = true;
        }
        String dim = Guard.dimOf(lv);
        var protect = Guard.INSTANCE.core.policy().protect;
        boolean nearProtect = CreeperRules.nearBox(protect, dim, c.getX(), c.getY(), c.getZ(), CreeperRules.PROTECT_MARGIN)
                || CreeperRules.nearBox(protect, dim, p.getX(), p.getY(), p.getZ(), CreeperRules.PROTECT_MARGIN);
        int be = 0, built = 0;
        BlockPos cp = c.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(cp.offset(-5, -5, -5), cp.offset(5, 5, 5))) {
            if (q.distSqr(cp) > 25) continue;
            BlockState st = lv.getBlockState(q);
            if (st.isAir()) continue;
            if (st.hasBlockEntity()) be++;
            else if (Guard.INSTANCE.isProtectedBlock(st.getBlock())) built++;
        }
        CreeperRules.Line line = withLine ? line(lv, p, p.getX() - c.getX(), p.getZ() - c.getZ()) : new CreeperRules.Line(CreeperRules.LINE_NEED, true, null);
        return new CreeperRules.Situation(mode, weaponRank(p), p.getHealth(), creepers, others, c.isPowered(), nearProtect,
                CreeperRules.nearBuilds(be, built), line.clear(), line.open(), line.stop(), banned.getOrDefault(c.getId(), 0L) > now);
    }

    // ---- the duel ----

    /** Once a tick while active: null while it goes on, else how it ended (the keys are up again by then). */
    public End tick(Minecraft mc, LocalPlayer p, long now, boolean hurt) {
        Creeper c = creeper;
        if (c.isDeadOrDying()) return end(mc, p, now, "killed a creeper (" + cycle.hits() + " hits" + (arrows > 0 ? ", " + arrows + " arrows" : "") + ")", false, false, true);
        if (c.isRemoved()) {
            boolean boom = c.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK && Math.max(lastSwelling, maxSwelling) >= 0.5f;
            if (boom) return end(mc, p, now, "a creeper exploded at " + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ()
                    + " (" + CreeperRules.fmt(dist) + " from me, after " + cycle.hits() + " hits)", false, true, false);
            return end(mc, p, now, "lost the creeper (" + (c.getRemovalReason() == null ? "gone" : c.getRemovalReason().name().toLowerCase()) + ")", false, false, false);
        }
        dist = p.distanceTo(c);
        if (dist > 20) return end(mc, p, now, "the creeper walked off", false, false, false);
        if (p.getHealth() <= CreeperRules.ABORT_HEALTH) return end(mc, p, now, "health " + Math.round(p.getHealth()) + ", running from the creeper", true, false, false);
        if (now >= nextCheck) {
            nextCheck = now + 20;
            CreeperRules.Situation s = situation(mc, p, c, hurt, false, now);
            String why = s.creepers() != 1 ? s.creepers() + " creepers now" : s.otherMonsters() ? "another monster came" : s.powered() ? "it is charged"
                    : s.nearProtect() ? "too close to a protect box" : s.nearBuilds() ? "it is next to built blocks" : null;
            if (why != null) return end(mc, p, now, "stopped the creeper duel: " + why, true, false, false);
        }
        float hp = c.getHealth();
        if (hp < creeperHp - 0.01f) cycle.damaged();
        creeperHp = hp;
        int dir = c.getSwellDir();
        lastSwelling = c.getSwelling(1f);
        maxSwelling = Math.max(maxSwelling * 0.98f, lastSwelling);
        cvx = cvx * 0.5 + (c.getX() - lastCx) * 0.5;
        cvz = cvz * 0.5 + (c.getZ() - lastCz) * 0.5;
        lastCx = c.getX();
        lastCz = c.getZ();

        boolean backing = cycle.phase() == CreeperRules.Phase.BACK;
        double ux = backing ? backX : p.getX() - c.getX(), uz = backing ? backZ : p.getZ() - c.getZ();
        CreeperRules.Line ln = line(mc.level, p, ux, uz);
        boolean stalled = false;
        if (backing) {
            // progress along the line (the bot's own, whatever the creeper does)
            double along = p.getX() * backX + p.getZ() * backZ;
            if (along > bestBack + 0.05) {
                bestBack = along;
                bestBackTick = now;
            }
            stalled = now - bestBackTick > 12;
        }
        boolean weapon = weaponRankOf(p.getMainHandItem()) > 0;
        float cooldown = weapon ? p.getAttackStrengthScale(0f) : 0f;
        boolean bowReady = mode == Mode.BOW && dist >= CreeperRules.BOW_MIN && dist <= CreeperRules.BOW_MAX && bowSlot(p) >= 0
                && p.hasLineOfSight(c) && !Double.isNaN(aimPitch(p, c));
        // the ground towards it: a charge never runs into a hole or water (the last 1.5 blocks are the creeper's own)
        boolean pathIn = true;
        if (!backing) {
            double need = Math.min(Math.max(dist - 1.5, 0), 6);
            pathIn = need < 0.5 || CreeperRules.retreatLine((x, y, z) -> cell(mc.level, new BlockPos(x, y, z)), p.getX(), footY(mc.level, p), p.getZ(),
                    c.getX() - p.getX(), c.getZ() - p.getZ(), need).clear() >= need;
        }
        Act a = cycle.step(now, new CreeperRules.Obs(dist, dir, lastSwelling, cooldown, ln.clear(), stalled, bowReady, pathIn));
        if (a != Act.SHOOT) stopDrawing(mc, p);
        switch (a) {
            case WAIT -> {
                moveKeys(mc, false, false);
                holdWeapon(mc, p);
                face(p, c);
            }
            case CHARGE -> {
                holdWeapon(mc, p);
                face(p, c);
                moveKeys(mc, true, p.horizontalCollision && p.onGround());
                if (p.getFoodData().getFoodLevel() > 6 && !p.isSprinting()) p.setSprinting(true);
            }
            case HIT -> {
                face(p, c);
                mc.gameMode.attack(p, c);
                p.swing(InteractionHand.MAIN_HAND);
                moveKeys(mc, false, false);
                startBack(p, c, now);
            }
            case BACK -> {
                if (lastAct != Act.BACK && lastAct != Act.HIT) startBack(p, c, now);
                try { p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(p.getX() + backX * 5, p.getEyeY(), p.getZ() + backZ * 5)); } catch (RuntimeException ignored) {}
                moveKeys(mc, true, p.horizontalCollision && p.onGround());
                if (p.getFoodData().getFoodLevel() > 6 && !p.isSprinting()) p.setSprinting(true);
            }
            case SHOOT -> {
                moveKeys(mc, false, false);
                shoot(mc, p, c, now);
            }
            case FLEE -> {
                lastAct = a;
                return end(mc, p, now, "ran from the creeper: " + cycle.why(), true, false, false);
            }
        }
        lastAct = a;
        return null;
    }

    /** Backs off straight away from where the creeper is now. */
    private void startBack(LocalPlayer p, Creeper c, long now) {
        double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ(), len = Math.max(Math.sqrt(dx * dx + dz * dz), 0.01);
        backX = dx / len;
        backZ = dz / len;
        bestBack = p.getX() * backX + p.getZ() * backZ;
        bestBackTick = now;
    }

    /** Ends a duel from outside (a settle: self-defence off, death, a retreat): keys up, no event of its own. */
    public void abort(String why) {
        if (creeper == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) stopDrawing(mc, mc.player);
        moveKeys(mc, false, false);
        LOG.info("[entropybot] creeper duel: stopped ({}) after {} hits", why, cycle.hits());
        creeper = null;
    }

    private End end(Minecraft mc, LocalPlayer p, long now, String text, boolean flee, boolean exploded, boolean killed) {
        Creeper c = creeper;
        stopDrawing(mc, p);
        moveKeys(mc, false, false);
        if (flee) banned.put(c.getId(), now + CreeperRules.GIVE_UP_TICKS);
        long secs = (now - startTick) / 20;
        LOG.info("[entropybot] creeper duel: {} after {} s, {} hits, {} charges", text, secs, cycle.hits(), cycle.charges());
        events.push("job", "creeper duel: " + text + " (" + secs + " s)", null);
        creeper = null;
        return new End(text, flee, exploded, killed, c.getBlockX(), c.getBlockY(), c.getBlockZ());
    }

    // ---- keys and hands ----

    private static void moveKeys(Minecraft mc, boolean forward, boolean jump) {
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(forward);
        mc.options.keyJump.setDown(jump);
        if (!forward && mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private static void face(LocalPlayer p, Creeper c) {
        try { p.lookAt(EntityAnchorArgument.Anchor.EYES, c.getEyePosition()); } catch (RuntimeException ignored) {}
    }

    static int weaponRankOf(ItemStack s) {
        return s.getItem() instanceof SwordItem ? 2 : s.getItem() instanceof AxeItem ? 1 : 0;
    }

    private static int weaponRank(LocalPlayer p) {
        int best = 0;
        for (int i = 0; i < 36; i++) best = Math.max(best, weaponRankOf(p.getInventory().getItem(i)));
        return best;
    }

    /** The best sword (else axe) into the hand; never a pickaxe or an empty hand. */
    private static void holdWeapon(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int best = -1, bestRank = 0;
        for (int i = 0; i < 36; i++) {
            int r = weaponRankOf(inv.getItem(i));
            if (r > bestRank) { bestRank = r; best = i; }
        }
        if (best < 0 || best == inv.selected || weaponRankOf(p.getMainHandItem()) >= bestRank) return;
        Hotbar.toHand(mc, p, best);
    }

    // ---- the bow (mode bow only) ----

    private static int bowSlot(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BowItem && !p.getProjectile(s).isEmpty()) return i;
        }
        return -1;
    }

    /** The pitch for a full-draw arrow at the creeper's middle, or NaN out of range. */
    private double aimPitch(LocalPlayer p, Creeper c) {
        Vec3 eye = p.getEyePosition();
        double dx = c.getX() - eye.x, dz = c.getZ() - eye.z;
        return CreeperRules.bowPitch(Math.sqrt(dx * dx + dz * dz), c.getY() + c.getBbHeight() * 0.5 - (eye.y - 0.1), CreeperRules.ARROW_SPEED);
    }

    private void shoot(Minecraft mc, LocalPlayer p, Creeper c, long now) {
        if (!(p.getMainHandItem().getItem() instanceof BowItem)) {
            int slot = bowSlot(p);
            if (slot >= 0 && !p.isUsingItem()) Hotbar.toHand(mc, p, slot);
            return;
        }
        // aim with lead: where the creeper will be when the arrow arrives
        Vec3 eye = p.getEyePosition();
        double pitch = aimPitch(p, c);
        if (Double.isNaN(pitch)) return;
        double dx0 = c.getX() - eye.x, dz0 = c.getZ() - eye.z;
        double ft = CreeperRules.flightTicks(Math.sqrt(dx0 * dx0 + dz0 * dz0), pitch, CreeperRules.ARROW_SPEED);
        double tx = c.getX() + cvx * ft, tz = c.getZ() + cvz * ft;
        double dx = tx - eye.x, dz = tz - eye.z;
        pitch = CreeperRules.bowPitch(Math.sqrt(dx * dx + dz * dz), c.getY() + c.getBbHeight() * 0.5 - (eye.y - 0.1), CreeperRules.ARROW_SPEED);
        if (Double.isNaN(pitch)) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot((float) pitch);
        if (!p.isUsingItem()) {
            if (now >= nextDraw) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                mc.options.keyUse.setDown(true);
            }
            return;
        }
        mc.options.keyUse.setDown(true);
        if (p.getTicksUsingItem() > CreeperRules.BOW_DRAW) {
            mc.gameMode.releaseUsingItem(p);
            mc.options.keyUse.setDown(false);
            arrows++;
            nextDraw = now + 5;
        }
    }

    private static void stopDrawing(Minecraft mc, LocalPlayer p) {
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof BowItem) mc.gameMode.releaseUsingItem(p);
        if (p.getMainHandItem().getItem() instanceof BowItem || p.getUseItem().getItem() instanceof BowItem) mc.options.keyUse.setDown(false);
    }

    // ---- the world as the rules see it ----

    private static CreeperRules.Line line(Level lv, LocalPlayer p, double ux, double uz) {
        return CreeperRules.retreatLine((x, y, z) -> cell(lv, new BlockPos(x, y, z)), p.getX(), footY(lv, p), p.getZ(), ux, uz, CreeperRules.LINE_NEED);
    }

    /** The block the feet are in; one higher when the bot stands on a slab or a path block (its cell is not free). */
    static int footY(Level lv, LocalPlayer p) {
        int y = (int) Math.floor(p.getY() + 1e-3);
        if (cell(lv, BlockPos.containing(p.getX(), y, p.getZ())) == Cell.FLOOR) y++;
        return y;
    }

    static Cell cell(Level lv, BlockPos pos) {
        if (!lv.hasChunkAt(pos)) return Cell.BAD;
        BlockState st = lv.getBlockState(pos);
        if (!st.getFluidState().isEmpty()) return Cell.LIQUID;
        Block b = st.getBlock();
        if (b instanceof BaseFireBlock || b instanceof CampfireBlock || b == Blocks.COBWEB || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.POWDER_SNOW
                || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK || b == Blocks.WITHER_ROSE || b == Blocks.POINTED_DRIPSTONE) return Cell.BAD;
        VoxelShape sh = st.getCollisionShape(lv, pos);
        if (sh.isEmpty()) return Cell.FREE;
        double top = sh.max(Direction.Axis.Y);
        if (top <= 0.2) return Cell.FREE;          // carpets, a thin snow layer: walked over
        if (top > 1.0) return Cell.BAD;            // fences, walls
        return top >= 0.5 ? Cell.FLOOR : Cell.BAD;
    }
}
