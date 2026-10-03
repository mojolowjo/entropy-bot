package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A job made of steps (B7b part 2, the bridge's startSeq / stepSeq / seqStep): walk, open, ops, note, close, put,
 * loot, and the scan, pots and Refined Storage steps. Stepped every 2 ticks like the bridge's; a walk step has the
 * part-1 walk's teleport home, path events, stuck watchdog and unsticking. A failed step ends the job with
 * "error: <why> (while <label>)", the last one with "ok: done <label>; <note>".
 */
public final class Seq {
    /** One step; which fields count depends on {@link #type}. */
    public static final class Step {
        public final String type;
        public int[] pos;
        public boolean near, optional, keepNote;
        public String why, machine = "no", kind, key, to, got, op, id, expect, expectName;
        public List<Map<String, Object>> ops;
        public Map<String, Integer> items, keep;
        public int[] fallback;
        public Integer n;
        public StorageRules.Spot center;
        public int radius;

        public Step(String type) { this.type = type; }

        static Step walk(int[] pos, boolean near) {
            Step s = new Step("walk");
            s.pos = pos;
            s.near = near;
            return s;
        }

        static Step open(int[] pos, String machine) {
            Step s = new Step("open");
            s.pos = pos;
            s.machine = machine;
            return s;
        }

        static Step close() { return new Step("close"); }
    }

    final Jobs jobs;
    final Storage storage;
    final List<Step> steps;
    String label, note, tpNote, wore;
    int idx;
    long stepStart, stageTick;
    String stage;
    /** "always" = the menu closes however the job ends; "fail" = unless it ended ok (open leaves it open). */
    final String closeOnEnd;
    // ops
    Map<String, List<Integer>> roles;
    int opI;
    Map<String, Integer> opBefore;
    String opErr;
    Object[] opWant;           // {id, n, gain}
    boolean machine;
    int tpStep = -1;
    // put
    Map<String, Integer> putBefore;
    int putMoved, putLeft;
    // rs
    Map<String, Integer> rsBefore;
    int rsWant, rsQuiet, rsLast;
    Integer rsShort;
    // pots
    Map<String, Integer> potsBefore;
    String potsTo;
    int potsCount;
    // loot
    Entity corpse;
    boolean lootEmpty, pressed;
    int lootBefore;

    Seq(Jobs jobs, Storage storage, String label, List<Step> steps, String closeOnEnd) {
        this.jobs = jobs;
        this.storage = storage;
        this.label = label;
        this.steps = new ArrayList<>(steps);
        this.closeOnEnd = closeOnEnd;
    }

    long now() { return jobs.core().tick(); }

    Jobs.Job job() { return jobs.job; }

    String status() { return job().status; }

    void setStatus(String s) { job().status = s; }

    /** Every 2 ticks while the job runs. */
    void tick(LocalPlayer p) {
        if (idx >= steps.size()) {
            jobs.finish("ok: done " + label + (wore != null ? "; " + wore.replaceFirst("^ok: ", "") : "") + (note != null ? "; " + note : "")
                    + (tpNote != null ? "; " + tpNote : ""));
            return;
        }
        String r;
        try {
            r = step(steps.get(idx), p);
        } catch (RuntimeException e) {
            r = String.valueOf(e);
        }
        if (job() == null || job().done) return;
        if (r.equals("wait")) return;
        if (r.equals("next")) {
            idx++;
            stepStart = now();
            stage = null;
            return;
        }
        jobs.finish("error: " + r + " (while " + label + ")");
    }

    /** After a reflex held the job: its clocks move on; a container the fight closed is opened again. */
    void afterHold(long held, LocalPlayer p) {
        stepStart += held;
        stageTick += held;
        if (idx >= steps.size()) return;
        String t = steps.get(idx).type;
        if ((t.equals("ops") || t.equals("put") || t.equals("note") || t.equals("rsmove") || t.equals("rsread")) && !Gui.open(p)) {
            int back = idx;
            while (back > 0 && !steps.get(back).type.equals("open")) back--;
            if (back > 0 && steps.get(back - 1).type.equals("walk")) back--;
            idx = back;
            stage = null;
            stepStart = now();
        }
        if (t.equals("walk") && "walking".equals(stage)) stage = null;     // plan the walk again
    }

    void splice(int at, List<Step> add) { steps.addAll(at, add); }

    private String step(Step st, LocalPlayer p) {
        long elapsed = now() - stepStart;
        switch (st.type) {
            case "walk": return walkStep(st, p, elapsed);
            case "open": return openStep(st, p, elapsed);
            case "ops": return opsStep(st, p);
            case "note":
                note = noteFor(st.kind, p);
                return "next";
            case "close":
                if (Gui.open(p)) Gui.close(p);
                return "next";
            case "loot": return lootStep(p);
            case "put": return putStep(st, p, elapsed);
            case "scanhere": {
                // arrived: find the containers now that their chunks are loaded, and do them next
                Storage.ScanPlan sc = storage.scanSteps(st.center.pos(), st.radius, Jobs.here(p));
                if (sc.found() == 0) return "no chests or barrels within " + st.radius + " blocks of " + st.center.label();
                splice(idx + 1, sc.steps());
                label = "scanning " + Math.min(sc.found(), 30) + " containers at " + st.center.label();
                return "next";
            }
            case "potshere":
            case "potsdone": return storage.potsStep(this, st, p);
            case "potsnote":
                // the haul's report first, then what putting it away said
                note = st.got + (note != null ? "; " + note : "");
                return "next";
            case "rsread": return rsReadStep(st, p, elapsed);
            case "rsmove": return storage.rsMoveStep(this, st, p, elapsed);
            default: return "unknown step " + st.type;
        }
    }

    // ---- walk ----

    private String walkStep(Step st, LocalPlayer p, long elapsed) {
        Jobs.Job j = job();
        String fmt = Jobs.fmt(st.pos);
        IBaritone b = Jobs.baritone();
        if (stage == null) {
            // a long way back to base: /home first (once per step), then walk the rest
            if (tpStep != idx && jobs.tpWorth(p, st.pos, null)) {
                tpStep = idx;
                if (b != null) Jobs.cancel(b);
                jobs.sendHome(p, j);
                stage = "tp";
                setStatus(label + " - teleporting home");
                return "wait";
            }
            if (b == null) return "baritone not loaded";
            // the fence: the goal has to lie inside an area (ends the errand with the guard's reason)
            String why = jobs.goalAllowed(st.pos[0], st.pos[1], st.pos[2]);
            if (why != null) return Jobs.withAreaHint(why + " (" + fmt + ")");
            // near: just get close (e.g. where it died), no need to touch the block
            BlockPos bp = new BlockPos(st.pos[0], st.pos[1], st.pos[2]);
            Goal goal = st.near ? new GoalNear(bp, 2) : new GoalGetToBlock(bp);
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(goal);
            j.goalObj = goal;
            j.goal = null;
            j.dest = st.pos;
            j.startTick = now();
            j.lastStepTick = -1;
            j.unstickTries = 0;
            jobs.watch(j);
            stage = "walking";
            setStatus(label + " - walking to " + (st.why != null ? st.why : fmt));
            return "wait";
        }
        if (stage.equals("tp")) {
            String r = jobs.tpResult(p, j);
            if (r == null) return "wait";
            j.tpTick = -1;
            tpNote = r.equals("ok") ? "teleported home" : "/home didn't move me, so I walked";
            stage = null;
            stepStart = now();
            return "wait";
        }
        if (elapsed < 20 || now() - j.startTick < 40) return "wait";
        // stuck on a block Baritone can't plan from, no path, or no progress for 30 s: step off it by hand (B7b part 1)
        if (elapsed % 20 < 2) {
            String ev = jobs.travelEvents(j);
            boolean stuck = ev == null && jobs.travelStuck(p, j);
            boolean early = ev == null && j.lastStepTick >= 0 && !Jobs.onFullBlock(p) && now() - j.bestTick > Jobs.UNSTICK_EARLY;
            if ((stuck || early || "nopath".equals(ev)) && j.unstickTries < Jobs.UNSTICK_TRIES && jobs.startUnstick(p, j)) return "wait";
            if (stuck && b != null) Jobs.cancel(b);
        }
        if (b != null && !Jobs.idle(b)) {
            if (elapsed > 20 * 90) {
                Jobs.cancel(b);
                return "took too long walking to " + fmt;
            }
            return "wait";
        }
        if (st.near) return "next";
        Vec3 center = new Vec3(st.pos[0] + 0.5, st.pos[1] + 0.5, st.pos[2] + 0.5);
        if (p.getEyePosition().distanceTo(center) > 4.5) return "couldn't get close to " + fmt;
        return "next";
    }

    // ---- open ----

    private String openStep(Step st, LocalPlayer p, long elapsed) {
        Minecraft mc = Minecraft.getInstance();
        if (stage == null) {
            if (mc.screen != null || Gui.open(p)) {
                Gui.close(p);                 // something else is still open: close it first
                stage = "closing";
                return "wait";
            }
            stage = "click";
        }
        if (stage.equals("closing")) {
            if (elapsed < 6) return "wait";
            stage = "click";
        }
        if (stage.equals("click")) {
            BlockPos bp = new BlockPos(st.pos[0], st.pos[1], st.pos[2]);
            // a right-click on a block with nothing to open would place the block in the hand
            if (!mc.level.getBlockState(bp).hasBlockEntity()) {
                return "there is no container at " + Jobs.fmt(st.pos) + " (it is " + Storage.blockName(mc.level.getBlockState(bp)) + ")";
            }
            String r = storage.use(p, Jobs.fmt(st.pos));
            if (!r.startsWith("ok")) return r;
            // a machine (furnace...) is not storage: its contents are never remembered as a chest's
            machine = st.machine.equals("yes") || (st.machine.equals("auto") && !Storage.isStorageBlock(st.pos));
            if (machine) storage.lastOpened = null;
            stage = "opening";
            stageTick = now();
            return "wait";
        }
        if (Gui.open(p)) {
            if (now() - stageTick < 15) return "wait";      // let the contents arrive
            // storage that shows nothing yet may just be slow to sync: give it a little longer
            if (!machine && now() - stageTick < 50 && GuiCore.sum(GuiCore.contents(new McMenu(p), GuiCore.ALL_ROLES, null)) == 0) return "wait";
            if (!machine) storage.rememberOpen(p);
            return "next";
        }
        return elapsed > 80 ? "the container at " + Jobs.fmt(st.pos) + " did not open" : "wait";
    }

    // ---- ops ----

    /** Runs one op on the open container; why it failed, or null. The caller measures the real change GUI_SETTLE ticks later. */
    private String runOp(LocalPlayer p, Map<String, Object> op, Step st) {
        McMenu m = new McMenu(p);
        String id = op.get("id") != null ? GuiCore.normId((String) op.get("id")) : "all";
        @SuppressWarnings("unchecked")
        List<String> names = op.get("roles") != null ? (List<String>) op.get("roles") : GuiCore.TAKE_ROLES;
        String where = st.pos != null ? "the container at " + Jobs.fmt(st.pos) : "the container";
        String kind = (String) op.get("op");
        opWant = null;
        int n = op.get("n") != null ? ((Number) op.get("n")).intValue() : 0;
        if (kind.equals("take")) {
            int held = GuiCore.contents(m, names, roles).getOrDefault(id, 0);
            if (held < n) return where + " had only " + held + " of " + GuiCore.shortId(id) + " (my notes were out of date - PM scan)";
            GuiCore.Result r = GuiCore.take(m, id, n, names, roles);
            opWant = new Object[]{id, n, true};
            if (r.moved() < n) return r.stuck() ? "my inventory is full (took " + r.moved() + " of " + n + " " + GuiCore.shortId(id) + ")" : "couldn't take " + n + " " + GuiCore.shortId(id) + " from " + where;
            return null;
        }
        if (kind.equals("put") || kind.equals("topup")) {
            int need = n;
            if (kind.equals("topup")) need = ((Number) op.get("to")).intValue() - GuiCore.contents(m, names, roles).getOrDefault(id, 0);
            if (need <= 0) return null;
            int have = Gui.inventory(p).getOrDefault(id, 0);
            if (have < need) return "I carry only " + have + " of the " + need + " " + GuiCore.shortId(id) + " to put in " + where;
            GuiCore.Result r = GuiCore.put(m, id, need, names, roles);
            opWant = new Object[]{id, need, false};
            return r.moved() < need ? where + " has no room for " + (need - r.moved()) + " of the " + need + " " + GuiCore.shortId(id) : null;
        }
        if (kind.equals("collect")) {
            GuiCore.take(m, op.get("id") != null ? id : "all", GuiCore.ALL, List.of("output"), roles);
            return null;
        }
        return "unknown gui op " + kind;
    }

    /** Every op is run, then re-measured once the server has confirmed it (GUI_SETTLE ticks). */
    private String opsStep(Step st, LocalPlayer p) {
        if (stage == null) {
            if (!Gui.open(p)) return "no container open";
            String bad = Gui.wrongScreen(p);
            if (bad != null) return bad;
            if (st.expect != null && !java.util.regex.Pattern.compile(st.expect, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(Gui.menuName(p)).find()) {
                return "the block at " + Jobs.fmt(st.pos) + " opened a " + Gui.menuName(p).replaceFirst("Menu$", "") + ", not a " + (st.expectName != null ? st.expectName : st.expect);
            }
            roles = GuiCore.roles(new McMenu(p));
            opI = 0;
            stage = "op";
        }
        if (stage.equals("op")) {
            if (opI >= st.ops.size()) {
                if (!machine) storage.rememberOpen(p);
                return "next";
            }
            opBefore = Gui.inventory(p);
            opErr = runOp(p, st.ops.get(opI), st);
            stage = "settle";
            stageTick = now();
            return "wait";
        }
        if (now() - stageTick < Storage.GUI_SETTLE) return "wait";
        List<Map<String, Integer>> d = GuiCore.diff(opBefore, Gui.inventory(p));
        String r = opErr;
        if (r == null && opWant != null) {
            // the click looked right; did the server agree?
            boolean gain = (Boolean) opWant[2];
            int got = (gain ? d.get(0) : d.get(1)).getOrDefault((String) opWant[0], 0), want = (Integer) opWant[1];
            if (got < want) r = "the server only let " + (gain ? "me take " : "me put ") + got + " of " + want + " " + GuiCore.shortId((String) opWant[0]);
        }
        if (r != null && !st.optional) return r;
        opI++;
        stage = "op";
        return "wait";
    }

    private String noteFor(String kind, LocalPlayer p) {
        if ("open".equals(kind)) {
            String t = GuiCore.top(GuiCore.contents(new McMenu(p), GuiCore.ALL_ROLES, null), 3);
            return t.isEmpty() ? "empty" : t;
        }
        return "";
    }

    // ---- put: exact counts above keep; a full chest's rest goes to the fallback chest right after its close ----

    private String putStep(Step st, LocalPlayer p, long elapsed) {
        if (stage == null) {
            if (!Gui.open(p)) return "no container open";
            String bad = Gui.wrongScreen(p);
            if (bad != null) return bad;
            putBefore = Gui.inventory(p);
            McMenu m = new McMenu(p);
            roles = GuiCore.roles(m);
            for (Map.Entry<String, Integer> e : st.items.entrySet()) {
                int n = putBefore.getOrDefault(e.getKey(), 0) - e.getValue();
                if (n > 0) GuiCore.put(m, e.getKey(), n, GuiCore.PUT_ROLES, roles);
            }
            stage = "put";
            return "wait";
        }
        if (elapsed < 10) return "wait";
        Map<String, Integer> nowInv = Gui.inventory(p), rest = new LinkedHashMap<>();
        int moved = 0, left = 0;
        for (Map.Entry<String, Integer> e : st.items.entrySet()) {
            String id = e.getKey();
            moved += putBefore.getOrDefault(id, 0) - nowInv.getOrDefault(id, 0);
            // what should have gone in but didn't: the chest is full
            int n = Math.max(0, nowInv.getOrDefault(id, 0) - e.getValue());
            left += n;
            if (n > 0) rest.put(id, e.getValue());
        }
        putMoved += moved;
        storage.rememberOpen(p);
        if (!rest.isEmpty() && st.fallback != null) {
            // this chest is full: take the rest to the fallback chest, once this chest is closed
            int at = idx + 1;
            while (at < steps.size() && !steps.get(at).type.equals("close")) at++;
            Step put = new Step("put");
            put.items = rest;
            splice(Math.min(at + 1, steps.size()), List.of(Step.walk(st.fallback, false), Step.open(st.fallback, "no"), put, Step.close()));
        } else {
            putLeft += left;
        }
        note = "put away " + putMoved + " items" + (putLeft > 0 ? " (" + putLeft + " didn't fit - chests full)" : "");
        return "next";
    }

    // ---- loot: the bot's own corpse ("Transfer Items" also puts the armor back on) ----

    private String lootStep(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Entity c = corpse;
        if (stage == null) {
            c = storage.findOwnCorpse(p, 32);
            if (c == null) {
                if (lootEmpty) {
                    note = "my corpse was already empty";
                    return "next";
                }
                return "no corpse of mine within 32 blocks";
            }
            corpse = c;
            if (c.distanceTo(p) > 3) {
                IBaritone b = Jobs.baritone();
                if (b == null) return "baritone not loaded";
                Goal g = new GoalNear(BlockPos.containing(c.getX(), c.getY(), c.getZ()), 1);
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(g);
                job().goalObj = g;
                job().dest = new int[]{(int) Math.floor(c.getX()), (int) Math.floor(c.getY()), (int) Math.floor(c.getZ())};
                job().startTick = now();
                job().lastStepTick = -1;
                job().unstickTries = 0;
                jobs.watch(job());
                stage = "walking";
                stageTick = now();
                setStatus(label + " - walking to my corpse");
                return "wait";
            }
            stage = "open";
        }
        if (stage.equals("walking")) {
            long in = now() - stageTick;
            if (in < 20 || now() - job().startTick < 40) return "wait";
            IBaritone b = Jobs.baritone();
            if (in % 20 < 2) {
                Jobs.Job j = job();
                String ev = jobs.travelEvents(j);
                boolean stuck = ev == null && jobs.travelStuck(p, j);
                boolean early = ev == null && j.lastStepTick >= 0 && !Jobs.onFullBlock(p) && now() - j.bestTick > Jobs.UNSTICK_EARLY;
                if ((stuck || early || "nopath".equals(ev)) && j.unstickTries < Jobs.UNSTICK_TRIES && jobs.startUnstick(p, j)) return "wait";
                if (stuck && b != null) Jobs.cancel(b);
            }
            if (b != null && !Jobs.idle(b)) {
                if (in > 20 * 60) {
                    Jobs.cancel(b);
                    return "took too long walking to my corpse";
                }
                return "wait";
            }
            if (!c.isAlive() || c.distanceTo(p) > 4) return "couldn't get to my corpse at " + (int) Math.floor(c.getX()) + " " + (int) Math.floor(c.getY()) + " " + (int) Math.floor(c.getZ());
            stage = "open";
        }
        if (stage.equals("open")) {
            if (mc.screen != null || Gui.open(p)) Gui.close(p);
            storage.lastOpened = null;       // not a chest: don't remember its contents as one
            lootBefore = Gui.totalItems(p);
            try { p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, c.position()); } catch (RuntimeException ignored) {}
            mc.gameMode.interact(p, c, net.minecraft.world.InteractionHand.MAIN_HAND);
            stage = "opening";
            stageTick = now();
            setStatus(label + " - opening my corpse");
            return "wait";
        }
        if (stage.equals("opening")) {
            if (mc.screen == null || !Gui.screenName().contains("Corpse") || !Gui.open(p)) {
                return now() - stageTick > 60 ? "my corpse did not open" : "wait";
            }
            if (now() - stageTick < 5) return "wait";        // let the contents arrive
            Map<String, Integer> items = Gui.containerContents(p);
            if (items == null || items.isEmpty()) {
                // an old, empty corpse: look for another one
                storage.emptyCorpses.add(c.getUUID());
                Gui.close(p);
                lootEmpty = true;
                corpse = null;
                stage = null;
                return "wait";
            }
            pressed = Storage.pressButton("Transfer Items");
            if (!pressed) {
                // no button (another language?): shift-click everything out instead
                var menu = p.containerMenu;
                for (int i = 0; i < menu.slots.size(); i++) {
                    var s = menu.getSlot(i);
                    if (s.container != p.getInventory() && s.hasItem()) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
                    }
                }
            }
            stage = "taking";
            stageTick = now();
            return "wait";
        }
        if (stage.equals("taking")) {
            if (now() - stageTick < 10) return "wait";
            Gui.close(p);
            if (!pressed) Gui.wearArmor(p);
            storage.emptyCorpses.add(c.getUUID());
            note = "got " + (Gui.totalItems(p) - lootBefore) + " items back from my corpse";
            return "next";
        }
        return "unknown loot stage " + stage;
    }

    // ---- rs read ----

    private String rsReadStep(Step st, LocalPlayer p, long elapsed) {
        Map<String, Integer> items = Storage.rsGridItems(p);
        if (items == null) return elapsed > 40 ? "the block at " + st.key + " did not open a Refined Storage grid" : "wait";
        // the grid fills in once the network has synced: give an empty one a little longer
        if (items.isEmpty() && elapsed < 60) return "wait";
        storage.rsRemember(st.key, items);
        if (!st.keepNote) note = StorageRules.rsSummary(st.key, items);      // after a take/put its own report stays
        return "next";
    }
}
