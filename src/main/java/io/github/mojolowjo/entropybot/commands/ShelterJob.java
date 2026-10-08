package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.camp.DayNight;
import io.github.mojolowjo.entropybot.camp.ShelterPlan;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.24.3 {@code shelter [5m] [keep] | shelter status | shelter leave}: a shell around the bot's own cell from carried
 * planks/cobblestone/dirt (ShelterPlan), a torch inside when it has one, then it waits until day (or the timer) - the
 * reflexes eat as usual - opens its own wall (only blocks it placed: a one-cell force lease each), steps out, and with
 * {@code keep} closes the hole behind it. Its blocks go in the restore ledger as {@code built: shelter}.
 * Loader notes: vanilla client calls (MultiPlayerGameMode.useItemOn via Clearing.placeAt, startDestroyBlock /
 * continueDestroyBlock, the forward key), nothing loader-specific.
 */
public final class ShelterJob {
    private static final Logger LOG = LogUtils.getLogger();
    static final long PLACE_TICKS = 6, BREAK_TICKS = 800, OUT_TICKS = 60, MAX_WAIT = 20 * 60 * 20;

    private ShelterJob() {}

    static final class State {
        ShelterPlan.Stage stage = ShelterPlan.Stage.BUILD;
        int[] feet;
        String block;
        long timer = -1, keep, startedAt, stageAt, lastAct;
        boolean sawNight, leave, torchDone;
        List<int[]> todo = new ArrayList<>();
        final Set<String> own = new HashSet<>();
        int[][] exit;
        int idx;
        int[] hit;
        LeaseSet leases;
        String note = "";
    }

    static volatile State current;

    static String status() {
        State s = current;
        if (s == null) return "shelter: not in one - shelter (until day) | shelter 5m | shelter keep";
        ClientLevel lv = Minecraft.getInstance().level;
        return "shelter: " + s.stage.name().toLowerCase() + " at " + Jobs.fmt(s.feet) + " (" + s.own.size() + " of my " + (s.block == null ? "" : GuiShort(s.block)) + " blocks"
                + (s.timer > 0 ? ", " + Math.max(0, (s.timer - (Core0.tick() - s.startedAt)) / 20) + " s left" : ", until day")
                + (lv == null ? "" : ", it is " + DayNight.clock(lv.getDayTime())) + ")" + (s.keep == 1 ? ", I close it behind me" : "");
    }

    private static String GuiShort(String id) { return id.replaceFirst("^minecraft:", ""); }

