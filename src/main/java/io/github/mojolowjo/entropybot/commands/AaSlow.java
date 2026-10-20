package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.Options;
import io.github.mojolowjo.entropybot.aa.Predictions;
import io.github.mojolowjo.entropybot.aa.Replan;
import io.github.mojolowjo.entropybot.aa.SlowMode;
import io.github.mojolowjo.entropybot.aa.SlowRoute;
import io.github.mojolowjo.entropybot.aa.StepTimes;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * AA_STEP3 B: the game side of slow mode and the prediction ledger. Slow mode: Chains asks {@link #hold} before it
 * dispatches a step of AA's chain; while the plan waits the dashboard shows it ({@code AaLive} "slow"), the route is drawn
 * ({@link SlowRoute}, engine/SlowRender) and Baritone's renderPath / renderGoal are on for the run (restored on
 * {@code aa slow off} / {@code aa off}). Ledger: every step of AA's chain that ends appends one row to
 * {@code entropybot/predictions.csv} in the background, slow mode or not; the time slow mode held is not counted. Nothing
 * is said in the chat; the log has one line per gate and per release.
 *
 * <p>Loader notes: the gate is our Chains hook; the draw is a NeoForge client event (RenderLevelStageEvent, registered in
 * WatchEvents.register on the NeoForge event bus; Fabric: WorldRenderEvents.AFTER_TRANSLUCENT); Baritone settings through
 * its API ({@code BaritoneAPI.getSettings()}), the same access as {@code aa look}.
 */
final class AaSlow {
    private static final Logger LOG = LogUtils.getLogger();

    final SlowMode mode = new SlowMode();
    private final Commands c;
    private final AaRuntime rt;

    /** The predicted seconds of the running chain's steps, in step order; looked up by text when a step ends. */
    private static final class Pred {
        final String text;
        final double s;
        final boolean known;
        boolean used;

        Pred(String text, double s, boolean known) {
            this.text = text;
            this.s = s;
            this.known = known;
        }
    }

    private final List<Pred> preds = new ArrayList<>();
    private String objective = "";
    private String stepNow;
    private double stepPred = Double.NaN;
    private long stepStart, heldMs, lastTickAt = -1;
    private double stepBlocks, stepDamage;
    private int[] lastPos;
    private float lastHealth = -1;
    private String lastReleaseSeen = "";
    private int rows, unpredicted;
    private volatile long writeErrors, lastWriteErrorAt;
    private Boolean fileHasContent;
    private Boolean savedRenderPath, savedRenderGoal;

    AaSlow(Commands c, AaRuntime rt) {
        this.c = c;
        this.rt = rt;
    }

    double pauseS() {
        double v = Tuning.get("slowPauseS");
        return Double.isNaN(v) ? 20 : v;
    }

    // ---- aa slow / go / predict ----

    String command(String arg) {
        String a = arg == null ? "" : arg.trim();
        long now = System.currentTimeMillis();
        if (a.isEmpty()) return mode.status(now, pauseS()) + "; ledger " + rows + " rows this run" + (unpredicted > 0 ? ", " + unpredicted + " steps without a prediction" : "")
                + (writeErrors > 0 ? ", " + writeErrors + " write error(s)" : "");
        String r = mode.set(a);
        if (r == null) return "usage: aa slow on|off|step (now " + mode.mode().name().toLowerCase() + ") - on: pause before each chosen way; step: before every step; go (PM or the dashboard) or " + Math.round(pauseS()) + " s of silence starts it";
        if (mode.on()) renderOn();
        else {
            renderRestore();
            SlowRoute.clear();
        }
        LOG.info("[entropybot] aa: slow mode {}", mode.mode().name().toLowerCase());
        return r + (rt.on() ? "" : " (aa is off: it takes effect when aa on starts a task)");
    }

    String go() {
        String r = mode.go();
        if (r.startsWith("ok")) LOG.info("[entropybot] aa: slow: go");
        return r;
    }

    boolean holding() { return mode.pending() != null; }

    /** Turn-off / world change: slow mode ends with the run (Baritone's settings back, nothing drawn). */
    void reset() {
        mode.set("off");
        renderRestore();
        SlowRoute.clear();
        preds.clear();
        stepNow = null;
    }

    // ---- the gate (Chains.StepGate) ----

