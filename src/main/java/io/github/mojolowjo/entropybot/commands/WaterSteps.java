package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearEngine;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.clear.WaterPlan;
import io.github.mojolowjo.entropybot.clear.WaterScan;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

/**
 * Water plan (docs/WATER_PLAN.md, 2026-10-04): the game side of {@code dig ... water [large]}. A dig that stops "blocked
 * by water at x y z" gets a water step after its clear ({@link Clearing#finish}). The step asks {@link WaterPlan#round}
 * what to do and splices it in: [a seal placement per cell] [wait for the water to drain, after a plug] [the same clear
 * again], so the dig goes on past the water; the clear's own rule (never break a block next to water) still holds, so a
 * seal that didn't take only means another round. When it can't (lava, a cell the guard refuses or it can't reach, too
 * few blocks, a large body of water without "large", too many rounds) the dig ends blocked as before, saying why; for a
 * large body it asks the requester to confirm ({@link ConfirmGate#offer}), which runs the dig again with "large".
 * A floor dig's fill follows the last clear. Only the decisions are game-free; this class only wires them.
 */
final class WaterSteps {
    private static final Logger LOG = LogUtils.getLogger();

    private WaterSteps() {}

    static final String KIND_WATER = "water", KIND_DRAIN = "waterdrain";

    /** The clear ended blocked by water (not lava) and its dig asked for "water". */
    static boolean wants(Seq.Step st, String msg) {
        return st.clear != null && st.clear.water && msg != null && msg.startsWith(ClearEngine.BLOCKED_BY + "water")
                && !Clearing.KIND_TABLE.equals(st.kind);
    }

    /** The clear's own end message with the water totals (KIND_FINISH: the blocks of every round). */
    static String endText(Seq.Step st, ClearJob job, String msg) {
        if (st.clear == null || !st.clear.water) return msg;
        return WaterPlan.endMessage(msg, st.clear.waterTally, job.broken, Clearing.KIND_FINISH.equals(st.kind));
    }

    static final class WaterState {
        final Seq.Step clear;
        final ClearJob job;
        boolean done;

        WaterState(Seq.Step clear, ClearJob job) {
            this.clear = clear;
            this.job = job;
        }
    }

    /** The water step after the blocked clear {@code clear} (its job knows which blocks it left by the water). */
    static Seq.Step step(Seq.Step clear, ClearJob job) {
        Seq.Step s = new Seq.Step("clear");
        s.kind = KIND_WATER;
        s.clear = clear.clear;
        s.state = new WaterState(clear, job);
        return s;
    }

    static final class DrainState {
        final Pos at;
        final ClearBox box;
        long start = -1;

        DrainState(Pos at, ClearBox box) {
            this.at = at;
            this.box = box;
        }
    }

