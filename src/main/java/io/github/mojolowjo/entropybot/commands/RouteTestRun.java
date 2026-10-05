package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.routewalk.RouteTestPlan;
import io.github.mojolowjo.entropybot.routewalk.RouteWalk;
import io.github.mojolowjo.entropybot.routewalk.TripLog;
import io.github.mojolowjo.entropybot.routewalk.TripMeter;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code route test <placeA> <placeB> [n]} (routing stage 1, R3): a walk to A (not measured), then n trips back and
 * forth, the mode going goal, legs, plain in turn ({@link RouteTestPlan}). No /home on these walks. Baritone's
 * {@code chatDebug} is on for the test only (client chat, never sent to the server) and back to what it was when the
 * job ends, however it ends. Each trip is a row in {@code entropybot/routes/trips.csv} ({@link TripLog}); the end
 * note compares the modes. A failed trip is recorded and the next one goes on.
 *
 * <p>Loader API: Baritone's api (settings, cancel), Minecraft's LocalPlayer; the file goes through java.nio.
 */
final class RouteTestRun {
    private static final Logger LOG = LogUtils.getLogger();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Core core;
    private final String test, nameA, nameB;
    private final int[] posA, posB;
    private final List<RouteTestPlan.Trip> plan;
    private final List<TripLog.Trip> done = new ArrayList<>();
    private final Path csv;
    private Boolean chatDebugWas;
    private String fileError;
    // the trip under way
    private RouteTestPlan.Trip trip;
    private TripMeter meter;
    private long evCursor, startMs;
    private int[] from;

    private RouteTestRun(Core core, String a, int[] pa, String b, int[] pb, int n) {
        this.core = core;
        this.nameA = a;
        this.nameB = b;
        this.posA = pa;
        this.posB = pb;
        this.plan = RouteTestPlan.trips(n);
        this.test = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
        this.csv = core.files().root().resolve("routes").resolve("trips.csv");
    }

    /** "route test A B [n]": checks the places and starts the job. */
    static String start(Commands c, Jobs jobs, Storage storage, LocalPlayer p, String rest) {
        RouteRules.TestArgs a = RouteRules.testArgs(rest);
        if (a.error() != null) return a.error();
        String dim = Guard.dimOf(p.level());
        int[][] pos = new int[2][];
        String[] names = {a.a(), a.b()};
        for (int i = 0; i < 2; i++) {
            JsonObject o = Core.INSTANCE.knowledge.places().get(names[i]);
            if (o == null) return "I have no place called " + names[i] + " - next: " + Hints.placeFix(names[i]);
            if (!Jobs.dimOf(o).equals(dim)) return "error: " + names[i] + " is in " + Jobs.dimOf(o) + " (route test walks, no teleports)";
            pos[i] = Jobs.pos(o);
            String why = jobs.goalAllowed(pos[i][0], pos[i][1], pos[i][2]);
            if (why != null) return Jobs.fenceError(why);
        }
        RouteTestRun run = new RouteTestRun(Core.INSTANCE, a.a(), pos[0], a.b(), pos[1], a.trips());
        List<Seq.Step> steps = new ArrayList<>();
        Seq.Step first = Seq.Step.walk(pos[0], true);
        first.noHome = true;
        first.why = a.a() + " (to start the test)";
        steps.add(first);
        for (int i = 0; i < run.plan.size(); i++) {
            RouteTestPlan.Trip t = run.plan.get(i);
            Seq.Step b = new Seq.Step("routetrip");
            b.n = i;
            steps.add(b);
            Seq.Step w = Seq.Step.walk(t.aToB() ? pos[1] : pos[0], true);
            w.noHome = true;
            w.why = (t.aToB() ? a.b() : a.a()) + " (trip " + t.n() + " of " + run.plan.size() + ", " + t.mode().word() + ")";
            steps.add(w);
            Seq.Step e = new Seq.Step("routetripend");
            e.n = i;
            steps.add(e);
        }
        Seq s = new Seq(jobs, storage, "route test " + a.a() + " <-> " + a.b() + ", " + run.plan.size() + " trips", steps, "fail");
        s.routeTest = run;
        String r = jobs.startSeq(s, "fail");
        if (jobs.job != null && jobs.job.seq == s) {
            run.chatDebug(true);
            jobs.job.onEnd = run::ended;
        }
        return r + " (trips go to entropybot/routes/trips.csv; Baritone's debug lines show in my chat meanwhile)";
    }

