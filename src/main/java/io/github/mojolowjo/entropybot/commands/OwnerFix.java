package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Where the owner is when the bot cannot see them: the last position the Entropy Companion mod (client-only, in the
 * owner's own game) posted to the dashboard, which wrote it to {@code entropybot/owner.json}:
 * {@code {"name","x","y","z","dim","at","received"}} ({@code received} = the laptop's clock, epoch ms).
 * A fix counts only when it is for that player, at most {@link #MAX_AGE_MS} old and in the bot's dimension; anything
 * else gives null, so a stale or other-dimension position never moves the bot. The file is read again only when its
 * modification time or size changes. No Minecraft types, so the rules are unit tested.
 */
public final class OwnerFix {
    public static long maxAgeMs() { return Tuning.l("ownerFix.maxAgeMs"); }
    /** A fix "from the future" (the clock was set back) is refused beyond this. */
    static long futureSlackMs() { return Tuning.l("ownerFix.futureSlackMs"); }

    /** One parsed owner.json. */
    public record Fix(String name, double x, double y, double z, String dim, long received, int food, int health) {
        /** A fix without vitals (an old companion): food and health unknown (-1). */
        public Fix(String name, double x, double y, double z, String dim, long received) { this(name, x, y, z, dim, received, -1, -1); }
    }

    /**
     * C7: the player's {food, health} from companion v2 (fields "food", "health"; -1 each when missing), or null
     * unless the fix is for that player and fresh (any dimension: hunger does not depend on where they are).
     */
    public static int[] vitals(Fix f, String player, long nowMs) {
        if (f == null || player == null || !f.name().equalsIgnoreCase(player)) return null;
        long age = nowMs - f.received();
        if (age > maxAgeMs() || age < -futureSlackMs()) return null;
        return new int[]{f.food(), f.health()};
    }

    public int[] vitals(String player) { return vitals(current(), player, System.currentTimeMillis()); }

    /** A whole number 0..1000 (health may come as 19.5: rounded), else -1. */
    static int intOr(JsonObject o, String k) {
        try {
            if (!o.has(k) || !o.get(k).isJsonPrimitive()) return -1;
            double d = o.get(k).getAsDouble();
            return Double.isFinite(d) && d >= 0 && d <= 1000 ? (int) Math.round(d) : -1;
        } catch (RuntimeException e) {
            Quiet.warn("OwnerFix.intOr failed, carrying on with the fallback", e);
            return -1;
        }
    }

    /** What the companion report is right now. NONE is a fact (no companion runs), not a failure. */
    public enum State { REPORT, NONE, NOT_LOADED, BROKEN }

    /** A parse: the fix, or the reason there is none. */
    record Parsed(Fix fix, String reason) {}

    /** owner.json's text as a Fix, or the reason it is not one (not JSON, a field missing or wrong, a non-finite number). */
    static Parsed parsed(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = o.get("name").getAsString(), dim = o.get("dim").getAsString();
            double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble(), z = o.get("z").getAsDouble();
            long received = o.get("received").getAsLong();
            if (name.isEmpty() || dim.isEmpty()) return new Parsed(null, "name or dim is empty");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return new Parsed(null, "a coordinate is not a finite number");
            return new Parsed(new Fix(name, x, y, z, dim, received, intOr(o, "food"), intOr(o, "health")), null);
        } catch (RuntimeException e) {
            return new Parsed(null, "not a usable report: " + e);
        }
    }

    /** owner.json's text as a Fix; null when it is not one (current() says why, once). */
    public static Fix parse(String json) { return parsed(json).fix(); }

    private final Supplier<Path> file;
    private final Consumer<String> say;
    private long mtime = Long.MIN_VALUE, size = -1;
    private Fix cached;
    private State state = State.NOT_LOADED;
    private String said;   // the last line said, so the same text is not said twice

    /** @param file where owner.json is (null while the bot's folder is not known yet) */
    public OwnerFix(Supplier<Path> file) { this(file, Quiet::line); }

    OwnerFix(Supplier<Path> file, Consumer<String> say) {
        this.file = file;
        this.say = say;
    }

    public synchronized State state() { return state; }

    /** The fix for {@code player} as block coordinates, or null (no file, another player, stale, other dimension). */
    public int[] pos(String player, String botDim) {
        return pos(current(), player, botDim, System.currentTimeMillis());
    }

    /** The rules, without the file: null unless the fix is for this player, fresh, and in this dimension. */
    public static int[] pos(Fix f, String player, String botDim, long nowMs) {
        if (f == null || player == null || botDim == null || !f.name().equalsIgnoreCase(player)) return null;
        if (!botDim.equals(f.dim())) return null;
        long age = nowMs - f.received();
        if (age > maxAgeMs() || age < -futureSlackMs()) return null;
        return new int[]{(int) Math.floor(f.x()), (int) Math.floor(f.y()), (int) Math.floor(f.z())};
    }

    private void set(State s, String line) {
        state = s;
        if (!line.equals(said)) {
            said = line;
            say.accept(line);
        }
    }

    /**
     * The file's fix, cached by mtime and size. No file is state NONE (said once, and again only if the file appears and
     * goes); a file that exists but is not a report is BROKEN (said once per change of its reason) and the last good fix
     * stays (age still rules). Returns the cached fix in every state.
     */
    public synchronized Fix current() {
        Path p = file.get();
        if (p == null) {
            state = State.NOT_LOADED;
            return cached;
        }
        if (!Files.exists(p)) {
            mtime = Long.MIN_VALUE;
            size = -1;
            set(State.NONE, "no companion report (entropybot/owner.json): the owner's position comes from sight only");
            return cached;
        }
        try {
            long m = Files.getLastModifiedTime(p).toMillis(), s = Files.size(p);
            if (m == mtime && s == size) return cached;
            Parsed r = parsed(Files.readString(p, StandardCharsets.UTF_8));
            mtime = m;
            size = s;
            if (r.fix() != null) {
                cached = r.fix();
                set(State.REPORT, "companion report found (entropybot/owner.json)");
            } else {
                set(State.BROKEN, "companion report entropybot/owner.json is broken: " + r.reason());
            }
        } catch (IOException e) {
            set(State.BROKEN, "companion report entropybot/owner.json cannot be read: " + e);
        }
        return cached;
    }
}