    static String waterRun(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof WaterState ws)) return "the water step was not set up";
        if (ws.done) return "next";
        ws.done = true;
        Commands c = seq.jobs.core().commands;
        ClearJob.Options o = st.clear;
        WaterPlan.Tally t = o.waterTally != null ? o.waterTally : (o.waterTally = new WaterPlan.Tally());
        Clearing.Outcome prev = ws.clear.cleared;
        String msg = prev != null ? prev.message() : "blocked by water (while " + seq.label + ")";
        int broken = prev != null ? prev.broken() : 0;
        String stop = WaterPlan.giveUp(t, broken);
        if (stop != null) return end(seq, ws, prev, WaterPlan.blockedMessage(msg, "the water", stop, null), broken);
        Clearing.LiveWorld w = new Clearing.LiveWorld();
        w.set(p);
        Pos at = ClearEngine.liquidBlock(w, ws.job);
        ClearBox box = ws.job.box;
        WaterPlan.Round r;
        if (at == null) {
            // the water is gone since (it drained, or someone sealed it): just dig on
            r = new WaterPlan.Round(null, WaterScan.Kind.NONE, List.of(), false, 0, 0, "no water there any more");
        } else {
            String dim = Minecraft.getInstance().level != null ? Storage.dim() : "minecraft:overworld";
            GuardCore g = Core.INSTANCE.guard.core;
            r = WaterPlan.round(new WaterPlan.Inputs(w, box, McClearWorld.botOf(p), at, o.waterLarge, Clearing.standCheck(c),
                    cell -> !GuardCore.DENIED_DIMS.contains(dim) && g.policy().protectAt(dim, cell.x(), cell.y(), cell.z()) == null,
                    Gui.inventory(p)));
        }
        String to = requester(seq, c);
        if (r.blocked() != null) {
            String question = null;
            if (WaterPlan.LARGE_ASK.equals(r.blocked()) && o.line != null) {
                question = c.confirmGate.offer(to, WaterPlan.largeLine(o.line),
                        "seal " + r.what() + " at " + (at != null ? at.key() : "?") + " and dig on (" + WaterPlan.largeLine(o.line) + ")",
                        System.currentTimeMillis());
            }
            LOG.info("[entropybot] water: {} - {}", r.what(), r.blocked());
            return end(seq, ws, prev, WaterPlan.blockedMessage(msg, r.what(), r.blocked(), question), broken);
        }
        t.add(r, null, box);
        t.broken += broken;
        List<Seq.Step> steps = new ArrayList<>();
        for (WaterPlan.Placement pl : r.placements()) {
            Pos cell = pl.cell(), f = pl.from();
            // optional: a seal that can't be done is skipped; the clear still never breaks next to water, so the next
            // round sees what is left
            Seq.Step ps = Clearing.placeStep(new int[]{cell.x(), cell.y(), cell.z()}, pl.block(),
                    f != null ? new int[]{f.x(), f.y(), f.z()} : null, true);
            ((Clearing.PlaceState) ps.state).seal = box;
            steps.add(ps);
        }
        if (r.drain() && at != null) {
            Seq.Step d = new Seq.Step("clear");
            d.kind = KIND_DRAIN;
            d.state = new DrainState(at, box);
            steps.add(d);
        }
        Seq.Step again = Clearing.KIND_FINISH.equals(ws.clear.kind) ? Clearing.finishingClearStep(o) : Clearing.clearStep(o);
        steps.add(again);
        // a floor dig: its fill reports (and counts) the new clear
        for (int j = seq.idx + 1; j < seq.steps.size(); j++) {
            if (seq.steps.get(j).state instanceof FloorSteps.FloorState fs && fs.clearStep == ws.clear) {
                fs.clearStep = again;
                fs.brokenBefore += broken;
            }
        }
        seq.splice(seq.idx + 1, steps);
        String say = "water at " + (at != null ? at.key() : "the dig") + ": " + r.what()
                + (r.placements().isEmpty() ? (r.drain() ? " - waiting for it to drain" : "") + ", digging on"
                        : " - sealing it (" + r.placements().size() + " block" + (r.placements().size() == 1 ? "" : "s") + "), then digging on");
        LOG.info("[entropybot] water: round {}: {}", t.rounds, say);
        if (to != null && t.rounds == 1) c.whisper(to, say);
        seq.setStatus(seq.label + " - " + say);
        return "next";
    }

    /** The dig ends blocked: the job's end (a dig), or the clear's outcome for the floor fill after it. */
    private static String end(Seq seq, WaterState ws, Clearing.Outcome prev, String endMsg, int broken) {
        ClearJob.Options o = ws.clear.clear;
        boolean finish = Clearing.KIND_FINISH.equals(ws.clear.kind);
        String m = WaterPlan.endMessage(endMsg, o.waterTally, broken, finish);
        ws.clear.cleared = prev == null ? new Clearing.Outcome(false, broken, 0, java.util.Map.of(), List.of(), m)
                : new Clearing.Outcome(false, prev.broken(), prev.left(), prev.mined(), prev.ores(), m);
        LOG.info("[entropybot] water: {}", m);
        if (finish) {
            seq.jobs.finish(m);
            return "wait";
        }
        return "next";
    }

    /** After a plug: wait (at most {@link WaterPlan#DRAIN_TICKS}) until no water is left near the spot inside the box. */
    static String drainRun(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof DrainState ds)) return "next";
        long now = seq.now();
        if (ds.start < 0) ds.start = now;
        long el = now - ds.start;
        if (el >= WaterPlan.DRAIN_TICKS) return "next";
        if (el % 20 != 0) return "wait";
        Clearing.LiveWorld w = new Clearing.LiveWorld();
        w.set(p);
        boolean wet = false;
        for (int x = ds.at.x() - 3; x <= ds.at.x() + 3 && !wet; x++)
            for (int y = ds.at.y() - 3; y <= ds.at.y() + 3 && !wet; y++)
                for (int z = ds.at.z() - 3; z <= ds.at.z() + 3 && !wet; z++)
                    wet = ds.box.contains(x, y, z) && w.fluid(x, y, z);
        if (!wet) return "next";
        seq.setStatus(seq.label + " - waiting for the water to drain (" + el / 20 + " s)");
        return "wait";
    }

    private static String requester(Seq seq, Commands c) {
        Jobs.Job j = seq.job();
        return j != null && j.req != null && j.req.from != null ? j.req.from : c.owner();
    }
}
