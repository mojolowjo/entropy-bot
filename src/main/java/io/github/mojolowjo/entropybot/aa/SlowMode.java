package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * AA_STEP3 B: slow mode, the gate. {@code aa slow on} holds the bot before a chosen way's first step, {@code aa slow step}
 * before every step, while the plan is shown (dashboard, path render); {@code go} (PM, dashboard button) releases it, and
 * so does {@code Tuning.slowPauseS} seconds without a word. The gate is asked by Chains before a step starts: {@link #hold}
 * true = not yet. One key per gate ("task 7", "task 7#2"): once released, the same key never holds again. Nothing is
 * said in the chat. Pure (JUnit: SlowModeTest). Loader notes: none.
 */
public final class SlowMode {
    public enum Mode { OFF, ON, STEP }

    /** One planned step: its text, predicted seconds (NaN none) and whether the game's numbers priced it alone. */
    public record Step(String text, double seconds, boolean known) {}

    /** A way that was not chosen: id, price in seconds, why. */
    public record Way(String id, double seconds, String why) {}

    /** What the dashboard shows while the bot waits. {@code next} = index of the step that starts on go. */
    public record Plan(String key, String kind, String objective, String desire, String way, double totalS, List<Step> steps, int next,
                       List<Way> rejected, List<String> dropped, List<int[]> route) {}

    private Mode mode = Mode.OFF;
    private Plan pending;
    private long since;
    private boolean goFlag;
    private String released;
    private String lastRelease = "";
    private int goes, timeouts, gates;

    public Mode mode() { return mode; }

    public Plan pending() { return pending; }

    public int goes() { return goes; }

    public int timeouts() { return timeouts; }

    public String lastRelease() { return lastRelease; }

    /** "on" / "off" / "step" (the arg of {@code aa slow}); the reply, or null for a word that is none of them. */
    public String set(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        Mode m = switch (a) {
            case "on" -> Mode.ON;
            case "off" -> Mode.OFF;
            case "step" -> Mode.STEP;
            default -> null;
        };
        if (m == null) return null;
        mode = m;
        pending = null;
        goFlag = false;
        released = null;
        return m == Mode.OFF ? "ok: slow mode off" : m == Mode.ON
                ? "ok: slow mode on - before each chosen way I stand still while the plan shows on the dashboard (/aa) and the route is drawn; go (PM or the Go button) starts it, silence for the pause starts it"
                : "ok: slow mode step - the same pause before every step of the way (go or silence starts each)";
    }

    public boolean on() { return mode != Mode.OFF; }

    /**
     * The gate, asked each tick while a step is due. True = hold. The plan is built (once per key) by {@code plan}; a throw
     * there is the caller's to handle (the gate then lets the step go).
     */
    public boolean hold(String key, long now, double pauseS, Supplier<Plan> plan) {
        if (mode == Mode.OFF || key.equals(released)) return false;
        if (pending == null || !pending.key().equals(key)) {
            pending = plan.get();
            since = now;
            goFlag = false;
            gates++;
            return true;
        }
        if (goFlag) return release(key, "go");
        if (now - since >= Math.round(pauseS * 1000)) {
            timeouts++;
            return release(key, "no word in " + Math.round(pauseS) + " s");
        }
        return true;
    }

    private boolean release(String key, String how) {
        lastRelease = pending == null ? how : pending.kind() + " " + pending.key() + ": " + how;
        if (how.equals("go")) goes++;
        released = key;
        pending = null;
        goFlag = false;
        return false;
    }

    /** {@code go}: the owner lets the waiting plan run. */
    public String go() {
        if (pending == null) return "nothing waits for go (slow mode " + mode.name().toLowerCase(Locale.ROOT) + ")";
        goFlag = true;
        return "ok: go - " + pending.kind() + " " + pending.key() + " runs";
    }

    /** The task ended or was cleared: nothing waits any more. */
    public void idle() {
        pending = null;
        goFlag = false;
    }

    public String status(long now, double pauseS) {
        String s = "slow " + mode.name().toLowerCase(Locale.ROOT);
        if (pending != null) s += " (waiting for go: " + pending.kind() + " " + pending.key() + ", " + Math.max(0, Math.round(pauseS - (now - since) / 1000.0)) + " s left)";
        return s + "; gates " + gates + ", go " + goes + ", silence " + timeouts + (lastRelease.isEmpty() ? "" : ", last " + lastRelease);
    }

    /** The dashboard's block: the mode, the waiting plan with its seconds left, the last release. */
    public JsonObject json(long now, double pauseS) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        o.addProperty("pauseS", pauseS);
        o.addProperty("lastRelease", lastRelease);
        if (pending == null) return o;
        Plan p = pending;
        JsonObject j = new JsonObject();
        j.addProperty("key", p.key());
        j.addProperty("kind", p.kind());
        j.addProperty("objective", p.objective());
        j.addProperty("desire", p.desire());
        j.addProperty("way", p.way());
        j.addProperty("totalS", num(p.totalS()));
        j.addProperty("secondsLeft", Math.max(0, Math.round(pauseS - (now - since) / 1000.0)));
        j.addProperty("next", p.next());
        JsonArray st = new JsonArray();
        for (Step s : p.steps()) {
            JsonObject x = new JsonObject();
            x.addProperty("text", s.text());
            x.addProperty("seconds", num(s.seconds()));
            x.addProperty("known", s.known());
            st.add(x);
        }
        j.add("steps", st);
        JsonArray rj = new JsonArray();
        for (Way w : p.rejected()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", w.id());
            x.addProperty("seconds", num(w.seconds()));
            x.addProperty("why", w.why());
            rj.add(x);
        }
        j.add("rejected", rj);
        JsonArray dj = new JsonArray();
        for (String d : p.dropped()) dj.add(d);
        j.add("dropped", dj);
        o.add("pending", j);
        return o;
    }

    private static double num(double v) { return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : -1; }
}