    boolean hold(String chain, int idx, String step) {
        if (!mode.on() || !AaRuntime.CHAIN.equals(chain) || !rt.on() || rt.slowSkip()) return false;
        boolean each = mode.mode() == SlowMode.Mode.STEP;
        if (!each && idx != 0) return false;
        long now = System.currentTimeMillis();
        String key = "task " + rt.taskSeq() + (each ? "#" + (idx + 1) : "");
        boolean held = mode.hold(key, now, pauseS(), () -> plan(key, each ? "step" : "objective", idx));
        SlowRoute.waiting = held;
        return held;
    }

    private SlowMode.Plan plan(String key, String kind, int idx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        int[] me = p == null ? null : Jobs.here(p);
        List<String> steps = c.chains.stepsNow();
        Options.Option opt = rt.runningOption();
        double total = opt == null ? Double.NaN : opt.seconds();
        List<StepTimes.Est> est = predsMatch(steps) ? fromPreds(steps) : StepTimes.estimate(steps, me, total);
        List<SlowMode.Step> out = new ArrayList<>();
        for (int i = idx; i < est.size(); i++) out.add(new SlowMode.Step(StepTimes.clean(est.get(i).step()), est.get(i).seconds(), est.get(i).known()));
        List<SlowMode.Way> rejected = new ArrayList<>();
        List<String> dropped = List.of();
        var ch = rt.lastChoice();
        if (ch != null) {
            for (Options.Option o : ch.ranked()) if (opt == null || !o.id().equals(opt.id())) rejected.add(new SlowMode.Way(o.id(), o.seconds(), o.why()));
            dropped = ch.dropped();
        }
        List<int[]> route = StepTimes.route(steps.subList(Math.min(idx, steps.size()), steps.size()), me,
                opt == null || opt.site() == null ? null : new int[]{opt.site().x(), opt.site().y(), opt.site().z()});
        SlowRoute.points = route;
        SlowRoute.waiting = true;
        double sum = 0;
        for (SlowMode.Step s : out) if (Double.isFinite(s.seconds())) sum += s.seconds();
        LOG.info("[entropybot] aa: slow: {} waits for go ({} s): {} - {} steps, ~{} s, {} other way(s)", kind, Math.round(pauseS()), rt.objective(), out.size(), Math.round(sum), rejected.size());
        return new SlowMode.Plan(key, kind, rt.objective(), rt.desireId(), opt == null ? "" : opt.id(), sum, out, 0, rejected, dropped, route);
    }

    private boolean predsMatch(List<String> steps) {
        if (preds.size() != steps.size()) return false;
        for (int i = 0; i < steps.size(); i++) if (!preds.get(i).text.equals(StepTimes.clean(steps.get(i)))) return false;
        return true;
    }

