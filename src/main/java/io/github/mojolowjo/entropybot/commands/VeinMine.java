package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.clear.Pos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rm-final: one ore vein mined over Baritone's MineProcess (the explore gather, the per-ore {@code Step.MineAt} and the mine
 * verb's per-ore step; the clear engine stays for the owner's typed dig and clear verbs). The mod says what to achieve (these
 * cells, of these block kinds, gone); Baritone's process walks to them, looks, picks the tool and breaks (its break hook is
 * the guard's cost and no-break floor). The vein is done when none of its cells still holds the block it held at the start;
 * Baritone ending first, the time limit, and a refusal at the start are the named failure. Client thread only. Loader notes:
 * Baritone API (MineProcess, BlockOptionalMetaLookup), vanilla block registry.
 */
final class VeinMine {
    private static final Logger LOG = LogUtils.getLogger();
    /** The vein being mined now (one at a time); the job's end stops it. */
    private static VeinMine active;

    private final List<int[]> cells = new ArrayList<>();
    private final List<String> idAt = new ArrayList<>();
    private final List<Block> kinds = new ArrayList<>();
    private final Map<String, Integer> mined = new LinkedHashMap<>();
    private final String label;
    /** 0.39.z76 send-back 3 (rule 22): the mine run may place (pillar, bridge); false inside an explore's gather. */
    private boolean allowPlace = true;
    private boolean started;
    private long startTick;
    private int broken;

    VeinMine(List<Pos> vein, String label) {
        for (Pos p : vein) cells.add(new int[]{p.x(), p.y(), p.z()});
        this.label = label;
    }

    boolean started() { return started; }

    int broken() { return broken; }

    int left() { return cells.size() - broken; }

    Map<String, Integer> mined() { return mined; }

    /** Null when Baritone's mine process runs on the vein's block kinds, else why it could not start. */
    String begin(Level level) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "baritone not loaded";
        for (int[] c : cells) {
            BlockPos q = new BlockPos(c[0], c[1], c[2]);
            if (!level.isLoaded(q)) { idAt.add(null); continue; }
            Block blk = level.getBlockState(q).getBlock();
            String id = BuiltInRegistries.BLOCK.getKey(blk).toString();
            idAt.add(level.getBlockState(q).isAir() ? null : id);
            if (!level.getBlockState(q).isAir() && !kinds.contains(blk)) kinds.add(blk);
        }
        if (kinds.isEmpty()) return "no block of the vein is there to mine (" + label + ")";
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): no Baritone setting here; the fork's mine run owns
        // breaking and placing for its run (mineWith) and puts them back at its end
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "vein");
        // quantity 0: Baritone mines on; the vein is done when its cells are gone, and then the process is cancelled here
        try {
            b.getMineProcess().mineWith(0, new BlockOptionalMetaLookup(kinds.toArray(new Block[0])), allowPlace);
        } catch (LinkageError e) {
            return "this Baritone has no mineWith (the stock jar? install the entropy fork): " + e;
        }
        started = true;
        startTick = Core.INSTANCE.tick();
        active = this;
        LOG.info("[entropybot] vein: {} - Baritone's mine process on {} block kind(s), {} cells", label, kinds.size(), cells.size());
        return null;
    }

    /** RUNNING while cells remain and Baritone mines, DONE when none holds its block any more, FAILED with the reason. */
    Car.Status poll(Level level) {
        int gone = 0;
        Map<String, Integer> now = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String id = idAt.get(i);
            if (id == null) continue;
            int[] c = cells.get(i);
            BlockPos q = new BlockPos(c[0], c[1], c[2]);
            if (!level.isLoaded(q)) continue;
            if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).toString().equals(id)) {
                gone++;
                now.merge(id, 1, Integer::sum);
            }
        }
        broken = gone;
        mined.clear();
        mined.putAll(now);
        int total = 0;
        for (String id : idAt) if (id != null) total++;
        if (gone >= total) {
            stop();
            return Car.Status.done("ok: " + label + ": " + gone + " block" + (gone == 1 ? "" : "s") + " mined");
        }
        if (Core.INSTANCE.tick() - startTick > (long) (20 * Tuning.get("steps.stepMaxS"))) {   // game fact: 20 game ticks per second
            stop();
            return Car.Status.failed(label + ": timed out with " + (total - gone) + " of " + total + " vein blocks left");
        }
        IBaritone b = Jobs.baritone();
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getMineProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            stop();
            return Car.Status.failed(label + ": Baritone's mine process ended with " + (total - gone) + " of " + total + " vein blocks left (none it could reach)");
        }
        return Car.Status.running(label + ": " + gone + " of " + total);
    }

    /** Ends Baritone's mine process (it puts its own settings back) and the policy intent back to travel. */
    void stop() {
        try {
            IBaritone b = Jobs.baritone();
            if (b != null) b.getMineProcess().cancel();
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "vein end");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] vein: stopping {} failed: {}", label, e.toString());
        }
        if (active == this) active = null;
    }

    /** A job ended or was stopped: a vein still mining stops (never silently: the log says so). */
    static void cancelActive(String why) {
        VeinMine v = active;
        if (v == null) return;
        LOG.info("[entropybot] vein: {} stopped: {}", v.label, why);
        v.stop();
    }

    // ---- the Seq glue: a vein step spliced into a mine or explore job ----

    static Seq.Step seqStep(List<Pos> vein, String label) { return seqStep(vein, label, true); }

    /**
     * 0.39.z76 send-back 3 (rule 22: "no placing or running away inside an explore"; run AU 01:58:12, 02:01:06, 02:02:02: the
     * client's placed count rose right after each gather vein of the explore - mineWith(..., allowPlace true)): {@code allowPlace}
     * false for an explore's gather.
     */
    static Seq.Step seqStep(List<Pos> vein, String label, boolean allowPlace) {
        Seq.Step s = new Seq.Step("veinmine");
        VeinMine v = new VeinMine(vein, label);
        v.allowPlace = allowPlace;
        s.state = v;
        return s;
    }

    static String step(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof VeinMine v)) return "the vein step was not set up";
        if (!v.started()) {
            String bad = v.begin(p.level());
            if (bad != null) {
                st.cleared = new Clearing.Outcome(false, 0, v.cells.size(), Map.of(), List.of(), bad);
                return bad;
            }
            seq.setStatus(v.label);
            return "wait";
        }
        Car.Status s = v.poll(p.level());
        switch (s.state()) {
            case RUNNING -> { return "wait"; }
            case DONE -> {
                st.cleared = new Clearing.Outcome(true, v.broken(), 0, new LinkedHashMap<>(v.mined()), List.of(), s.why());
                return "next";
            }
            default -> {
                st.cleared = new Clearing.Outcome(false, v.broken(), v.left(), new LinkedHashMap<>(v.mined()), List.of(), s.why());
                return s.why();
            }
        }
    }
}