package io.github.mojolowjo.entropybot.restore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.guard.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * P1: everything restore.json holds: the {@link Ledger}, the mode (auto: put back at a job's end; manual: only on
 * "restore now"; off: record only) and the build hints (a suggested protect box per spot, until the owner protects it
 * or dismisses it with "restore ignore x y z"). Loader-neutral; the game side reads and writes the file.
 */
public final class RestoreBook {
    public static final int MAX_HINTS = 100;

    public static final class BuildHint {
        public final String key, name, dim;
        public final int[] center, box;
        public final int count;
        public final long at;
        public boolean ignored;

        public BuildHint(String key, String name, String dim, int[] center, int[] box, int count, long at, boolean ignored) {
            this.key = key;
            this.name = name;
            this.dim = dim;
            this.center = center;
            this.box = box;
            this.count = count;
            this.at = at;
            this.ignored = ignored;
        }

        public String command() {
            return "area " + box[0] + " " + box[2] + " " + box[3] + " " + box[5] + " " + name + " safe " + box[1] + " " + box[4];
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("name", name);
            o.addProperty("dim", dim);
            JsonArray c = new JsonArray();
            for (int v : center) c.add(v);
            o.add("center", c);
            JsonArray b = new JsonArray();
            for (int v : box) b.add(v);
            o.add("box", b);
            o.addProperty("count", count);
            o.addProperty("at", at);
            o.addProperty("ignored", ignored);
            return o;
        }

        static BuildHint fromJson(JsonObject o) {
            JsonArray c = o.getAsJsonArray("center"), b = o.getAsJsonArray("box");
            int[] center = {c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()};
            int[] box = new int[6];
            for (int i = 0; i < 6; i++) box[i] = b.get(i).getAsInt();
            return new BuildHint(o.get("key").getAsString(), o.get("name").getAsString(), o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld",
                    center, box, o.has("count") ? o.get("count").getAsInt() : 0, o.has("at") ? o.get("at").getAsLong() : 0,
                    o.has("ignored") && o.get("ignored").getAsBoolean());
        }
    }

    public Ledger ledger = new Ledger();
    public String mode = "auto";
    public final List<BuildHint> hints = new ArrayList<>();

    /** A new hint for this spot; null when the spot already has one (once per spot, ignored or not). */
    public BuildHint addHint(BuildSpotter.Hint h, String dim, long now) {
        for (BuildHint o : hints) if (o.key.equals(h.key()) && o.dim.equals(dim)) return null;
        BuildHint b = new BuildHint(h.key(), h.name(), dim, h.center(), h.box(), h.count(), now, false);
        hints.add(b);
        while (hints.size() > MAX_HINTS) hints.remove(0);
        return b;
    }

    /** Dismisses the hint whose box holds x y z (or whose center is within 8); false when there is none. */
    public boolean ignore(int x, int y, int z) {
        boolean any = false;
        for (BuildHint b : hints) {
            boolean in = x >= b.box[0] && x <= b.box[3] && y >= b.box[1] && y <= b.box[4] && z >= b.box[2] && z <= b.box[5];
            boolean close = Math.abs(x - b.center[0]) <= 8 && Math.abs(y - b.center[1]) <= 8 && Math.abs(z - b.center[2]) <= 8;
            if ((in || close) && !b.ignored) {
                b.ignored = true;
                any = true;
            }
        }
        return any;
    }

    /** The hints still open: not dismissed, and no protect box holds their center. */
    public List<BuildHint> openHints(List<Box> protect) {
        List<BuildHint> out = new ArrayList<>();
        for (BuildHint b : hints) {
            if (b.ignored) continue;
            boolean covered = false;
            if (protect != null) for (Box p : protect) if (p.contains(b.dim, b.center[0], b.center[1], b.center[2])) covered = true;
            if (!covered) out.add(b);
        }
        return out;
    }

    public JsonObject toJson() {
        JsonObject o = ledger.toJson();
        o.addProperty("mode", mode);
        JsonArray h = new JsonArray();
        for (BuildHint b : hints) h.add(b.toJson());
        o.add("hints", h);
        return o;
    }

    public static RestoreBook fromJson(JsonObject o, StringBuilder note) {
        RestoreBook r = new RestoreBook();
        if (o == null) return r;
        r.ledger = Ledger.fromJson(o, Ledger.CAP, note);
        if (o.has("mode") && o.get("mode").getAsString().matches("auto|manual|off")) r.mode = o.get("mode").getAsString();
        if (o.has("hints") && o.get("hints").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("hints")) {
                try { r.hints.add(BuildHint.fromJson(e.getAsJsonObject())); } catch (RuntimeException ignored) {}
            }
        }
        return r;
    }
}
