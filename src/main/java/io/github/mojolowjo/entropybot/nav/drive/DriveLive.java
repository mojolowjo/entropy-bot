package io.github.mojolowjo.entropybot.nav.drive;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.engine.Hotbar;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.mixin.DriveMixinGameModeAccess;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nav step 8, the game side of our driver: the owner's test verbs ({@code aa nav look|walk|break|place|stop|status}),
 * and on each client tick's start what the {@link Driver} asks for: the keys ({@link NavInput} in {@code player.input}),
 * the head turn ({@link LookMath}, set on the player), a held break ({@code continueDestroyBlock}, the
 * {@code isDestroying} flag set false after it as Baritone's BlockBreakHelper does), a right click ({@code useItemOn}), and
 * the hand ({@link HotbarChoice}). Each task's end is one {@code [entropybot] nav ...} line in latest.log. A verb is refused
 * while Baritone drives, and the driver lets go when Baritone starts (MoversTest: nav/drive/ is the second home of
 * keys, camera, break and place). Pure (JUnit: none, the adapter; DriverTest, LookMathTest, KeysTest and
 * HotbarChoiceTest cover the decisions). Loader notes: NeoForge {@code ClientTickEvent.Pre} (EntropyBot); vanilla
 * {@code LocalPlayer.input}, {@code setYRot/setXRot}, {@code Entity.pick}, {@code Level.clip},
 * {@code MultiPlayerGameMode.continueDestroyBlock / stopDestroyBlock / useItemOn}, {@code BlockState.getDestroyProgress},
 * {@code ItemStack.getDestroySpeed / isCorrectToolForDrops}, {@code Inventory.selected}; the accessor mixin
 * {@link DriveMixinGameModeAccess}; Baritone's API for "is it driving". Fabric: the same vanilla calls, its client tick
 * callback, and the accessor through a refmap.
 */
public final class DriveLive {
    public static final DriveLive INSTANCE = new DriveLive();
    private static final Logger LOG = LogUtils.getLogger();

    private final DriveConfig config = DriveConfig.defaults();
    private final Driver driver = new Driver(config);
    private final Keys keys = new Keys();
    private ClientLevel level;
    private boolean hitting;
    private String last = "nothing yet";

    private DriveLive() {}

    /** aa nav ...: the owner's test verbs. Returns the reply; the result comes later as a log line. */
    public String command(String arg) {
        String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        String verb = a.length == 0 ? "" : a[0];
        try {
            switch (verb) {
                case "status":
                    return "nav: " + (driver.task() == null ? "idle" : Driver.name(driver.task()) + " running") + ", keys " + keys + "; last: " + last;
                case "stop": {
                    String line = driver.stop("aa nav stop");
                    letGo(Minecraft.getInstance());
                    if (line != null) {
                        say(line);
                    }
                    return line == null ? "nav: nothing running" : "nav " + line;
                }
                case "look":
                    return start(new Driver.Look(Float.parseFloat(a[1]), Float.parseFloat(a[2])));
                case "walk":
                    return start(new Driver.Walk(Double.parseDouble(a[1])));
                case "break":
                    return start(new Driver.Break(new BetterBlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]))));
                case "place":
                    return start(new Driver.Place(new BetterBlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]))));
                default:
                    return USAGE;
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            return "nav: " + e.getMessage() + " - " + USAGE;
        }
    }

    static final String USAGE = "usage: aa nav look <yaw> <pitch> | walk <blocks> | break <x y z> | place <x y z> | stop | status";

    private String start(Driver.Task t) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            return "nav: not started, no world";
        }
        if (baritoneDriving()) {
            return "nav " + Driver.name(t) + ": not started, Baritone is driving (aa nav waits until it is idle)";
        }
        if (t instanceof Driver.Break b && Guard.INSTANCE.vetoBreak(new BlockPos(b.pos().x, b.pos().y, b.pos().z))) {
            say(Driver.name(t) + ": not started, the guard refuses this block (its log line says why) [failed]");
            return "nav " + Driver.name(t) + ": not started, the guard refuses this block";
        }
        String hand = hand(mc, p, t);
        String was = driver.start(t);
        level = mc.level;
        if (was != null) {
            say(was);
        }
        say(Driver.name(t) + ": started" + (hand.isEmpty() ? "" : ", " + hand));
        return "nav " + Driver.name(t) + ": started" + (hand.isEmpty() ? "" : ", " + hand) + " (the result goes to the log)";
    }

    /** ClientTickEvent.Pre: one driver tick. */
    public void preTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (driver.task() == null) {
            if (keys.moving() || hitting || (p != null && p.input instanceof NavInput)) {
                letGo(mc);
            }
            return;
        }
        if (p == null || mc.level == null || mc.gameMode == null) {
            String line = driver.stop("the world is gone");
            keys.releaseAll();
            hitting = false;
            say(line);
            return;
        }
        if (hitting) {
            ((DriveMixinGameModeAccess) mc.gameMode).entropybot$setDestroying(true);     // Baritone's restore of last tick's hold
        }
        Driver.Task task = driver.task();
        Driver.Act act = driver.tick(see(mc, p, task));
        keys.set(act.keys());
        if (keys.moving() && !(p.input instanceof NavInput)) {
            p.input = new NavInput(keys);
        } else if (!keys.moving() && p.input instanceof NavInput) {
            p.input = new KeyboardInput(mc.options);
        }
        if (act.look() != null) {
            LookMath.Turn n = LookMath.step(p.getYRot(), p.getXRot(), act.look().yaw(), act.look().pitch(), config.maxTurn(), mc.options.sensitivity().get());
            p.setYRot(n.yaw());
            p.setXRot(n.pitch());
        }
        HitResult hr = p.pick(p.blockInteractionRange(), 1.0f, false);
        if (act.hit() && hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) {
            if (mc.gameMode.continueDestroyBlock(b.getBlockPos(), b.getDirection())) {
                p.swing(InteractionHand.MAIN_HAND);
            }
            hitting = mc.gameMode.isDestroying();
            ((DriveMixinGameModeAccess) mc.gameMode).entropybot$setDestroying(false);   // vanilla's continueAttack(false) must not abort it
        } else if (hitting) {
            mc.gameMode.stopDestroyBlock();
            hitting = false;
        }
        if (act.use() && hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) {
            InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, b);
            if (r.consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
            }
        }
        if (act.ended() != null) {
            letGo(mc);
            say(act.ended() + (act.ok() ? "" : " [failed]"));
        }
    }

    private Driver.See see(Minecraft mc, LocalPlayer p, Driver.Task t) {
        Driver.Target target = switch (t) {
            case Driver.Break b -> breakTarget(mc, p, b.pos());
            case Driver.Place pl -> placeTarget(mc, p, pl.pos());
            default -> null;
        };
        return new Driver.See(p.getYRot(), p.getXRot(), p.getX(), p.getZ(), mc.isPaused(), baritoneDriving(), mc.level == level, target);
    }

    private Driver.Target breakTarget(Minecraft mc, LocalPlayer p, BetterBlockPos cell) {
        BlockPos pos = new BlockPos(cell.x, cell.y, cell.z);
        BlockState st = mc.level.getBlockState(pos);
        BlockHitResult under = crosshair(p);
        boolean empty = st.isAir() || st.canBeReplaced();
        Vec3 aim = empty ? null : visible(mc, p, pos);
        String refusal = null;
        if (!empty && aim == null) {
            refusal = String.format(Locale.ROOT, "no face of %s in sight within reach (%.1f blocks, reach %.1f)",
                    name(st), Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))), p.blockInteractionRange());
        }
        LookMath.Turn turn = aim == null ? new LookMath.Turn(p.getYRot(), p.getXRot()) : LookMath.toward(p.getX(), p.getEyeY(), p.getZ(), aim.x, aim.y, aim.z);
        boolean on = under != null && under.getBlockPos().equals(pos);
        return new Driver.Target(empty, turn, on, empty ? 0f : st.getDestroyProgress(p, mc.level, pos), refusal, describe(mc, under));
    }

    private Driver.Target placeTarget(Minecraft mc, LocalPlayer p, BetterBlockPos cell) {
        BlockPos pos = new BlockPos(cell.x, cell.y, cell.z);
        BlockState st = mc.level.getBlockState(pos);
        BlockHitResult under = crosshair(p);
        boolean empty = st.isAir() || st.canBeReplaced();
        String refusal = null;
        Vec3 aim = null;
        Direction face = null;
        BlockPos support = null;
        if (empty) {
            ItemStack held = p.getMainHandItem();
            if (!fullBlock(held)) {
                refusal = "no full block in the hand (the bag has none)";
            } else if (p.getBoundingBox().intersects(new AABB(pos))) {
                refusal = "I stand in " + pos.toShortString();
            } else {
                for (Direction d : Direction.values()) {
                    BlockPos s = pos.relative(d);
                    BlockState ss = mc.level.getBlockState(s);
                    if (ss.isAir() || ss.canBeReplaced()) {
                        continue;
                    }
                    Vec3 point = Vec3.atCenterOf(pos).add(d.getStepX() * 0.51, d.getStepY() * 0.51, d.getStepZ() * 0.51);  // structural: just past the shared face
                    BlockHitResult r = ray(mc, p, point);
                    if (r != null && r.getBlockPos().equals(s) && r.getDirection() == d.getOpposite()) {
                        aim = point;
                        face = d.getOpposite();
                        support = s;
                        break;
                    }
                }
                if (aim == null) {
                    refusal = "no face to place against in sight within reach (reach " + p.blockInteractionRange() + ")";
                }
            }
        }
        LookMath.Turn turn = aim == null ? new LookMath.Turn(p.getYRot(), p.getXRot()) : LookMath.toward(p.getX(), p.getEyeY(), p.getZ(), aim.x, aim.y, aim.z);
        boolean on = under != null && support != null && under.getBlockPos().equals(support) && under.getDirection() == face;
        return new Driver.Target(empty, turn, on, 0f, refusal, empty ? describe(mc, under) : name(st));
    }

    /** The first of the block's centre and its six face centres the eyes see within reach, or null. */
    private static Vec3 visible(Minecraft mc, LocalPlayer p, BlockPos pos) {
        List<Vec3> points = new ArrayList<>();
        points.add(Vec3.atCenterOf(pos));
        for (Direction d : Direction.values()) {
            points.add(Vec3.atCenterOf(pos).add(d.getStepX() * 0.49, d.getStepY() * 0.49, d.getStepZ() * 0.49));   // structural: just inside a face
        }
        for (Vec3 v : points) {
            BlockHitResult r = ray(mc, p, v);
            if (r != null && r.getBlockPos().equals(pos)) {
                return v;
            }
        }
        return null;
    }

    /** The block the eyes' ray toward {@code point} hits within reach, or null. */
    private static BlockHitResult ray(Minecraft mc, LocalPlayer p, Vec3 point) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = point.subtract(eye);
        if (dir.length() > p.blockInteractionRange() + 0.87) {                      // structural: half a block's diagonal
            return null;
        }
        Vec3 end = eye.add(dir.normalize().scale(p.blockInteractionRange()));
        BlockHitResult r = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.BLOCK ? r : null;
    }

    private static BlockHitResult crosshair(LocalPlayer p) {
        HitResult hr = p.pick(p.blockInteractionRange(), 1.0f, false);
        return hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private static String describe(Minecraft mc, BlockHitResult under) {
        return under == null ? "nothing in reach" : name(mc.level.getBlockState(under.getBlockPos())) + " at " + under.getBlockPos().toShortString()
                + " (" + under.getDirection().getName() + " face)";
    }

    private static String name(BlockState st) {
        return st.getBlock().builtInRegistryHolder().key().location().getPath();
    }

    /** Puts the best tool (a break) or a full block (a place) in the hand; says what it chose, or "" for a look or walk. */
    private static String hand(Minecraft mc, LocalPlayer p, Driver.Task t) {
        BlockPos pos = switch (t) {
            case Driver.Break b -> new BlockPos(b.pos().x, b.pos().y, b.pos().z);
            case Driver.Place pl -> new BlockPos(pl.pos().x, pl.pos().y, pl.pos().z);
            default -> null;
        };
        if (pos == null) {
            return "";
        }
        BlockState st = mc.level.getBlockState(pos);
        var inv = p.getInventory();
        List<HotbarChoice.Slot> bag = new ArrayList<>();
        for (int i = 0; i < 36; i++) {                                               // structural: the player inventory's 36 slots
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() && i != inv.selected) {
                continue;
            }
            boolean correct = !st.requiresCorrectToolForDrops() || s.isCorrectToolForDrops(st);
            bag.add(new HotbarChoice.Slot(i, s.getCount(), s.isEmpty() ? 1f : s.getDestroySpeed(st), correct, fullBlock(s)));
        }
        int pick = t instanceof Driver.Break ? HotbarChoice.tool(bag, inv.selected) : HotbarChoice.block(bag, inv.selected);
        if (pick < 0) {
            return t instanceof Driver.Break ? "hand: " + itemName(p.getMainHandItem()) + " (nothing breaks it better)" : "hand: no full block in the bag";
        }
        if (pick != inv.selected) {
            if (pick < 9) {                                                          // structural: the hotbar is slots 0-8
                inv.selected = pick;
            } else if (!Hotbar.toHand(mc, p, pick)) {
                return "hand: " + itemName(inv.getItem(pick)) + " is in the bag but a menu is open";
            }
        }
        return "hand: " + itemName(p.getMainHandItem()) + " (slot " + inv.selected + ")";
    }

    /** A stack that places a full solid block that stays put (no falling block, no block entity). */
    private static boolean fullBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) {
            return false;
        }
        if (bi.getBlock() instanceof FallingBlock || bi.getBlock() instanceof EntityBlock) {
            return false;
        }
        BlockState st = bi.getBlock().defaultBlockState();
        return st.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static String itemName(ItemStack s) {
        return s.isEmpty() ? "empty hand" : s.getItem().builtInRegistryHolder().key().location().getPath();
    }

    private void letGo(Minecraft mc) {
        keys.releaseAll();
        LocalPlayer p = mc.player;
        if (p != null && p.input instanceof NavInput) {
            p.input = new KeyboardInput(mc.options);
        }
        if (hitting && mc.gameMode != null) {
            ((DriveMixinGameModeAccess) mc.gameMode).entropybot$setDestroying(true);
            mc.gameMode.stopDestroyBlock();
        }
        hitting = false;
    }

    private static boolean baritoneDriving() {
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            return b != null && (b.getPathingBehavior().isPathing() || b.getPathingControlManager().mostRecentInControl().isPresent());
        } catch (Throwable t) {
            Quiet.warn("DriveLive.baritoneDriving failed, counting Baritone as driving", t);
            return true;
        }
    }

    private void say(String line) {
        last = line;
        LOG.info("[entropybot] nav {}", line);
    }
}