    /** "shelter ...". */
    static String start(Commands c, LocalPlayer p, String rest, boolean internal, String from) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.equals("status")) return status();
        if (r.equals("leave")) {
            State s = current;
            if (s == null || !c.jobs.running()) return "I'm not in a shelter";
            s.leave = true;
            return "ok: leaving my shelter";
        }
        long[] a = ShelterPlan.args(r);
        if (a == null) return "usage: shelter [5m|30s] [keep] | shelter status | shelter leave";
        if (c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
        ClientLevel lv = Minecraft.getInstance().level;
        int[] f = Jobs.here(p);
        List<int[]> todo = ShelterPlan.shell(f, x -> solid(lv, x));
        Map<String, Integer> bag = Gui.inventory(p);
        String block = ShelterPlan.material(bag, todo.size());
        if (block == null && !todo.isEmpty()) {
            String prep = ShelterPlan.prep(bag);
            if (internal || c.chains.running())
                return Hints.next("error: I need " + todo.size() + " planks, cobblestone or dirt for a shelter", prep + " then shelter (or dig some dirt)");
            return c.chains.startChain(from, "shelter", prep + " then shelter" + (r.isEmpty() ? "" : " " + r), 1);
        }
        if (!lv.getBlockState(new BlockPos(f[0], f[1] - 1, f[2])).isCollisionShapeFullBlock(lv, new BlockPos(f[0], f[1] - 1, f[2])))
            return Hints.next("error: no solid floor under me at " + Jobs.fmt(f), "goto a spot on solid ground, then shelter");
        State s = new State();
        s.feet = f;
        s.block = block;
        s.todo = todo;
        s.timer = a[0];
        s.keep = a[1];
        s.startedAt = Core0.tick();
        s.stageAt = s.startedAt;
        s.sawNight = SleepJob.night(lv);
        s.leases = Clearing.newLeases();
        // centre on the cell so the shell's blocks never touch the bot
        p.setPos(f[0] + 0.5, p.getY(), f[2] + 0.5);
        Seq.Step st = new Seq.Step("shelter");
        st.state = s;
        current = s;
        String res = c.jobs.startSeq(new Seq(c.jobs, c.storage, "sheltering at " + Jobs.fmt(f), List.of(st), "fail"), "fail");
        Jobs.Job j = c.jobs.job;
        if (j != null) {
            Runnable prev = j.onEnd;
            j.onEnd = () -> {
                if (prev != null) prev.run();
                end(s);
            };
        }
        return res;
    }

    private static void end(State s) {
        try { s.leases.releaseAll(); } catch (RuntimeException ignored) {}
        Minecraft mc = Minecraft.getInstance();
        try { mc.options.keyUp.setDown(false); } catch (RuntimeException ignored) {}
        try { if (mc.gameMode != null) mc.gameMode.stopDestroyBlock(); } catch (RuntimeException ignored) {}
        if (current == s) current = null;
    }

    static boolean solid(ClientLevel lv, int[] c) {
        BlockPos b = new BlockPos(c[0], c[1], c[2]);
        // a cell is closed when nothing can be put there: a full block, or a chest, a fence, a slab... (not air, grass, water)
        return !lv.getBlockState(b).canBeReplaced();
    }

    /** The Seq "shelter" step. */
    static String step(Seq s, Seq.Step st, LocalPlayer p) {
        State ps = (State) st.state;
        ClientLevel lv = Minecraft.getInstance().level;
        long now = s.now();
        boolean night = SleepJob.night(lv);
        if (night) ps.sawNight = true;
        if (now % 20 == 0) ps.leases.beat();
        try {
            boolean done = switch (ps.stage) {
                case BUILD -> build(s, ps, p, lv, now);
                case WAIT -> {
                    if (now % 100 == 0) s.setStatus(status());
                    yield false;
                }
                case OPEN -> open(ps, p, lv, now);
                case OUT -> out(ps, p, lv, now);
                case CLOSE -> close(ps, p, lv, now);
                case DONE -> true;
            };
            if (ps.note.startsWith("error")) {
                s.jobs.finish(ps.note);
                return "wait";
            }
            boolean timerUp = ps.timer > 0 && Core0.tick() - ps.startedAt >= ps.timer;
            boolean tooLong = ps.timer <= 0 && Core0.tick() - ps.startedAt >= MAX_WAIT && !night && ps.stage == ShelterPlan.Stage.WAIT;
            ShelterPlan.Stage n = ShelterPlan.next(ps.stage, night, ps.sawNight, ps.timer > 0, timerUp, ps.leave || tooLong, done, ps.keep == 1);
            if (n != ps.stage) {
                LOG.info("[entropybot] shelter: {} -> {}", ps.stage, n);
                if (n == ShelterPlan.Stage.WAIT) noteBuilt(ps, lv);
                if (n == ShelterPlan.Stage.OPEN) {
                    ps.exit = ShelterPlan.exit(ps.feet, ps.own);
                    if (ps.exit == null) {
                        s.jobs.finish("error: my shelter has no wall of my own to open (all its sides were there before) - next: stop, and dig me out by hand");
                        return "wait";
                    }
                    ps.idx = 0;
                }
                ps.stage = n;
                ps.stageAt = now;
                if (n == ShelterPlan.Stage.DONE) {
                    s.jobs.finish("ok: sheltered at " + Jobs.fmt(ps.feet) + " until " + DayNight.clock(lv.getDayTime()) + " and stepped out"
                            + (ps.keep == 1 ? " (closed it behind me)" : "") + " - it stays as my shelter");
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter step", e);
            s.jobs.finish("error: shelter: " + e);
        }
        return "wait";
    }

    private static boolean build(Seq s, State ps, LocalPlayer p, ClientLevel lv, long now) {
        if (now - ps.lastAct < PLACE_TICKS) return false;
        ps.lastAct = now;
        while (ps.idx < ps.todo.size() && solid(lv, ps.todo.get(ps.idx))) {
            ps.own.add(ShelterPlan.key(ps.todo.get(ps.idx)));
            ps.idx++;
        }
        if (ps.idx >= ps.todo.size()) {
            if (!ps.torchDone) {
                ps.torchDone = true;
                if (Gui.inventory(p).getOrDefault("minecraft:torch", 0) > 0) {
                    String r = Clearing.placeAt(p, "minecraft:torch", ps.feet[0], ps.feet[1] + 1, ps.feet[2], ps.leases);
                    LOG.info("[entropybot] shelter: torch inside: {}", r);
                }
                return false;
            }
            return true;
        }
        int[] c = ps.todo.get(ps.idx);
        s.setStatus("sheltering: building " + (ps.idx + 1) + "/" + ps.todo.size());
        String r = Clearing.placeAt(p, ps.block, c[0], c[1], c[2], ps.leases);
        if (r.startsWith("error")) {
            if (now - ps.stageAt > 200) ps.note = "error: couldn't build my shelter at " + Jobs.fmt(c) + ": " + r.replaceFirst("^error: ", "");
        }
        return false;
    }

    /** The shell's blocks in the restore ledger as "built: shelter" (never re-placed, never counted as a stranger's build). */
    private static void noteBuilt(State ps, ClientLevel lv) {
        List<int[]> cells = new ArrayList<>();
        for (String k : ps.own) {
            String[] w = k.split(" ");
            cells.add(new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
        }
        RestoreLive.INSTANCE.noteBuilt(cells, ps.block, "built: shelter");
    }

    private static boolean open(State ps, LocalPlayer p, ClientLevel lv, long now) {
        if (ps.idx >= 2) return true;
        int[] c = ps.exit[ps.idx];
        BlockPos bp = new BlockPos(c[0], c[1], c[2]);
        if (lv.getBlockState(bp).getCollisionShape(lv, bp).isEmpty()) {
            ps.idx++;
            ps.hit = null;
            ps.stageAt = now;
            return ps.idx >= 2;
        }
        if (!ShelterPlan.mayBreak(c, ps.own)) {
            ps.note = "error: the block at " + Jobs.fmt(c) + " isn't mine - I only open my own shelter";
            return false;
        }
        if (now - ps.stageAt > BREAK_TICKS) {
            ps.note = "error: my shelter wall at " + Jobs.fmt(c) + " didn't break in 40 s (no tool for it?)";
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (ps.hit == null) {
            holdTool(p, ps.block);
            // the own-block permission: a one-cell force lease (the guard refuses built blocks otherwise)
            String le = ps.leases.take("opening my shelter at " + Jobs.fmt(c), new ClearBox(c[0], c[1], c[2], c[0], c[1], c[2]), false, true);
            if (le != null) {
                ps.note = "error: couldn't open my shelter at " + Jobs.fmt(c) + ": " + le.replaceFirst("^error: ", "");
                return false;
            }
        }
        Vec3 center = Vec3.atCenterOf(bp);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        Vec3 eye = p.getEyePosition();
        Direction face = Direction.getNearest((float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z));
        if (ps.hit == null || ps.hit != c) {
            mc.gameMode.startDestroyBlock(bp, face);
            ps.hit = c;
        } else mc.gameMode.continueDestroyBlock(bp, face);
        p.swing(InteractionHand.MAIN_HAND);
        return false;
    }

    /** The fastest tool for the shell's block in hand: a pickaxe for stone, an axe for planks, a shovel for dirt (best tier first). */
    private static void holdTool(LocalPlayer p, String block) {
        String kind = block == null ? "_pickaxe" : block.endsWith("_planks") ? "_axe" : block.endsWith("dirt") ? "_shovel" : "_pickaxe";
        String best = null;
        int bestTier = -1;
        for (String id : Gui.inventory(p).keySet()) {
            if (!id.endsWith(kind) || (kind.equals("_axe") && id.endsWith("_pickaxe"))) continue;
            int t = io.github.mojolowjo.entropybot.brain.NightSafety.tierOf(id);
            if (t > bestTier) { best = id; bestTier = t; }
        }
        if (best != null) Clearing.holdItem(p, best);
    }

    private static boolean out(State ps, LocalPlayer p, ClientLevel lv, long now) {
        Minecraft mc = Minecraft.getInstance();
        int[] to = ps.exit[2];
        int[] me = Jobs.here(p);
        boolean outside = me[0] != ps.feet[0] || me[2] != ps.feet[2];
        if (outside && Math.abs(p.getX() - (ps.exit[0][0] + 0.5)) + Math.abs(p.getZ() - (ps.exit[0][2] + 0.5)) > 0.9) {
            mc.options.keyUp.setDown(false);
            ps.idx = 0;
            ps.stageAt = now;
            return true;
        }
        if (now - ps.stageAt > OUT_TICKS) {
            mc.options.keyUp.setDown(false);
            ps.note = "error: couldn't step out of my shelter (the way out at " + Jobs.fmt(ps.exit[0]) + " is open)";
            return false;
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(to[0] + 0.5, p.getEyeY(), to[2] + 0.5));
        mc.options.keyUp.setDown(true);
        return false;
    }

    private static boolean close(State ps, LocalPlayer p, ClientLevel lv, long now) {
        if (now - ps.lastAct < PLACE_TICKS) return false;
        ps.lastAct = now;
        if (ps.idx >= 2) return true;
        int[] c = ps.exit[ps.idx];                // the feet cell first (on the ground), then the head cell on it
        if (solid(lv, c)) {
            ps.idx++;
            return ps.idx >= 2;
        }
        String r = Clearing.placeAt(p, ps.block, c[0], c[1], c[2], ps.leases);
        if (r.startsWith("error") && now - ps.stageAt > 200) ps.note = "error: couldn't close my shelter at " + Jobs.fmt(c) + ": " + r.replaceFirst("^error: ", "");
        return false;
    }

    /** The core tick (a tiny indirection so the class reads without Core's import noise). */
    static final class Core0 {
        static long tick() { return io.github.mojolowjo.entropybot.Core.INSTANCE.tick(); }
    }
}
