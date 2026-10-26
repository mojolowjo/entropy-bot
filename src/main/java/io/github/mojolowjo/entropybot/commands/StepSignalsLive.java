package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Map;

/**
 * 0.39.z71 (design D2 3.2 / 4, Job B): the running step's {@link StepSignals} from the game, for the step monitor. Read
 * only, client thread, one read per monitor look (monitor.everyTicks):
 * <ul>
 * <li>Baritone's API only: {@code IPathingBehavior.getGoal()} with {@code Goal.heuristic(x, y, z)} / {@code isInGoal},
 *     {@code getCurrent()} (executing), {@code getInProgress()} (a search), and the CALC_FAILED count the existing
 *     {@code BaritoneHook} path listener keeps;</li>
 * <li>vanilla: the feet, the bag ({@code Gui.inventory}), {@code MultiPlayerGameMode#isDestroying / getDestroyStage} (as
 *     AaRuntime's progress already reads them);</li>
 * <li>the placed count from the existing {@code GuardMixinBlockItem} RETURN hook ({@link #placed}; no new mixin);</li>
 * <li>the explore's new chunks from Mining.</li>
 * </ul>
 * Rule 15: no player, no Baritone, its event hook not wired yet (the CALC_FAILED count would be a quiet zero) or a reflex
 * holding the bot: not loaded with why. Loader notes: Baritone API (IPathingBehavior, Goal); vanilla Minecraft.player,
 * MultiPlayerGameMode; Fabric: the same calls under Yarn names, the mixin hook unchanged.
 */
public final class StepSignalsLive {
    /** One per StepHands (the running step's made baseline). */
    StepSignalsLive() {}

    /** Blocks this client placed (BlockItem.place consumed the action), a running count. */
    private static volatile long placed;

    /** GuardMixinBlockItem at RETURN of BlockItem.place (client side): one more block placed. */
    public static void placed(net.minecraft.world.InteractionResult r) {
        if (r != null && r.consumesAction()) placed++;
    }

    public static long placedCount() { return placed; }

    /** The output word and its bag count when the step started (made = the gain since), null word: none named. */
    private String outWord;
    private int outBase;
    /** The step explores (Mining's chunk count is its signal; any other step: -1). */
    private boolean explores;

    /** StepHands: a step started; its output's bag count now (the made baseline). */
    void begin(Step s, LocalPlayer p) {
        explores = StepMonitor.kind(s) == StepMonitor.Kind.EXPLORE;
        outWord = StepMonitor.output(s);
        outBase = outWord == null || p == null ? 0 : count(Gui.inventory(p), outWord);
        if (p == null) outWord = null;
    }

    /** The bag's count of {@code word}: an item id, or a tag / "a|b" word as its members summed. */
    static int count(Map<String, Integer> bag, String word) {
        String w = io.github.mojolowjo.entropybot.aa.NeedChain.word(word);
        if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(w)) return bag.getOrDefault(w, 0);
        int n = 0;
        for (String m : io.github.mojolowjo.entropybot.aa.NeedChain.membersOf(w)) n += bag.getOrDefault(m.indexOf(':') < 0 ? "minecraft:" + m : m, 0);
        return n;
    }

    /**
     * 0.39.z76 send-back 4 (run AV 02:43:29, 02:52:43, 02:56:09: three working strips - 19, 26 and 40 blocks mined, the
     * second inside an iron cluster - ended "no progress 30 s ... no block broken"; no monitor line in runs AC-AW ever showed
     * a stage): the game's break stage (0-9) of the block being broken, -1: none. The one read, for the step monitor and
     * AaRuntime's progress tick. Baritone's {@code BlockBreakHelper.tick} sets {@code MultiPlayerGameMode#isDestroying} back
     * to false at the end of every break tick (so vanilla's {@code continueAttack} does not abort a break it has no key for)
     * and restores it on its next tick; read after that, {@code isDestroying} is always false, so the old read
     * "isDestroying ? stage : -1" was -1 for every Baritone dig. The progress behind {@code getDestroyStage} stays while the
     * block is broken and is 0 again once it breaks; Baritone's {@code stopBreakingBlock} can leave it stale (its reset runs
     * with isDestroying already false), so a stage counts only while the arm swings (Baritone swings on every damaging tick)
     * and the crosshair is on a block (review: a mob hit swings too). A stale stage can still show on such a swing; the
     * callers count a change only between two stages ({@link io.github.mojolowjo.entropybot.aa.core.BreakProgress}, the
     * monitor's per-cell best). Loader notes: vanilla {@code MultiPlayerGameMode#getDestroyStage},
     * {@code LivingEntity#swinging}, {@code Minecraft#hitResult}; Fabric: the same under Yarn names.
     */
    static int destroyStage(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        var gm = mc.gameMode;
        if (gm == null || p == null || !p.swinging || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult)) return -1;
        return gm.getDestroyStage();
    }

    /**
     * The signals now. {@code notLoaded}: the hands' own rule-15 wait (the station fact not loaded), null: none;
     * {@code ownWait}: the step waits on its own clock (the furnace, the first-answer wait), null: none.
     */
    StepSignals read(String notLoaded, String ownWait) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return StepSignals.notLoaded("no player");
        if (notLoaded != null) return StepSignals.notLoaded(notLoaded);
        IBaritone b = Jobs.baritone();
        if (b == null) return StepSignals.notLoaded("Baritone not loaded");
        if (!Core.INSTANCE.baritone.hooked()) return StepSignals.notLoaded("Baritone's event hook is not wired yet (no CALC_FAILED count)");
        if (Core.INSTANCE.reflexes.hold()) return StepSignals.notLoaded("a reflex holds the bot (its own watch)");
        int[] f = Jobs.here(p);
        Goal g = b.getPathingBehavior().getGoal();
        double d = g == null ? Double.NaN : g.heuristic(f[0], f[1], f[2]);
        boolean in = g != null && g.isInGoal(f[0], f[1], f[2]);
        int ds = destroyStage(p);
        // 0.39.z71 review 1: the block being broken is the one under the crosshair (Baritone looks at it; vanilla hitResult)
        String cell = ds >= 0 && Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.BlockHitResult bh ? Jobs.fmt(new int[]{bh.getBlockPos().getX(), bh.getBlockPos().getY(), bh.getBlockPos().getZ()}) : null;
        int made = outWord == null ? -1 : Math.max(0, count(Gui.inventory(p), outWord) - outBase);
        return new StepSignals(true, null, f, d, g == null ? null : String.valueOf(g), in, b.getPathingBehavior().getCurrent() != null, b.getPathingBehavior().getInProgress().isPresent(),
                io.github.mojolowjo.entropybot.baritone.BaritoneHook.calcFailed(), made, ds, cell, placed, explores ? Mining.get().exploredChunks() : -1, ownWait);
    }
}
