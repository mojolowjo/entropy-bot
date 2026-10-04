package io.github.mojolowjo.entropybot.altar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Package E: what the bot put on the altar and its pedestals itself, kept in commands.json ("altar") so a stopped or
 * crashed infuse knows its own items afterwards: {@code {"placed": {"x y z": "item id"}, "pressed": "seed id"}}.
 * Only a slot that still holds the very item noted here counts as the bot's; anything else on a pedestal is somebody
 * else's and is never taken off. A note never outlives one look at an empty slot ({@link AltarPlan#survey} forgets
 * it), and "pressed" is dropped once neither the bot's input nor an output is on the altar. The notes made before a
 * click (place, pressed) are written to disk at once (commands.json flushed), so a crash right after a click can't
 * lose them.
 */
public final class AltarMemory {
    private final JsonObject root;
    private final Runnable saved, flush;

    /** {@code root}: commands.json's data (the "altar" object is made on the first write); {@code saved}: marks it changed. */
    public AltarMemory(JsonObject root, Runnable saved) {
        this(root, saved, saved);
    }

    /** {@code flush}: writes the file now (used for the notes made right before a click). */
    public AltarMemory(JsonObject root, Runnable saved, Runnable flush) {
        this.root = root;
        this.saved = saved == null ? () -> {} : saved;
        this.flush = flush == null ? this.saved : flush;
    }

    private JsonObject altar(boolean make) {
        JsonElement e = root.get("altar");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        if (!make) return null;
        JsonObject o = new JsonObject();
        root.add("altar", o);
        return o;
    }

    private JsonObject placedMap(boolean make) {
        JsonObject a = altar(make);
        if (a == null) return null;
        JsonElement e = a.get("placed");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        if (!make) return null;
        JsonObject o = new JsonObject();
        a.add("placed", o);
        return o;
    }

    static String key(int[] pos) { return pos[0] + " " + pos[1] + " " + pos[2]; }

    /** The item the bot put at pos, or null. */
    public String placed(int[] pos) {
        JsonObject m = placedMap(false);
        if (m == null) return null;
        JsonElement e = m.get(key(pos));
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Noted before the click (a crash between the click and the note never leaves an item of ours unknown). */
    public void place(int[] pos, String id) {
        placedMap(true).addProperty(key(pos), id);
        flush.run();
    }

    public void forget(int[] pos) {
        JsonObject m = placedMap(false);
        if (m != null && m.remove(key(pos)) != null) saved.run();
    }

    /** The seed the bot pressed the button for and hasn't taken yet (the altar's output is then the bot's), or null. */
    public String pressed() {
        JsonObject a = altar(false);
        JsonElement e = a == null ? null : a.get("pressed");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public void pressed(String seed) {
        if (seed == null) {
            JsonObject a = altar(false);
            if (a != null && a.remove("pressed") != null) saved.run();
            return;
        }
        altar(true).addProperty("pressed", seed);
        flush.run();
    }

    /** How many slots are noted as the bot's (for the tests and "nothing left behind"). */
    public int count() {
        JsonObject m = placedMap(false);
        return m == null ? 0 : m.size();
    }
}
