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
        boolean sawNight, leave, torchDone, recovered;
        List<int[]> todo = new ArrayList<>();
        final Set<String> own = new HashSet<>();
        /** 0.27.3 (d3): natural shell cells the dig rules allow (underground inside the areas): a way out with one own cell. */
        final Set<String> dig = new HashSet<>();
        int[][] exit;
        int idx;
        /** 0.27.4: shell cells an entity stood in this pass; retried after a pause (3 rounds at most). */
        List<int[]> skipped = new ArrayList<>();
        int skipRounds;
        long skipAt = -1;
        String skipWhy = "";
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
        boolean armed = current != null && c.jobs.running();
        if (r.equals("status")) {
            if (armed) return status();
            State rec = recognise(c, p);
            if (rec == null) return status();
            return "shelter: in my shelter at " + Jobs.fmt(rec.feet) + " from before (no job knows it: a restart?), timer expired, "
                    + rec.own.size() + " of its blocks are mine - shelter leave opens my own wall";
        }
        if (r.equals("leave")) {
            if (armed) {
                current.leave = true;
                return "ok: leaving my shelter";
            }
            // 0.27.2: not armed (a restart forgot it): any shelter of its own the ledger knows around the bot
            State rec = recognise(c, p);
            if (rec == null) return "I'm not in a shelter";
            if (c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
            rec.leave = true;
            rec.startedAt = Core0.tick();
            rec.stageAt = rec.startedAt;
            rec.leases = Clearing.newLeases();
            LOG.info("[entropybot] shelter: re-armed my shelter at {} (from before, {} own blocks): leaving", Jobs.fmt(rec.feet), rec.own.size());
            String res = launch(c, p, rec, "leaving my shelter at " + Jobs.fmt(rec.feet));
            return res.startsWith("started") ? "ok: leaving my shelter at " + Jobs.fmt(rec.feet) + " (I knew it from before the restart)" : res;
        }
        long[] a = ShelterPlan.args(r);
        if (a == null) return "usage: shelter [5m|30s] [keep] | shelter status | shelter leave";
        if (c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
        ClientLevel lv = Minecraft.getInstance().level;
        int[] f = Jobs.here(p);
        // 0.27.3: an anchored order (every block leans on the ground, a clickable block or one placed before it)
        Clearing.LiveWorld lw = new Clearing.LiveWorld();
        lw.set(p);
        List<int[]> todo = ShelterPlan.ordered(f, x -> solid(lv, x), x -> io.github.mojolowjo.entropybot.clear.PlaceRules.clickable(lw, x[0], x[1], x[2]));
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
        // a shelter it built before (the ledger's "built: shelter") is its own again
        for (int[] cell : ShelterPlan.allCells(f)) if (ownBlocks(c).contains(ShelterPlan.key(cell)) || RestoreLive.INSTANCE.builtByMe(cell[0], cell[1], cell[2])) s.own.add(ShelterPlan.key(cell));
        if (s.block == null) s.block = ShelterPlan.material(bag, 2) != null ? ShelterPlan.material(bag, 2) : "minecraft:cobblestone";
        return launch(c, p, s, "sheltering at " + Jobs.fmt(f));
    }

    /** Starts the shelter job for state s (centred on its cell). */
    private static String launch(Commands c, LocalPlayer p, State s, String label) {
        int[] f = s.feet;
        // centre on the cell so the shell's blocks never touch the bot
        p.setPos(f[0] + 0.5, p.getY(), f[2] + 0.5);
        Seq.Step st = new Seq.Step("shelter");
        st.state = s;
        current = s;
        String res = c.jobs.startSeq(new Seq(c.jobs, c.storage, label, List.of(st), "fail"), "fail");
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
                    ps.exit = ShelterPlan.exit(ps.feet, ps.own, x -> ps.dig.contains(ShelterPlan.key(x)));      // 0.27.3: one own + one natural cell too
                    if (ps.exit == null) {
                        s.jobs.finish("error: my shelter has no wall of my own to open (all its sides were there before) - next: a goto (underground inside my areas I dig a staircase up), or dig me out by hand");
                        return "wait";
                    }
                    ps.idx = 0;
                }
                ps.stage = n;
                ps.stageAt = now;
                if (n == ShelterPlan.Stage.DONE) {
                    pruneOwn(ps, lv);
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
        if (ps.idx >= ps.todo.size() && !ps.skipped.isEmpty()) return retrySkipped(ps, lv, p, now);
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
        // 0.27.4: an entity in the cell (its corpse, a mob, a boat) is skipped for now: the other cells go on
        String taken = entityIn(lv, p, c);
        if (taken != null) {
            ps.skipped.add(c);
            ps.skipWhy = "the cell " + Jobs.fmt(c) + " is taken by " + taken;
            LOG.info("[entropybot] shelter: {} - skipped for now", ps.skipWhy);
            ps.idx++;
            return false;
        }
        String r = Clearing.placeAt(p, ps.block, c[0], c[1], c[2], ps.leases);
        if (r.startsWith("error")) {
            if (now - ps.stageAt > 200) ps.note = "error: couldn't build my shelter at " + Jobs.fmt(c) + ": " + ShelterPlan.placeWhy(r);
        }
        return false;
    }

    /** 0.27.4: ticks between the passes over the skipped cells (3 s), and the passes after the first (3). */
    static final long SKIP_TICKS = 60;
    static final int SKIP_ROUNDS = 3;

    /**
     * 0.27.4: after the other cells, the skipped ones again (a pause first). Still taken after SKIP_ROUNDS passes: the
     * shelter fails and names the cell and what is in it.
     */
    private static boolean retrySkipped(State ps, ClientLevel lv, LocalPlayer p, long now) {
        if (ps.skipAt < 0) ps.skipAt = now;
        if (now - ps.skipAt < SKIP_TICKS) return false;
        ps.skipAt = -1;
        if (ps.skipRounds >= SKIP_ROUNDS) {
            int[] first = ps.skipped.get(0);
            String taken = entityIn(lv, p, first);
            if (taken != null) {
                ps.note = "error: couldn't build my shelter at " + Jobs.fmt(first) + ": the cell " + Jobs.fmt(first) + " is taken by " + taken
                        + " (still there after " + SKIP_ROUNDS + " tries)";
                return false;
            }
            // free now: the last pass places it (a refusal there names the guard or the face)
        }
        ps.skipRounds++;
        LOG.info("[entropybot] shelter: retrying {} skipped cell(s), round {}/{}", ps.skipped.size(), ps.skipRounds, SKIP_ROUNDS);
        ps.todo = new ArrayList<>(ps.skipped);
        ps.skipped = new ArrayList<>();
        ps.idx = 0;
        return false;
    }

    /**
     * 0.27.4: the kind of a living entity (other than the bot and item drops) whose box lies in cell c, or null. Vanilla
     * client: Level.getEntities(Entity, AABB) on the ClientLevel, the registry key of its EntityType (BuiltInRegistries).
     */
    static String entityIn(ClientLevel lv, LocalPlayer p, int[] c) {
        try {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1);
            for (net.minecraft.world.entity.Entity e : lv.getEntities(p, box)) {
                if (!e.isAlive() || e.isSpectator() || e instanceof net.minecraft.world.entity.item.ItemEntity
                        || e instanceof net.minecraft.world.entity.ExperienceOrb) continue;
                return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            }
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] shelter: entity check at {}: {}", Jobs.fmt(c), ex.toString());
        }
        return null;
    }


    /** The shell's blocks in the restore ledger as "built: shelter" (never re-placed, never counted as a stranger's build). */
    private static void noteBuilt(State ps, ClientLevel lv) {
        List<int[]> cells = new ArrayList<>();
        for (String k : ps.own) {
            String[] w = k.split(" ");
            cells.add(new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
        }
        RestoreLive.INSTANCE.noteBuilt(cells, ps.block, "built: shelter");
        // and in commands.json "shelterBlocks" (the ledger forgets a cell once it is full again): the next shelter here may open them
        try {
            Commands c = Core0.commands();
            if (c != null) {
                // 0.27.3 (d4): only the current shelter's cells (the old list kept every shelter's forever)
                saveOwn(c, ps.own);
                // 0.27.4: every cell ever built stays "own" (a newer shelter elsewhere must not make an old pit's walls strangers')
                noteEver(c, ps.own);
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: saving my blocks failed: {}", e.toString());
        }
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
        boolean ownCell = ShelterPlan.mayBreak(c, ps.own);
        if (!ownCell && !ps.dig.contains(ShelterPlan.key(c))) {
            ps.note = "error: the block at " + Jobs.fmt(c) + " isn't mine - I only open my own shelter";
            return false;
        }
        if (now - ps.stageAt > BREAK_TICKS) {
            ps.note = "error: my shelter wall at " + Jobs.fmt(c) + " didn't break in 40 s (no tool for it?)";
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (ps.hit == null) {
            holdTool(p, ownCell ? ps.block : "minecraft:stone");      // 0.27.3: a natural cell: the pickaxe
            // the own-block permission: a one-cell force lease (the guard refuses built blocks otherwise)
            String le = ps.leases.take("opening my shelter at " + Jobs.fmt(c), new ClearBox(c[0], c[1], c[2], c[0], c[1], c[2]), false, ownCell);      // own: force; natural: a plain lease
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

    /** 0.27.2: own shelter cells a surface dig-out opened: commands.json "shelterDoors" (the last 50), like shelter leave's way out. */
    static void noteDoor(Commands c, List<int[]> cells) {
        try {
            com.google.gson.JsonObject d = c.brainData();
            Set<String> all = new java.util.LinkedHashSet<>();
            if (d.has("shelterDoors") && d.get("shelterDoors").isJsonArray()) for (var e : d.getAsJsonArray("shelterDoors")) all.add(e.getAsString());
            for (int[] x : cells) all.add(ShelterPlan.key(x));
            List<String> l = new ArrayList<>(all);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String k : l.subList(Math.max(0, l.size() - 50), l.size())) a.add(k);
            d.add("shelterDoors", a);
            c.saved();
            LOG.info("[entropybot] shelter: opened my own wall as a door at {}", String.join("; ", l.subList(Math.max(0, l.size() - cells.size()), l.size())));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: saving the door failed: {}", e.toString());
        }
    }

    /** 0.27.2: the own cells the bot knows: commands.json shelterBlocks plus the ledger's built cells of the shell around f. */
    static Set<String> knownOwn(Commands c, int[] f) {
        Set<String> own = new HashSet<>();
        Set<String> stored = ownBlocks(c);
        for (int[] cell : ShelterPlan.allCells(f))
            if (stored.contains(ShelterPlan.key(cell)) || RestoreLive.INSTANCE.builtByMe(cell[0], cell[1], cell[2])) own.add(ShelterPlan.key(cell));
        return own;
    }

    /**
     * 0.27.2: a shelter of its own around the bot that no job knows (the state was only in memory: a restart lost it).
     * The re-armed state ("in it, timer expired"), or null when the bot is not inside one.
     */
    static State recognise(Commands c, LocalPlayer p) {
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv == null || p == null) return null;
        int[] f = Jobs.here(p);
        Set<String> own = knownOwn(c, f);
        // 0.27.3 (d3): a closed shell with at least one own cell (a pit with my roof and one wall) counts too
        if (!io.github.mojolowjo.entropybot.move.SurfaceSeal.inOwnShelterLoose(f, own, x -> solid(lv, x))) return null;
        State s = new State();
        s.feet = f;
        s.own.addAll(own);
        s.stage = ShelterPlan.Stage.WAIT;
        s.timer = 1;                 // expired
        s.torchDone = true;
        for (int[] cell : ShelterPlan.allCells(f)) if (!own.contains(ShelterPlan.key(cell)) && mayDig(c, lv, f, cell)) s.dig.add(ShelterPlan.key(cell));
        int[] any = null;
        for (int[] cell : ShelterPlan.allCells(f)) if (own.contains(ShelterPlan.key(cell))) { any = cell; break; }
        s.block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(new BlockPos(any[0], any[1], any[2])).getBlock()).toString();
        s.recovered = true;
        return s;
    }

    /** 0.27.3: commands.json "shelterBlocks" = exactly these cells (the current shelter's). */
    static void saveOwn(Commands c, java.util.Collection<String> own) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String k : ShelterPlan.keepOwn(own, null)) a.add(k);
        c.brainData().add("shelterBlocks", a);
        c.saved();
    }

    /** 0.27.4: adds cells to commands.json "shelterEver" (never pruned by a new shelter; the newest 400 kept). */
    static void noteEver(Commands c, java.util.Collection<String> cells) {
        try {
            com.google.gson.JsonObject d = c.brainData();
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (d.has("shelterEver") && d.get("shelterEver").isJsonArray()) for (var e : d.getAsJsonArray("shelterEver")) all.add(e.getAsString());
            all.addAll(cells);
            List<String> l = new ArrayList<>(all);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String k : l.subList(Math.max(0, l.size() - 400), l.size())) a.add(k);
            d.add("shelterEver", a);
            c.saved();
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: saving my ever-built blocks failed: {}", e.toString());
        }
    }

    /** 0.27.3 (d4): on leaving, drop the cells that are no longer a block (opened as the door, broken by someone). */
    static void pruneOwn(State ps, ClientLevel lv) {
        try {
            Commands c = Core0.commands();
            if (c == null || lv == null) return;
            java.util.List<String> kept = ShelterPlan.keepOwn(ownBlocks(c), k -> {
                String[] w = k.split(" ");
                return solid(lv, new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
            });
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String k : kept) a.add(k);
            c.brainData().add("shelterBlocks", a);
            c.saved();
            LOG.info("[entropybot] shelter: {} of my shelter blocks still stand", kept.size());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: pruning my blocks failed: {}", e.toString());
        }
    }

    /** 0.27.3 (d3): a natural shell cell the dig rules allow: underground (no sky over the head) inside the areas, natural, no container, no fluid next to it. */
    static boolean mayDig(Commands c, ClientLevel lv, int[] f, int[] cell) {
        try {
            BlockPos bp = new BlockPos(cell[0], cell[1], cell[2]);
            if (lv.canSeeSky(new BlockPos(f[0], f[1] + 1, f[2])) || !c.inAreas(Storage.dim(), cell[0], cell[2])) return false;
            var st = lv.getBlockState(bp);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            return io.github.mojolowjo.entropybot.restore.RestoreRules.natural(id) && !st.hasBlockEntity() && st.getFluidState().isEmpty()
                    && io.github.mojolowjo.entropybot.guard.Guard.liquidNextTo(lv, cell[0], cell[1], cell[2]) == null;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: dig check at {}: {}", Jobs.fmt(cell), e.toString());
            return false;
        }
    }

    /**
     * 0.27.4: every cell the bot built for a shelter, "x y z": commands.json "shelterBlocks" (the current shelter) plus
     * "shelterEver" (all of them, newest 400). The own-block tests use this union.
     */
    static Set<String> ownBlocks(Commands c) {
        Set<String> out = new HashSet<>();
        try {
            com.google.gson.JsonObject d = c.brainData();
            if (d.has("shelterBlocks") && d.get("shelterBlocks").isJsonArray()) for (var e : d.getAsJsonArray("shelterBlocks")) out.add(e.getAsString());
            if (d.has("shelterEver") && d.get("shelterEver").isJsonArray()) for (var e : d.getAsJsonArray("shelterEver")) out.add(e.getAsString());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] shelter: reading my blocks failed: {}", e.toString());
        }
        return out;
    }

    /** The core tick (a tiny indirection so the class reads without Core's import noise). */
    static final class Core0 {
        static long tick() { return io.github.mojolowjo.entropybot.Core.INSTANCE.tick(); }

        static Commands commands() { return RestoreLive.INSTANCE.commands(); }
    }
}