    private void chatDebug(boolean on) {
        try {
            if (on) {
                chatDebugWas = BaritoneAPI.getSettings().chatDebug.value;
                BaritoneAPI.getSettings().chatDebug.value = true;
            } else if (chatDebugWas != null) {
                BaritoneAPI.getSettings().chatDebug.value = chatDebugWas;
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] route test: chatDebug {}: {}", on ? "on" : "back", t.toString());
        }
    }

    /** The job ended (done, stopped, failed): chatDebug back as it was. */
    void ended() {
        chatDebug(false);
        trip = null;
        meter = null;
    }

    /** "routetrip": the trip starts (its mode for the walk step after it). */
    String begin(Seq seq, Seq.Step st, LocalPlayer p) {
        trip = plan.get(st.n);
        seq.routeMode = trip.mode();
        meter = new TripMeter(core.tick());
        evCursor = core.events.lastSeq();
        startMs = System.currentTimeMillis();
        from = Jobs.here(p);
        return "next";
    }

    /** Every Seq tick: the new events go to the trip's meter. */
    void poll() {
        if (meter == null) return;
        try {
            JsonArray list = JsonParser.parseString(core.events.since(evCursor, 0)).getAsJsonArray();
            boolean firstSeen = false;
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                long seq = o.get("seq").getAsLong();
                if (!firstSeen && seq > evCursor + 1) meter.markLost();
                firstSeen = true;
                evCursor = seq;
                meter.event(o.get("tick").getAsLong(), o.get("kind").getAsString(), o.get("text").getAsString());
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] route test: reading events: {}", e.toString());
        }
    }

    /** "routetripend": the trip arrived. */
    String end(Seq seq, Seq.Step st, LocalPlayer p) {
        record(seq, p, true, "ok");
        return "next";
    }

    /**
     * A step failed: when it is this test's trip walk, the trip is recorded as failed and the test goes on with the
     * next trip (true); anything else ends the job as usual (false).
     */
    boolean caught(Seq seq, String r, LocalPlayer p) {
        if (trip == null || meter == null) return false;
        int i = seq.idx;
        if (i + 1 >= seq.steps.size() || !seq.steps.get(i).type.equals("walk") || !seq.steps.get(i + 1).type.equals("routetripend")) return false;
        IBaritone b = Jobs.baritone();
        if (b != null) Jobs.cancel(b);
        record(seq, p, false, r);
        seq.idx = i + 2;
        seq.stage = null;
        seq.stepStart = seq.now();
        return true;
    }

    private void record(Seq seq, LocalPlayer p, boolean ok, String result) {
        if (trip == null) return;
        poll();
        Jobs.Job j = seq.job();
        RouteWalk w = j == null ? null : j.route;
        String used = trip.mode() == RouteWalk.Mode.PLAIN ? "plain" : w != null ? w.used() : "plain (" + RouteWalker.last.replaceFirst("^plain: ", "") + ")";
        int[] to = Jobs.here(p);
        double blocks = Math.sqrt(Jobs.distSq(from, to));
        TripLog.Trip t = new TripLog.Trip(LocalDateTime.now().format(TIME), test, trip.n(), trip.aToB() ? nameA : nameB,
                trip.aToB() ? nameB : nameA, trip.mode().word(), used, ok, (System.currentTimeMillis() - startMs) / 1000.0, blocks,
                meter.searches(), meter.segments(), meter.stoppedSeconds(), meter.firstStepSeconds(),
                meter.debugLines() > 0 ? meter.nodes() : -1, meter.debugLines() > 0 ? meter.movements() : -1, meter.searchMs(),
                meter.failures(), meter.debugLines(), meter.lost(), result);
        done.add(t);
        append(t);
        LOG.info("[entropybot] route test trip {}: {}", trip.n(), TripLog.row(t));
        seq.routeMode = null;
        boolean last = trip.n() >= plan.size();
        trip = null;
        meter = null;
        if (last) seq.note = TripLog.summary(done).replace("\n", " | ") + (fileError != null ? " | " + fileError : "");
    }

    private void append(TripLog.Trip t) {
        try {
            Files.createDirectories(csv.getParent());
            boolean fresh = !Files.exists(csv) || Files.size(csv) == 0;
            String text = (fresh ? TripLog.HEADER + System.lineSeparator() : "") + TripLog.row(t) + System.lineSeparator();
            Files.writeString(csv, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            fileError = "couldn't write trips.csv: " + e;
            LOG.warn("[entropybot] route test: {}", fileError);
        }
    }
}
