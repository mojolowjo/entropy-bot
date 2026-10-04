package io.github.mojolowjo.entropybot.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayList;
import java.util.List;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Package D (TO-LOOK-AT-LATER 10 and 11): the furnaces the bot has put something in, remembered across other work and
 * restarts (one job per furnace, at most {@link Limits#FURNACE_JOBS}), and the decisions at a furnace: is it busy with
 * someone else's smelting (never cleared, never added to), and what to do when the bot comes back to collect (take
 * what is done, wait, done, or the output is gone). No Minecraft types: the wiring reads the furnace's slots into
 * {@link Slots}. Times are wall-clock milliseconds, so a job's due time means the same after a restart.
 *
 * <p>Stored in the commands store's object under {@code "furnaces"}: {@code {"next": 4, "jobs": [{...}]}}.
 */
public final class FurnaceJobs {
    /** A vanilla furnace: 10 s an item. */
    public static final long SMELT_MS = 10_000;
    /** Added to a job's due time (the first item lights the furnace, the server's tick rate). */
    public static final long SLACK_MS = 2_000;
    /** A job is forgotten this long after it was due (package H's proposal), counted from this session's start at the earliest. */
    public static final long EXPIRE_MS = 30 * 60_000L;
    /** A pickup that couldn't run (busy, failed) is tried again after this. */
    public static final long RETRY_MS = 5 * 60_000L;
    /** Past its due time, no new output for this long while input is left: the furnace has stopped (no fuel?). */
    public static final long STALL_MS = 60_000;
    /** Every furnace busy: look again after this many ticks (a minute), at most {@link #BUSY_ROUNDS} times. */
    public static final int BUSY_WAIT_TICKS = 1200;
    public static final int BUSY_ROUNDS = 3;
    /** Kinds: the "smelt" verb's own target (kept in the bag), or a craft plan's intermediate (put away when nobody needs it). */
    public static final String SMELT = "smelt", CRAFT = "craft";

    /** One remembered furnace job. */
    public static final class Job {
        public int id;
        public int[] pos;
        public String dim, item, input, fuel, why, kind;
        public int n, want, collected, fuelCount;
        public long startedAt, dueAt, nextTry;
        public boolean redone;
        /** Past due, input left, nothing new: no idle pickup tries it again (a manual "smelt collect" does). */
        public boolean stalled;

        public int remaining() { return Math.max(0, want - collected); }

        public boolean due(long now) { return now >= dueAt; }

        public String where() { return pos[0] + " " + pos[1] + " " + pos[2]; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            JsonArray p = new JsonArray();
            for (int v : pos) p.add(v);
            o.add("pos", p);
            o.addProperty("dim", dim);
            o.addProperty("item", item);
            o.addProperty("input", input);
            o.addProperty("n", n);
            o.addProperty("want", want);
            o.addProperty("collected", collected);
            if (fuel != null) o.addProperty("fuel", fuel);
            o.addProperty("fuelCount", fuelCount);
            o.addProperty("startedAt", startedAt);
            o.addProperty("dueAt", dueAt);
            if (nextTry > 0) o.addProperty("nextTry", nextTry);
            if (why != null) o.addProperty("why", why);
            o.addProperty("kind", kind);
            if (redone) o.addProperty("redone", true);
            if (stalled) o.addProperty("stalled", true);
            return o;
        }

        static Job fromJson(JsonObject o) {
            try {
                Job j = new Job();
                j.id = o.get("id").getAsInt();
                JsonArray p = o.getAsJsonArray("pos");
                j.pos = new int[]{p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()};
                j.dim = str(o, "dim", "minecraft:overworld");
                j.item = o.get("item").getAsString();
                j.input = str(o, "input", null);
                j.n = num(o, "n");
                j.want = num(o, "want");
                j.collected = num(o, "collected");
                j.fuel = str(o, "fuel", null);
                j.fuelCount = num(o, "fuelCount");
                j.startedAt = o.has("startedAt") ? o.get("startedAt").getAsLong() : 0;
                j.dueAt = o.has("dueAt") ? o.get("dueAt").getAsLong() : 0;
                j.nextTry = o.has("nextTry") ? o.get("nextTry").getAsLong() : 0;
                j.why = str(o, "why", null);
                j.kind = str(o, "kind", SMELT);
                j.redone = o.has("redone") && o.get("redone").getAsBoolean();
                j.stalled = o.has("stalled") && o.get("stalled").getAsBoolean();
                return j;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsString();
    }

    private static int num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private final JsonObject root;
    private final Runnable saved;
    private final List<Job> jobs = new ArrayList<>();
    private final List<Leftover> leftovers = new ArrayList<>();
    private int next = 1;

    /**
     * What a job the bot stopped tracking (expired, "smelt forget") may have left in a furnace: that much of that item
     * in its output is the bot's to clear, so the furnace doesn't look busy forever.
     */
    public record Leftover(int[] pos, String dim, String item, int count) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray p = new JsonArray();
            for (int v : pos) p.add(v);
            o.add("pos", p);
            o.addProperty("dim", dim);
            o.addProperty("item", item);
            o.addProperty("count", count);
            return o;
        }
    }

    /** Reads {@code root.furnaces} (a broken entry is skipped); {@code saved} is called after every change. */
    public FurnaceJobs(JsonObject root, Runnable saved) {
        this.root = root;
        this.saved = saved == null ? () -> {} : saved;
        JsonElement f = root.get("furnaces");
        if (f != null && f.isJsonObject()) {
            JsonObject o = f.getAsJsonObject();
            if (o.has("next")) next = Math.max(1, o.get("next").getAsInt());
            if (o.has("jobs") && o.get("jobs").isJsonArray()) {
                for (JsonElement e : o.has("leftovers") && o.get("leftovers").isJsonArray() ? o.getAsJsonArray("leftovers") : new JsonArray()) {
                    try {
                        JsonObject l = e.getAsJsonObject();
                        JsonArray p = l.getAsJsonArray("pos");
                        leftovers.add(new Leftover(new int[]{p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()}, str(l, "dim", "minecraft:overworld"),
                                l.get("item").getAsString(), num(l, "count")));
                    } catch (RuntimeException ignored) {
                        // a broken entry: skipped
                    }
                }
                for (JsonElement e : o.getAsJsonArray("jobs")) {
                    Job j = e.isJsonObject() ? Job.fromJson(e.getAsJsonObject()) : null;
                    if (j != null && jobs.size() < Limits.FURNACE_JOBS) {
                        jobs.add(j);
                        next = Math.max(next, j.id + 1);
                    }
                }
            }
        }
    }

    private void save() {
        JsonObject o = new JsonObject();
        o.addProperty("next", next);
        JsonArray a = new JsonArray();
        for (Job j : jobs) a.add(j.toJson());
        o.add("jobs", a);
        JsonArray l = new JsonArray();
        for (Leftover x : leftovers) l.add(x.toJson());
        o.add("leftovers", l);
        root.add("furnaces", o);
        saved.run();
    }

    public List<Job> all() { return List.copyOf(jobs); }

    public boolean isEmpty() { return jobs.isEmpty(); }

    public Job get(int id) {
        for (Job j : jobs) if (j.id == id) return j;
        return null;
    }

    /** Our job at this furnace, or null. */
    public Job at(int[] pos, String dim) {
        for (Job j : jobs) if (j.pos[0] == pos[0] && j.pos[1] == pos[1] && j.pos[2] == pos[2] && j.dim.equals(dim)) return j;
        return null;
    }

    public boolean full() { return jobs.size() >= Limits.FURNACE_JOBS; }

    /** The refusal when {@link #full()}: never a job dropped that still has items in a furnace. */
    public static String fullRefusal() {
        return "I remember " + Limits.FURNACE_JOBS + " furnace jobs already - \"smelt collect\" first (\"smelt jobs\" lists them)";
    }

    /** The due time of {@code n} items started at {@code now}. */
    public static long dueAt(long now, int n) {
        return now + n * SMELT_MS + SLACK_MS;
    }

    /** Remembers the smelt just put into the furnace at {@code pos}; null when {@link #full()} (or that furnace has one). */
    public Job add(int[] pos, String dim, Crafter.Smelt s, String kind, String why, long now) {
        if (full() || at(pos, dim) != null) return null;
        Job j = new Job();
        j.id = next++;
        j.pos = pos.clone();
        j.dim = dim;
        j.item = s.item();
        j.input = s.input();
        j.n = s.n();
        j.want = s.want();
        j.fuel = s.fuel();
        j.fuelCount = s.fuelCount();
        j.startedAt = now;
        j.dueAt = dueAt(now, s.n());
        j.kind = kind == null ? SMELT : kind;
        j.why = why;
        jobs.add(j);
        save();
        return j;
    }

    /** {@code n} more of the job's output taken; the job is forgotten once all of it is. */
    public void collected(Job j, int n) {
        if (n <= 0) return;
        j.collected += n;
        if (j.remaining() <= 0) jobs.remove(j);
        save();
    }

    public void remove(Job j) {
        if (jobs.remove(j)) save();
    }

    /** Stops tracking a job whose items may still be in the furnace: they are remembered as a {@link Leftover}. */
    public void forget(Job j) {
        if (!jobs.remove(j)) return;
        noteLeftover(j);
        save();
    }

    private void noteLeftover(Job j) {
        if (j.remaining() <= 0) return;
        leftovers.removeIf(l -> same(l.pos(), j.pos) && l.dim().equals(j.dim));
        leftovers.add(new Leftover(j.pos.clone(), j.dim, j.item, j.remaining()));
        while (leftovers.size() > Limits.FURNACE_JOBS) leftovers.remove(0);
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /** A job's leftover in the furnace at pos, or null. */
    public Leftover leftover(int[] pos, String dim) {
        for (Leftover l : leftovers) if (same(l.pos(), pos) && l.dim().equals(dim)) return l;
        return null;
    }

    /** The leftover was cleared out (or is gone): forget it. */
    public void clearLeftover(int[] pos, String dim) {
        if (leftovers.removeIf(l -> same(l.pos(), pos) && l.dim().equals(dim))) save();
    }

    /** The job stopped (past due, input left, nothing new): no idle pickup tries it again. */
    public void markStalled(Job j) {
        j.stalled = true;
        save();
    }

    /** A manual "smelt collect" tries stalled jobs again. */
    public void unstall(List<Job> js) {
        boolean any = false;
        for (Job j : js) {
            if (j.stalled) {
                j.stalled = false;
                any = true;
            }
        }
        if (any) save();
    }

    /** A pickup for these jobs didn't happen now: not again before {@link #RETRY_MS}. */
    public void later(List<Job> js, long now) {
        later(js, now, RETRY_MS);
    }

    /** Not again before {@code now + ms} (a bag that was full: half an hour). */
    public void later(List<Job> js, long now, long ms) {
        for (Job j : js) j.nextTry = now + ms;
        if (!js.isEmpty()) save();
    }

    /** Jobs in {@code dim} whose output is due and that may be picked up now. */
    public List<Job> due(long now, String dim) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs) if (j.dim.equals(dim) && j.due(now) && j.nextTry <= now && !j.stalled) out.add(j);
        return out;
    }

    /**
     * Forgets the jobs {@link #EXPIRE_MS} past due, counted from {@code sessionStart} at the earliest (after a night with
     * the game closed the bot gets its 30 minutes in game). Returns them, for a whisper.
     */
    public List<Job> expire(long now, long sessionStart) {
        List<Job> gone = new ArrayList<>();
        for (Job j : jobs) if (now > Math.max(j.dueAt, sessionStart) + EXPIRE_MS) gone.add(j);
        if (!gone.isEmpty()) {
            jobs.removeAll(gone);
            for (Job j : gone) noteLeftover(j);
            save();
        }
        return gone;
    }

    /** "12 iron_ingot in the furnace at -32 53 183 (30 min past due)": the owner's note for a forgotten job. */
    public static String forgotten(Job j) {
        return "I stopped tracking " + j.remaining() + " " + shortId(j.item) + " in the furnace at " + j.where()
                + " (30 min past due) - they may still be in it";
    }

    /** "1m 30s", "45s", "now". */
    public static String eta(long ms) {
        if (ms <= 0) return "now";
        long s = (ms + 999) / 1000;
        return s >= 60 ? (s / 60) + "m" + (s % 60 > 0 ? " " + (s % 60) + "s" : "") : s + "s";
    }

    /** The "smelt jobs" reply. */
    public String list(long now) {
        if (jobs.isEmpty()) return "no furnace jobs (nothing of mine is smelting)";
        List<String> parts = new ArrayList<>();
        for (Job j : jobs) {
            parts.add("#" + j.id + " " + j.want + " " + shortId(j.item) + " at " + j.where()
                    + (j.collected > 0 ? " (" + j.collected + " taken)" : "")
                    + (j.due(now) ? ", ready" : ", ready in " + eta(j.dueAt - now))
                    + (j.why != null ? ", for " + j.why : ""));
        }
        return "furnace jobs: " + String.join("; ", parts);
    }

    /** "started 40 raw_iron in the furnace at x y z, ready in 6m 42s": the note when a job starts. */
    public static String startedNote(Job j, long now) {
        return "started " + j.n + " " + shortId(j.input) + " in the furnace at " + j.where() + ", ready in " + eta(j.dueAt - now);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // at the furnace
    // ---------------------------------------------------------------------------------------------------------------

    /** What a furnace's three slots hold (null id = empty). */
    public record Slots(String input, int inputN, String fuel, int fuelN, String output, int outputN) {
        public static final Slots EMPTY = new Slots(null, 0, null, 0, null, 0);
    }

    /** Before putting anything in: free, our own leftover to take out first, or busy. */
    public enum Put { FREE, CLEAR_FIRST, BUSY }

    public record PutCheck(Put put, String why) {}

    /**
     * Before putting anything in, with {@code fuel} the fuel the plan brings and {@code lo} our leftover in this furnace
     * (or null): BUSY when someone else's input is in it, its output holds something that isn't our leftover, or its
     * fuel slot holds another fuel (never burned for us, never a plank or a bucket stalling our smelt); CLEAR_FIRST when
     * the output is only our own leftover; else FREE (a fuel slot with our fuel is topped up to the plan's count).
     */
    public static PutCheck check(Slots s, String fuel, Leftover lo) {
        if (s.input() != null && s.inputN() > 0) return new PutCheck(Put.BUSY, "it is smelting " + s.inputN() + " " + shortId(s.input()));
        if (s.fuel() != null && s.fuelN() > 0 && !s.fuel().equals(fuel)) {
            return new PutCheck(Put.BUSY, "its fuel slot holds " + s.fuelN() + " " + shortId(s.fuel()) + ", not my " + (fuel == null ? "fuel" : shortId(fuel)));
        }
        if (s.output() != null && s.outputN() > 0) {
            if (lo != null && lo.item().equals(s.output()) && s.outputN() <= lo.count()) {
                return new PutCheck(Put.CLEAR_FIRST, "my " + s.outputN() + " " + shortId(s.output()) + " from an earlier job wait in its output");
            }
            return new PutCheck(Put.BUSY, s.outputN() + " " + shortId(s.output()) + " wait in its output");
        }
        return new PutCheck(Put.FREE, null);
    }

    /** Why the furnace is busy for a smelt bringing {@code fuel} (no leftover of ours counted), or null. */
    public static String busy(Slots s, String fuel) {
        PutCheck c = check(s, fuel, null);
        return c.put() == Put.BUSY ? c.why() : c.put() == Put.CLEAR_FIRST ? c.why() : null;
    }

    /** What to do at a furnace the bot came back to. */
    public enum Next { TAKE, WAIT, DONE, GONE, STALLED }

    /** {@code take}: how many of the job's output to take now; {@code why}: for GONE and STALLED. */
    public record Collect(Next next, int take, String why) {}

    /**
     * One look at the furnace while collecting {@code target} for job {@code j} ({@code took} of them so far in this
     * visit). The output of the job is taken as it comes (early collection); the visit is done when {@code target} is
     * reached or the job is complete. GONE: neither the input nor our output is there any more (a player or a pipe
     * took them, or another smelt replaced them); STALLED: input left, past due and nothing new for {@link #STALL_MS}.
     */
    public static Collect collect(Slots s, Job j, int took, int target, long now, long lastProgressAt) {
        return collect(s, j, took, target, now, lastProgressAt, -1);
    }

    /**
     * As above, with {@code prevInput} the job's input count at the last look (-1: no look yet). At a shared furnace
     * the bot takes nothing that may not be its own: the input went up since the last look (someone added the same
     * ore), or input + output + what it already took is more than it put in, or another item is in the output -> GONE.
     * A TAKE takes only what the step still needs ({@code target - took}); the rest keeps smelting or waits.
     */
    public static Collect collect(Slots s, Job j, int took, int target, long now, long lastProgressAt, int prevInput) {
        if (j.remaining() <= 0 || took >= target) return new Collect(Next.DONE, 0, null);
        int perItem = j.n > 0 ? Math.max(1, j.want / j.n) : 1;
        int inN = s.input() != null && s.input().equals(j.input) ? s.inputN() : 0;
        int outN = s.output() != null && s.output().equals(j.item) ? s.outputN() : 0;
        if (s.output() != null && s.outputN() > 0 && outN == 0) {
            return new Collect(Next.GONE, 0, "its output holds " + s.outputN() + " " + shortId(s.output()) + ", not my " + shortId(j.item));
        }
        if (prevInput >= 0 && inN > prevInput) {
            return new Collect(Next.GONE, 0, "someone added " + (inN - prevInput) + " " + shortId(j.input) + " to it - I take nothing from a shared smelt");
        }
        if ((long) inN * perItem + outN + j.collected > j.want) {
            return new Collect(Next.GONE, 0, "it holds more " + shortId(j.input) + "/" + shortId(j.item) + " than I put in - someone else's is mixed in, I take nothing");
        }
        if (outN > 0) return new Collect(Next.TAKE, Math.min(outN, Math.min(j.remaining(), target - took)), null);
        boolean ourIn = inN > 0;
        if (ourIn) {
            if (now > j.dueAt + STALL_MS && now - lastProgressAt > STALL_MS) {
                return new Collect(Next.STALLED, 0, "it stopped with " + s.inputN() + " " + shortId(j.input) + " left to smelt (out of fuel?)");
            }
            return new Collect(Next.WAIT, 0, null);
        }
        if (s.input() != null && s.inputN() > 0) {
            return new Collect(Next.GONE, 0, "it is smelting " + s.inputN() + " " + shortId(s.input()) + " now, not my " + shortId(j.input));
        }
        return new Collect(Next.GONE, 0, "my " + j.remaining() + " " + shortId(j.item) + " are gone (input and output empty)");
    }

    /** Ticks to wait (furnace closed) before looking again: until it is due, at least 5 s, at most 30 s. */
    public static int lookAgainTicks(long msUntilDue) {
        long s = Math.max(5, Math.min(30, msUntilDue / 1000));
        return (int) (s * 20);
    }

    /** The collect status: "waiting for the furnace at x y z (iron_ingot 12/40, ready in 4m 40s)". */
    public static String waitStatus(Job j, long now) {
        return "waiting for the furnace at " + j.where() + " (" + shortId(j.item) + " " + j.collected + "/" + j.want
                + (j.due(now) ? ", any moment" : ", ready in " + eta(j.dueAt - now)) + ")";
    }
}
