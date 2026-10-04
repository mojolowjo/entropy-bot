package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final long MAX_AGE_MS = 10_000;
    /** A fix "from the future" (the clock was set back) is refused beyond this. */
    static final long FUTURE_SLACK_MS = 2_000;

    /** One parsed owner.json. */
    public record Fix(String name, double x, double y, double z, String dim, long received) {}

    private final Supplier<Path> file;
    private long mtime = Long.MIN_VALUE, size = -1;
    private Fix cached;

    /** @param file where owner.json is (null while the bot's folder is not known yet) */
    public OwnerFix(Supplier<Path> file) {
        this.file = file;
    }

    /** The fix for {@code player} as block coordinates, or null (no file, another player, stale, other dimension). */
    public int[] pos(String player, String botDim) {
        return pos(current(), player, botDim, System.currentTimeMillis());
    }

    /** The rules, without the file: null unless the fix is for this player, fresh, and in this dimension. */
    public static int[] pos(Fix f, String player, String botDim, long nowMs) {
        if (f == null || player == null || botDim == null || !f.name().equalsIgnoreCase(player)) return null;
        if (!botDim.equals(f.dim())) return null;
        long age = nowMs - f.received();
        if (age > MAX_AGE_MS || age < -FUTURE_SLACK_MS) return null;
        return new int[]{(int) Math.floor(f.x()), (int) Math.floor(f.y()), (int) Math.floor(f.z())};
    }

    /** owner.json's text as a Fix; null when it is not JSON or lacks a field or has a non-finite number. */
    public static Fix parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = o.get("name").getAsString(), dim = o.get("dim").getAsString();
            double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble(), z = o.get("z").getAsDouble();
            long received = o.get("received").getAsLong();
            if (name.isEmpty() || dim.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return new Fix(name, x, y, z, dim, received);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The file's fix, cached by mtime and size; a half-written or missing file keeps the last good one (age still rules). */
    public synchronized Fix current() {
        Path p = file.get();
        if (p == null) return cached;
        try {
            long m = Files.getLastModifiedTime(p).toMillis(), s = Files.size(p);
            if (m == mtime && s == size) return cached;
            Fix f = parse(Files.readString(p, StandardCharsets.UTF_8));
            mtime = m;
            size = s;
            if (f != null) cached = f;
        } catch (IOException | RuntimeException e) {
            // no file yet, or caught mid-replace: the next call looks again
        }
        return cached;
    }
}