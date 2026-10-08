package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.clear.Veins;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.strip.MineBook;
import io.github.mojolowjo.entropybot.strip.MineGeom;
import io.github.mojolowjo.entropybot.strip.OreSpec;
import io.github.mojolowjo.entropybot.strip.RunNotes;
import io.github.mojolowjo.entropybot.strip.StripPlan;
import io.github.mojolowjo.entropybot.strip.StripRules;
import io.github.mojolowjo.entropybot.strip.StripTexts;
import io.github.mojolowjo.entropybot.strip.StripWorld;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7d D2: Seq hands every step whose type starts with "strip" here (the bridge's strip-mine seq steps: the walk legs,
 * setupplan, minesetup, the dig hand-offs with stripRecover, oredig, the hole fills, basedeposit, minedone, stripnext,
 * toolget/toolcheck, stripback, tphome, turnnote). Every step carries a {@link Data} in {@code Step.state}; all steps of
 * one errand share its {@link Run}. A dig is a D1 "clear" step ({@link Clearing#clearStep}) followed by a "stripdug" step
 * that reads its {@link Clearing.Outcome}: the corridor's "has to end up open" check is done there (corridorBlock), so
 * the clear itself never ends the errand for a blocked corridor and the run can help itself (a pickaxe, home and back,
 * a skipped branch, a turn).
 */
final class StripSteps {
    private static final Logger LOG = LogUtils.getLogger();

    private StripSteps() {}

    /** What one strip-mine errand shares across its steps (and runs, for "mine strip"). */
    static final class Run {
        final String mineName;
        final OreSpec spec;
        final int target;
        /** the mine this run digs ("x y z dir"): its note gets the progress even if another mine was looked at meanwhile */
        String key;
        int runs = 1;
        long deadline = Long.MAX_VALUE;
        /** ore block id -> mined (the clears' Outcome.mined), for "mine strip <ores> n" */
        final Map<String, Integer> tally = new LinkedHashMap<>();
        final RunNotes notes = new RunNotes();
        /** stripRecover's tries: "kind k part" -> n */
        final Map<String, Integer> tries = new HashMap<>();
        /** an optional craft that couldn't start (toolcheck's reason) */
        String optFail;
        /** the base trips' items: Seq.putMoved when the current trip began (-1: none running), and the total */
        int putBefore = -1, basePut;

        Run(String mineName, OreSpec spec, int target, String key) {
            this.mineName = mineName;
            this.spec = spec;
            this.target = target;
            this.key = key;
        }

        /** This run's mine note (selected again if "stripmine status" looked at another mine meanwhile). */
        JsonObject note(StripMine sm) {
            return key != null ? sm.book().select(key) : sm.book().current();
        }
    }

    /** One step's notes; which fields count depends on the step type. */
    static final class Data {
        final Run run;
        MineGeom.Leg leg;
        long legStart = -1;
        int legTries;
        boolean tpDone;
        int k;
        String part;
        ClearJob.Options opts;
        boolean dumpMine;
        Seq.Step clear;
        Map<String, Integer> before;
        List<ClearBox> boxes;
        String text, unlessId;
        int unlessN;
        boolean optional;
        String need, why, how;
        List<Pos> chests;
        Pos table, pos;
        int length;

        Data(Run run) { this.run = run; }

        /** A fresh copy of a strip dig (stripRecover's "again"). */
        Data digCopy() {
            Data d = new Data(run);
            d.k = k;
            d.part = part;
            d.opts = opts;
            d.dumpMine = dumpMine;
            return d;
        }
    }

    static Seq.Step make(String type, Data d) {
        Seq.Step s = new Seq.Step(type);
        s.state = d;
        return s;
    }

    /** The plan's items as Seq steps. */
    static List<Seq.Step> toSteps(List<StripPlan.Item> items, Run run) {
        List<Seq.Step> out = new ArrayList<>();
        for (StripPlan.Item it : items) {
            Data d = new Data(run);
            switch (it.type) {
                case "leg" -> {
                    d.leg = it.leg;
                    out.add(make("stripleg", d));
                }
                case "setupplan" -> out.add(make("stripsetup", d));
                case "craft" -> {
                    d.text = it.text;
                    d.unlessId = it.unlessId;
                    d.unlessN = it.unlessN;
                    d.optional = it.optional;
                    out.add(make("stripcraft", d));
                }
                case "dig" -> {
                    d.k = it.k;
                    d.part = it.part;
                    d.opts = it.opts;
                    d.dumpMine = it.dumpMine;
                    out.add(make("stripdig", d));
                }
                case "oredig" -> {
                    d.k = it.k;
                    d.boxes = it.boxes;
                    d.dumpMine = it.dumpMine;
                    out.add(make("stripore", d));
                }
                case "basedeposit" -> out.add(make("stripbase", d));
                case "minedone" -> {
                    d.k = it.k;
                    out.add(make("stripdone", d));
                }
                case "next" -> {
                    d.length = it.length;
                    out.add(make("stripnext", d));
                }
                case "place" -> out.add(Clearing.placeStep(new int[]{it.pos.x(), it.pos.y(), it.pos.z()}, it.item));
                case "setupdone" -> {
                    d.chests = it.chests;
                    d.table = it.table;
                    out.add(make("stripsetdone", d));
                }
                default -> throw new IllegalArgumentException("unknown strip plan item " + it.type);
            }
        }
        return out;
    }

    static List<Seq.Step> legSteps(List<MineGeom.Leg> legs, Run run) {
        List<Seq.Step> out = new ArrayList<>();
        for (MineGeom.Leg l : legs) {
            Data d = new Data(run);
            d.leg = l;
            out.add(make("stripleg", d));
        }
        return out;
    }

    // ---- the steps ----

    static String step(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        if (!(st.state instanceof Data d)) return "the " + st.type + " step has lost its notes";
        StripMine sm = StripMine.get();
        return switch (st.type) {
            case "stripleg" -> legStep(seq, d, p, elapsed);
            case "stripsetup" -> setupStep(seq, sm, d, p);
            case "stripsetdone" -> {
                JsonObject cur = d.run.note(sm);
                if (cur == null) yield "the mine's notes are gone";
                MineBook.setSetup(cur, d.chests, d.table);
                sm.saved();
                yield "next";
            }
            case "stripcraft" -> craftStep(seq, sm, d, p);
            case "stripdig" -> digStep(seq, sm, d, p);
            case "stripdug" -> dugStep(seq, sm, d, p);
            case "stripore" -> oreStep(seq, sm, d, p);
            case "stripfill" -> {
                // the hole is filled only when it was mined (still open) and there is cobblestone to fill it with
                StripWorld w = sm.world(p);
                if (w.replaceable(d.pos.x(), d.pos.y(), d.pos.z()) && Gui.inventory(p).getOrDefault(Veins.FILL_ITEM, 0) > 0) {
                    Seq.Step put = Clearing.placeStep(new int[]{d.pos.x(), d.pos.y(), d.pos.z()}, Veins.FILL_ITEM);
                    put.optional = true;
                    seq.splice(seq.idx + 1, List.of(put));
                }
                yield "next";
            }
            case "stripbase" -> baseStep(seq, sm, d, p);
            case "stripdone" -> {
                JsonObject cur = d.run.note(sm);
                if (cur != null) MineBook.setK(cur, d.k + 1);
                sm.saved();
                Run r = d.run;
                if (r.putBefore >= 0) {
                    r.basePut += Math.max(0, seq.putMoved - r.putBefore);
                    r.putBefore = -1;
                }
                seq.note = r.notes.doneNote(d.k, r.basePut);
                yield "next";
            }
            case "stripnext" -> nextStep(seq, sm, d, p);
            case "striptool" -> toolStep(seq, sm, d, p);
            case "striptoolcheck" -> {
                if (bestPickTier(p) >= StripRules.pickTier(d.need)) {
                    d.run.notes.toolNote = StripTexts.toolNote(d.how, d.need, d.why);
                    LOG.info("[entropybot] strip mine: {}", d.run.notes.toolNote);
                    yield "next";
                }
                yield StripTexts.noTool(d.why, d.need, d.run.optFail);
            }
            case "stripback" -> {
                // back to the corridor end after a trip (a pickaxe, /home): the legs, when it is far
                MineGeom g = MineBook.geom(sm.place(d.run.mineName));
                if (g != null) seq.splice(seq.idx + 1, legSteps(g.legs(d.k, Jobs.here(p)), d.run));
                yield "next";
            }
            case "striphome" -> homeStep(seq, sm, p);
            case "stripturned" -> {
                seq.note = d.text;
                d.run.notes.turned = true;
                yield "next";
            }
            case "stripput" -> {
                // S1: the base trip after a turn: its items counted, and said in the turn's note
                Run r = d.run;
                int before = r.basePut;
                if (r.putBefore >= 0) {
                    r.basePut += Math.max(0, seq.putMoved - r.putBefore);
                    r.putBefore = -1;
                }
                if (r.basePut > before) seq.note = StripTexts.withBasePut(seq.note, r.basePut);
                yield "next";
            }
            default -> "unknown step " + st.type;
        };
    }

    /** A leg of the walk to the mine (the bridge's walk step with within/tries/why, and legFailed). */
    private static String legStep(Seq seq, Data d, LocalPlayer p, long elapsed) {
        Jobs.Job j = seq.job();
        Pos pos = d.leg.pos();
        int[] target = {pos.x(), pos.y(), pos.z()};
        String fmt = pos.key();
        IBaritone b = Jobs.baritone();
        if (seq.stage == null) {
            String fence = seq.jobs.goalAllowed(pos.x(), pos.y(), pos.z());
            if (fence != null) return Jobs.withAreaHint(fence + " (" + fmt + ")");
            // S1: a corridor that runs over lava, water or a deep hole can't be walked: say so before trying
            String bad = badCorridor(d.run, p, pos, false);
            if (bad != null) return bad;
            // a long way back near home: /home first (once per step), as every seq walk does
            if (!d.tpDone && seq.jobs.tpWorth(p, target, null)) {
                d.tpDone = true;
                if (b != null) Jobs.cancel(b);
                seq.jobs.sendHome(p, j);
                seq.stage = "tp";
                seq.setStatus(seq.label + " - teleporting home");
                return "wait";
            }
            if (b == null) return "baritone not loaded";
            Goal goal = new GoalNear(new BlockPos(pos.x(), pos.y(), pos.z()), 2);
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(goal);
            j.goalObj = goal;
            j.goal = null;
            j.dest = target;
            j.startTick = seq.now();
            j.lastStepTick = -1;
            j.unstickTries = 0;
            seq.jobs.watch(j);
            seq.stage = "walking";
            d.legStart = seq.stepStart;
            seq.setStatus(seq.label + " - walking to " + d.leg.why());
            return "wait";
        }
        if (seq.stage.equals("tp")) {
            String r = seq.jobs.tpResult(p, j);
            if (r == null) return "wait";
            j.tpTick = -1;
            seq.tpNote = r.equals("ok") ? "teleported home" : "/home didn't move me, so I walked";
            seq.stage = null;
            seq.stepStart = seq.now();
            return "wait";
        }
        // a reflex held the job (Seq.afterHold moved the step's clock): plan the walk again, not a try
        if (seq.stepStart != d.legStart) {
            seq.stage = null;
            return "wait";
        }
        if (elapsed < 20) return "wait";
        // S1: the seq walk's unstick (stuck on a block Baritone can't plan from, no path, no progress for 30 s)
        if (seq.now() - j.startTick >= 40 && elapsed % 20 < 2) {
            String ev = seq.jobs.travelEvents(j);
            boolean stuck = ev == null && seq.jobs.travelStuck(p, j);
            boolean early = ev == null && j.lastStepTick >= 0 && !Jobs.onFullBlock(p) && seq.now() - j.bestTick > Jobs.UNSTICK_EARLY;
            if ((stuck || early || "nopath".equals(ev)) && j.unstickTries < Jobs.UNSTICK_TRIES && seq.jobs.startUnstick(p, j)) return "wait";
            if (stuck && b != null) Jobs.cancel(b);
        }
        if (b != null && !Jobs.idle(b)) {
            if (elapsed > 20 * 90) {
                Jobs.cancel(b);
                return legFailed(seq, d, p);
            }
            return "wait";
        }
        // a leg of a long walk has to end where it was meant to (Baritone may give up half way)
        int within = d.leg.within();
        if (Jobs.distSq(Jobs.here(p), target) > (long) within * within) return legFailed(seq, d, p);
        return "next";
    }

    private static String legFailed(Seq seq, Data d, LocalPlayer p) {
        d.legTries++;
        if (d.legTries < d.leg.tries()) {
            seq.stage = null;
            seq.stepStart = seq.now();
            return "wait";
        }
        // S1: what lies ahead may be known now that the bot got this far (lava, water, a deep hole)
        String bad = badCorridor(d.run, p, d.leg.pos(), true);
        if (bad != null) return bad;
        return "couldn't reach the mine: stuck at " + Jobs.fmt(Jobs.here(p)) + " on the way to " + d.leg.why();
    }

    /**
     * S1: the first corridor cell between the bot and pos (a corridor cell) it can't walk, as the run's end text; null
     * when none is known. Before a walk only lava counts (water can be swum, a hole may have a way round); after the walk
     * failed, water and a deep hole are named too.
     */
    static String badCorridor(Run run, LocalPlayer p, Pos pos, boolean failed) {
        StripMine sm = StripMine.get();
        MineGeom g = MineBook.geom(sm.place(run.mineName));
        if (g == null) return null;
        int to = g.indexOf(pos);
        if (to < 0 || Math.abs(g.side(new int[]{pos.x(), pos.y(), pos.z()})) > 0) return null;   // not on this mine's corridor (turned meanwhile)
        StripRules.BadFloor bad = StripRules.badFloor(sm.world(p), g, StripRules.scanFrom(g, Jobs.here(p), to), to, !failed);
        if (bad == null) return null;
        LOG.info("[entropybot] strip mine: the corridor can't be walked: {} at {} (cell {})", bad.what(), bad.at().key(), bad.i());
        return StripTexts.badFloorText(g, bad);
    }

    private static String setupStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        MineGeom g = MineBook.geom(sm.place(d.run.mineName));
        if (g == null) return "the mine is gone from my places";
        StripRules.Setup r = StripRules.setupPlan(sm.world(p), g);
        if (r.err() != null) return r.err();
        if (r.note() != null) d.run.notes.setupNote = r.note();
        seq.splice(seq.idx + 1, toSteps(r.items(), d.run));
        return "next";
    }

    /** craftitem with "unless" (the bag already has enough) and "optional" (a craft that can't start is skipped, and noted). */
    private static String craftStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        if (d.unlessId != null && Gui.inventory(p).getOrDefault(d.unlessId, 0) >= d.unlessN) return "next";
        Crafting.Prepared r = sm.crafting().prepareCraft(p, d.text, null);
        if (r.err() != null) {
            if (d.optional) {
                d.run.optFail = r.err();
                return "next";
            }
            return r.err().replaceFirst("^error: ", "");
        }
        List<Seq.Step> add = new ArrayList<>(r.steps());
        for (Seq.Step a : add) a.direct = false;
        seq.splice(seq.idx + 1, add);
        return "next";
    }

    static ClearJob.Options copy(ClearJob.Options o) {
        ClearJob.Options c = new ClearJob.Options();
        c.box = o.box;
        c.only = o.only;
        c.label = o.label;
        c.keepOres = o.keepOres;
        c.collect = o.collect;
        c.soft = o.soft;
        c.mustFinish = o.mustFinish;
        c.force = o.force;
        c.minStandY = o.minStandY;
        c.torches = o.torches == null ? null : new ArrayList<>(o.torches);
        c.dump = o.dump;
        return c;
    }

    /** A strip dig: the guard's area check first (a box past the area's edge turns the corridor / skips the branch), then the clear and its "stripdug". */
    private static String digStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        ClearJob.Options o = copy(d.opts);
        if (d.dumpMine) {
            // the strip mine's dumps go into the mine's chests (known once it is set up)
            List<Pos> ch = MineBook.chests(d.run.note(sm));
            o.dump = ch.isEmpty() ? null : ch;
        }
        if (d.part != null) {
            String refused = sm.areaRefusal(p, o.box, o.torches != null && !o.torches.isEmpty());
            if (refused != null) {
                ClearBox gb = o.box;
                String msg = "stopped: blocked:area at " + gb.x1() + " " + gb.y1() + " " + gb.z1() + " (" + refused + ")";
                return recover(seq, sm, d, StripRules.kindOf(msg), StripRules.whyOf(msg), null, p);
            }
        }
        Seq.Step c = Clearing.clearStep(o);
        Data after = d.digCopy();
        after.clear = c;
        after.before = o.collect ? Gui.inventory(p) : null;
        seq.splice(seq.idx + 1, List.of(c, make("stripdug", after)));
        return "next";
    }

    /** After a strip dig: the totals, then what the result means for the run. */
    private static String dugStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        Clearing.Outcome o = d.clear != null ? d.clear.cleared : null;
        String msg = o != null ? o.message() : null;
        boolean ok = msg != null ? msg.startsWith("ok") : (o == null || o.ok());
        if (msg == null) msg = ok ? "ok" : "stopped";
        Run run = d.run;
        run.notes.addClear(msg);
        if (o != null && o.mined() != null) o.mined().forEach((k, v) -> run.tally.merge(k, v, Integer::sum));
        if (d.before != null) run.notes.addGains(d.before, Gui.inventory(p));
        // the corridor has to go all the way, or every later branch is out of reach (the bridge's mustFinish)
        String report = msg;
        StripRules.Verdict v = StripRules.judge(d.part, report,
                () -> StripRules.corridorBlock(sm.world(p), d.opts.box, Jobs.here(p), report, bestPickTier(p)));
        if (v.fail() != null) return v.fail();
        if (v.kind() == null) return "next";
        return recover(seq, sm, d, v.kind(), v.why(), v.need(), p);
    }

    private static final Pattern NEEDS = Pattern.compile(" needs (\\w+)");

    /**
     * stripRecover: blocked:tool = fetch or make the pickaxe, walk back, dig again (once); stuck = /home, walk back in
     * legs, dig again (the corridor twice, a branch once), a branch stuck again is noted bad and skipped; a refused or
     * blocked branch is skipped; a corridor that can't go on turns the mine and ends the run. "next" or the run's failure.
     */
    private static String recover(Seq seq, StripMine sm, Data d, String kind, String why, String need, LocalPlayer p) {
        Run run = d.run;
        int n = run.tries.merge(kind + " " + d.k + " " + d.part, 1, Integer::sum);
        StripRules.Action a = StripRules.recover(kind, d.part, n);
        switch (a) {
            case FAIL -> {
                return "the mine corridor is blocked - " + why;
            }
            case TOOL -> {
                Data t = new Data(run);
                Matcher m = NEEDS.matcher(why);
                t.need = need != null ? need : m.find() ? m.group(1) : "iron";
                t.why = why;
                Data back = new Data(run);
                back.k = d.k;
                seq.splice(seq.idx + 1, List.of(make("striptool", t), make("stripback", back), make("stripdig", d.digCopy())));
                LOG.info("[entropybot] strip mine: {} - getting the pickaxe, then digging again", why);
                return "next";
            }
            case RETRY -> {
                Data back = new Data(run);
                back.k = d.k;
                seq.splice(seq.idx + 1, List.of(make("striphome", new Data(run)), make("stripback", back), make("stripdig", d.digCopy())));
                LOG.info("[entropybot] strip mine: {} - home, walking back, digging again (try {})", why, n + 1);
                return "next";
            }
            case SKIP -> {
                // a branch stuck again (noted bad) or refused (outside my areas): the run goes on without it
                if (kind.equals("stuck")) {
                    JsonObject cur = d.run.note(sm);
                    if (cur != null) MineBook.addBad(cur, d.k + " " + d.part);
                    sm.saved();
                }
                run.notes.addSkip(StripRules.skipText(d.k, d.part, kind, why));
                return "next";
            }
            default -> {
                StripMine.TurnResult tr = sm.turn(p, run.mineName, null, why, false);
                if (tr.err() != null) return "the mine corridor is blocked - " + why + " - and " + tr.err();
                run.key = MineBook.str(sm.book().current(), "at");
                // the rest of this run belongs to the old mine: drop it (a trip to the base stays), say what happened
                List<Seq.Step> rest = new ArrayList<>(seq.steps.subList(seq.idx + 1, seq.steps.size())), keep = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (Seq.Step s : rest) types.add(s.type);
                for (int i : StripRules.keepAfterTurn(types)) keep.add(rest.get(i));
                while (seq.steps.size() > seq.idx + 1) seq.steps.remove(seq.steps.size() - 1);
                Data note = new Data(run);
                note.text = StripTexts.turnNote(why, tr.ok());
                seq.steps.add(make("stripturned", note));
                // S1 (D2 review): a base trip kept after the turn is counted ("took N items to base") by a "stripput"
                // right after it (its deposit steps are spliced in between), as "stripdone" counts it on a normal run
                for (Seq.Step k : keep) {
                    seq.steps.add(k);
                    if (k.type.equals("stripbase")) seq.steps.add(make("stripput", new Data(run)));
                }
                return "next";
            }
        }
    }

    /** oredig: the ores the branch exposed and their veins, as a soft "only" clear on the walkway, then the hole fills. */
    private static String oreStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        Run run = d.run;
        String dim = sm.dim(p);
        List<Pos> veins = Veins.findVeins(sm.world(p), d.boxes, run.spec == null ? null : id -> run.spec.match(id) >= 0, (x, z) -> sm.inArea(dim, x, z));
        if (veins.isEmpty()) return "next";
        int feetY = Veins.feetY(d.boxes);
        List<Pos> ch = d.dumpMine ? MineBook.chests(d.run.note(sm)) : List.of();
        Seq.Step c = Clearing.clearStep(Veins.oreDigOptions(veins, feetY, ch.isEmpty() ? null : ch, d.k));
        Data after = new Data(run);
        after.clear = c;
        after.before = Gui.inventory(p);
        List<Seq.Step> add = new ArrayList<>(List.of(c, make("stripdug", after)));
        for (Veins.Fill f : Veins.holeFills(d.boxes, veins, feetY)) {
            Data fd = new Data(run);
            fd.pos = f.pos();
            add.add(make("stripfill", fd));
        }
        seq.splice(seq.idx + 1, add);
        return "next";
    }

    /** basedeposit: 64+ valuables on board, or the bag nearly full: take them to the base chests (the walk teleports home). */
    private static String baseStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        Storage storage = sm.storage();
        StorageRules.Keeps keeps = storage.keeps();
        List<StorageRules.Held> held = new ArrayList<>(), slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            StorageRules.Held h = s.isEmpty() ? null : new StorageRules.Held(Gui.itemId(s), s.getCount(), Gui.isFood(s), i);
            slots.add(h);
            if (h != null) held.add(h);
        }
        Map<String, Integer> keep = StorageRules.depositables(held, "", false, StorageRules.VALUABLE, keeps);
        int room = StripRules.bagRoom(slots, StorageRules.depositables(held, "", true, null, keeps));
        if (!StripRules.baseTrip(keep, Gui.inventory(p), room)) return "next";
        Storage.DepositSteps dep = storage.depositSteps(p, "", true, StorageRules.VALUABLE);
        if (dep.err() != null) {
            d.run.notes.baseNote = "no trip to the base (" + dep.err().replaceFirst("^error: ", "") + ")";
            return "next";
        }
        d.run.notes.baseTrip = true;
        d.run.putBefore = seq.putMoved;
        seq.splice(seq.idx + 1, dep.steps());
        return "next";
    }

    /** "mine strip": another run unless the listed ores are mined, the hour is up or the runs are used up. */
    private static String nextStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        Run run = d.run;
        int got = run.spec.count(run.tally);
        String why = StripTexts.stripStop(run.target, got, seq.now() > run.deadline, run.runs);
        if (why != null) {
            seq.note = StripTexts.stripDone(run.spec, got, run.runs, why, seq.note);
            return "next";
        }
        JsonObject place = sm.place(run.mineName);
        MineGeom g = MineBook.geom(place);
        if (g == null) return "the mine " + run.mineName + " is gone from my places";
        JsonObject note = sm.book().select(g.key());
        run.key = g.key();
        sm.saved();
        StripPlan.Run plan = StripPlan.run(g, note, 1, d.length, true, b -> sm.boxInArea(sm.placeDim(place, p), b), Jobs.here(p));
        List<Seq.Step> add = toSteps(plan.items(), run);
        Data nx = new Data(run);
        nx.length = d.length;
        add.add(make("stripnext", nx));
        run.runs++;
        seq.setStatus(seq.label + " - run " + run.runs + ", " + got + (run.target != 0 ? "/" + run.target : "") + " ores so far");
        seq.splice(seq.idx + 1, add);
        return "next";
    }

    /** toolget: a pickaxe good enough for the block in the corridor, from storage (chests, the RS network), else made. */
    private static String toolStep(Seq seq, StripMine sm, Data d, LocalPlayer p) {
        int tier = StripRules.pickTier(d.need);
        if (bestPickTier(p) >= tier) return "next";
        Crafting crafting = sm.crafting();
        List<Crafting.Source> src = new ArrayList<>(crafting.storageSources(p));
        // far from the base its chests count too: the walk there teleports home first, and stripback walks back
        if (StorageRules.depositAtBase(sm.place("base"), Jobs.here(p), sm.dim(p))) {
            for (StorageRules.Chest c : sm.storage().baseChests(p, true)) src.add(new Crafting.Source(false, c.pos(), c.items()));
        }
        List<String> ids = new ArrayList<>();
        for (String id : new String[]{"minecraft:" + d.need + "_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"}) {
            if (Tools.toolTier(id) >= tier && !ids.contains(id)) ids.add(id);
        }
        String found = null;
        for (Crafting.Source s : src) {
            for (String id : ids) {
                if (found == null && s.items().getOrDefault(id, 0) > 0) found = id;
            }
            if (found != null) break;
        }
        List<Seq.Step> add = new ArrayList<>();
        if (found != null) {
            Map<String, Integer> want = new LinkedHashMap<>();
            want.put(found, 1);
            add.addAll(Crafting.takeTrips(src, want).steps());
        } else {
            Data c = new Data(d.run);
            c.text = "minecraft:" + d.need + "_pickaxe 1";
            c.optional = true;
            add.add(make("stripcraft", c));
        }
        Data check = new Data(d.run);
        check.need = d.need;
        check.why = d.why;
        check.how = found != null ? "fetched" : "made";
        add.add(make("striptoolcheck", check));
        d.run.optFail = null;
        seq.splice(seq.idx + 1, add);
        seq.setStatus(seq.label + " - getting " + StripTexts.anA(d.need) + " pickaxe (" + d.why + ")");
        return "next";
    }

    /** tphome: the way out of a spot it is stuck in (stripback walks back in legs after it). */
    private static String homeStep(Seq seq, StripMine sm, LocalPlayer p) {
        Jobs.Job j = seq.job();
        if (seq.stage == null) {
            if (sm.commands().home() == null) return "next";
            if (!io.github.mojolowjo.entropybot.move.ServerCmds.allowed("the strip mine's way out by /home")) return "next";
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
            seq.jobs.sendHome(p, j);
            seq.stage = "tp";
            seq.setStatus(seq.label + " - stuck, teleporting home to walk back");
            return "wait";
        }
        if (seq.jobs.tpResult(p, j) == null) return "wait";
        j.tpTick = -1;
        return "next";
    }

    /** The best pickaxe tier in the bag (stone 2, copper 3, iron 4, diamond 5), 0 without one. */
    static int bestPickTier(LocalPlayer p) {
        int best = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = Gui.itemId(s);
            if (Tools.isPickaxe(id)) best = Math.max(best, Tools.toolTier(id));
        }
        return best;
    }
}