    private List<StepTimes.Est> fromPreds(List<String> steps) {
        List<StepTimes.Est> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) out.add(new StepTimes.Est(steps.get(i), preds.get(i).s, preds.get(i).known));
        return out;
    }

    // ---- the ledger ----

    /** AA's chain started (or switched at a replan): the steps are priced once, here, and kept for the ledger. */
    void chainStarted() {
        LocalPlayer p = Minecraft.getInstance().player;
        int[] me = p == null ? null : Jobs.here(p);
        List<String> steps = c.chains.stepsNow();
        Options.Option opt = rt.runningOption();
        List<StepTimes.Est> est = StepTimes.estimate(steps, me, opt == null ? Double.NaN : opt.seconds());
        preds.clear();
        for (StepTimes.Est e : est) preds.add(new Pred(StepTimes.clean(e.step()), e.seconds(), e.known()));
        objective = rt.objective();
    }

    void tick(LocalPlayer p, long now) {
        // position and health deltas for the step in progress
        int[] pos = Jobs.here(p);
        if (lastPos != null) {
            double d = Math.sqrt(Math.pow(pos[0] - lastPos[0], 2) + Math.pow(pos[2] - lastPos[2], 2));
            if (d < 30) stepBlocks += d;                  // a teleport (death, a nether trip) is not a walk
        }
        lastPos = pos;
        float h = p.getHealth();
        if (lastHealth >= 0 && h < lastHealth) stepDamage += lastHealth - h;
        lastHealth = h;
        boolean waiting = mode.pending() != null;
        if (waiting && lastTickAt >= 0) heldMs += now - lastTickAt;
        lastTickAt = now;
        if (waiting) rt.poke();                            // a held plan is not "no progress" for the stuck watch
        String st = rt.ownChainRunning() ? c.chains.stepText() : null;
        String body = st == null ? null : StepTimes.clean(Replan.stepBody(st));
        if (stepNow != null && (body == null || !body.equals(stepNow))) finishStep(now);
        if (body != null && stepNow == null) {
            stepNow = body;
            stepStart = now;
            heldMs = 0;
            stepBlocks = 0;
            stepDamage = 0;
            stepPred = Double.NaN;
            for (Pred pr : preds) if (!pr.used && pr.text.equals(body)) { pr.used = true; stepPred = pr.s; break; }
        }
        if (body == null) {
            mode.idle();
            if (SlowRoute.points.size() > 0) SlowRoute.clear();
        }
        String rel = mode.lastRelease();
        if (!rel.equals(lastReleaseSeen)) {
            lastReleaseSeen = rel;
            LOG.info("[entropybot] aa: slow: released ({})", rel);
        }
    }

    private void finishStep(long now) {
        String step = stepNow;
        stepNow = null;
        double actual = Math.max(0, now - stepStart - heldMs) / 1000.0;
        if (!(stepPred > 0)) {
            unpredicted++;
            return;
        }
        rows++;
        write(new Predictions.Row(now, objective, StepTimes.key(step), step, stepPred, actual, stepBlocks, stepDamage));
    }

    private Path file() { return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("predictions.csv"); }

    private void write(Predictions.Row r) {
        Path f = file();
        if (f == null) return;
        try {
            if (fileHasContent == null) fileHasContent = Files.exists(f) && Files.size(f) > 0;
            String text = Predictions.append(fileHasContent, r);
            fileHasContent = true;
            AaRuntime.background(() -> {
                try {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException | RuntimeException e) {
                    writeFailed(e);
                }
            });
        } catch (IOException | RuntimeException e) {
            writeFailed(e);
        }
    }

    private void writeFailed(Exception e) {
        writeErrors++;
        long now = System.currentTimeMillis();
        if (now - lastWriteErrorAt > 60_000) {
            lastWriteErrorAt = now;
            LOG.warn("[entropybot] aa: predictions.csv write failed ({} so far): {}", writeErrors, e.toString());
        }
    }

    /** aa predict: the last 20 rows and the step kinds the model gets wrong. */
    String predict() {
        Path f = file();
        if (f == null) return "aa predict: no world loaded";
        try {
            if (!Files.exists(f)) return Predictions.text(List.of(), 0, "entropybot/predictions.csv");
            String text = tail(f, 400_000);
            int[] bad = {0};
            return Predictions.text(Predictions.parse(text, bad), bad[0], "entropybot/predictions.csv");
        } catch (IOException | RuntimeException e) {
            return "error: aa predict could not read predictions.csv: " + e;
        }
    }

    /** The file's last {@code max} bytes from a line start (a big ledger is not read whole). */
    private static String tail(Path f, long max) throws IOException {
        long size = Files.size(f);
        if (size <= max) return Files.readString(f, StandardCharsets.UTF_8);
        try (var ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.READ)) {
            var buf = java.nio.ByteBuffer.allocate((int) max);
            ch.read(buf, size - max);
            String s = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(nl + 1);
        }
    }

    // ---- Baritone's own path render, on for the run ----

    private void renderOn() {
        try {
            var s = baritone.api.BaritoneAPI.getSettings();
            if (savedRenderPath == null) {
                savedRenderPath = s.renderPath.value;
                savedRenderGoal = s.renderGoal.value;
            }
            s.renderPath.value = true;
            s.renderGoal.value = true;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] aa: slow: Baritone's renderPath could not be switched on ({}); the mod's own route still draws", e.toString());
        }
    }

    private void renderRestore() {
        if (savedRenderPath == null) return;
        try {
            var s = baritone.api.BaritoneAPI.getSettings();
            s.renderPath.value = savedRenderPath;
            s.renderGoal.value = savedRenderGoal;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[entropybot] aa: slow: Baritone's render settings could not be restored: {}", e.toString());
        }
        savedRenderPath = null;
        savedRenderGoal = null;
    }

    com.google.gson.JsonObject json(long now) { return mode.json(now, pauseS()); }
}

