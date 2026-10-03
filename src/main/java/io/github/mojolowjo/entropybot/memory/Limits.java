package io.github.mojolowjo.entropybot.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How much the bot remembers and queues (package H, 2026-10-03; TO-LOOK-AT-LATER item 12). Each cap sits just below
 * the size where something got slow or too long in the measurements ({@code LimitsStressTest}, run with
 * {@code gradlew test -Pstress}; the numbers are in docs/wave1-h.md of the bot repo). Budgets: a file written on the
 * client tick thread under ~10 ms (a 60 fps frame is 16 ms), per-tick work well under 1 ms, a chat answer at most
 * {@link #WHISPER_PARTS} whispers (one goes out every 1.25 s), state.json well under 64 KB.
 *
 * <p>Over a cap the bot either drops the oldest (notes it can see again: chest notes, Refined Storage readings,
 * caves, points of interest) or refuses and says the limit (what the owner names: places, routines, rules, areas,
 * protect boxes, allowed players, supplies, chain steps). The bridge script keeps the same numbers ({@code LIMITS}).
 *
 * <p>Caps kept where they already were: points of interest {@code Pois.MAX} 500 (oldest dropped), the event ring
 * {@code EventRing.CAPACITY} 300, the guard's veto log {@code VetoLog.CAPACITY} 50, the autominer's log 20 decisions
 * (each result at most 1000 characters), deaths: the last hour only.
 */
public final class Limits {
    private Limits() {}

    /** Named places ("mark"): a new name past this is refused (the "places" answer is ~3 whispers at 100). */
    public static final int PLACES = 100;
    /** Chest notes: past this the oldest-seen note goes (never an untrusted one). chests.json ~240 KB, its write ~9 ms at 500; 10-18 ms at 1000. */
    public static final int CHESTS = 500;
    /** Refined Storage readings (one per grid): past this the oldest goes. */
    public static final int RS_READINGS = 8;
    /** Caves: past this the oldest finished cave goes (then the oldest). */
    public static final int CAVES = 64;
    /** Coarse cells of every cave together: caves.json is rewritten every 5 s while caving (~275 KB, ~8 ms at 20000; 550 KB, 16 ms at 40000). */
    public static final int CAVE_CELLS = 20000;
    /** Cells of one cave: above what a search bounded to 48 blocks from the entrance can visit (~9000). */
    public static final int CAVE_CELLS_EACH = 12000;
    /** Routines: a new name past this is refused (the list is ~3 whispers, state.json's settings ~13 KB at 100). */
    public static final int ROUTINES = 100;
    /** Characters of one routine's or rule's commands. */
    public static final int ROUTINE_TEXT = 1000;
    /** Rules: "rules" lists every rule whole, ~2 whispers per 10 rules. */
    public static final int RULES = 20;
    /** Steps of one chain after routines are expanded (3 levels of 20 would be 8000, a 62 KB "started" answer). */
    public static final int CHAIN_STEPS = 100;
    /** Steps a "started"/"saved" answer names before "+N more". */
    public static final int LIST_STEPS = 12;
    /** Areas and protect boxes: Baritone asks the guard for every node of a path (100k checks: 7 ms at 32+32, 19 ms at 128+128). */
    public static final int AREAS = 32, PROTECT = 64;
    /** Players on the allow list. */
    public static final int ALLOWED = 20;
    /** Items in "supplies" (each is checked at every autominer decision and restock). */
    public static final int SUPPLIES = 32;
    /** Whispers one answer may take (~2000 characters, 12 s of chat); the rest is cut with a note. */
    public static final int WHISPER_PARTS = 10;
    /** Whispers waiting to go out (75 s at one per 1.25 s; "help" alone is ~25); past this new ones are dropped. */
    public static final int OUTBOX = 60;
    /** Older decisions in "why" (the latest is shown whole). */
    public static final int WHY_OLDER = 160;
    /**
     * For package D (remembered furnace jobs, not built yet): one job per furnace the bot uses, at most 16 jobs, each
     * dropped 30 minutes after its output should have been collected (a few hundred bytes each in commands.json).
     */
    public static final int FURNACE_JOBS = 16;

    /** A chest note's "seen" (0 when missing or odd). */
    static long seen(JsonObject note) {
        try {
            return note != null && note.has("seen") ? note.get("seen").getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static boolean untrusted(JsonObject note) {
        JsonElement t = note == null ? null : note.get("trusted");
        try {
            return t != null && t.isJsonPrimitive() && !t.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The keys to drop so the map holds at most {@code max} notes: the oldest "seen" first, never an untrusted note
     * (the owner's "untrust" must stick) nor {@code keep} (the note just written). Empty when nothing has to go.
     */
    public static List<String> oldest(Map<String, JsonObject> m, int max, String keep) {
        List<String> out = new ArrayList<>();
        int over = m.size() - max;
        if (over <= 0) return out;
        List<Map.Entry<String, JsonObject>> c = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : m.entrySet()) if (!e.getKey().equals(keep) && !untrusted(e.getValue())) c.add(e);
        c.sort((a, b) -> Long.compare(seen(a.getValue()), seen(b.getValue())));
        for (int i = 0; i < over && i < c.size(); i++) out.add(c.get(i).getKey());
        return out;
    }

    /**
     * The refusal for a new entry past a cap, or null: {@code known} (replacing one) and room left pass.
     * "error: I keep at most 100 places - forget one first (forget &lt;name&gt;; "places" lists them)".
     */
    public static String full(boolean known, int size, int max, String what, String free) {
        if (known || size < max) return null;
        return "error: I keep at most " + max + " " + what + " - " + free;
    }

    /** At most {@code max} whisper parts: past it the last kept part says how many were cut. */
    public static List<String> capParts(List<String> parts, int max) {
        if (parts.size() <= max) return parts;
        List<String> out = new ArrayList<>(parts.subList(0, max - 1));
        out.add("(+" + (parts.size() - max + 1) + " more lines cut - too long for chat; send it from the dashboard to see all of it)");
        return out;
    }

    /** "a > b > c" with at most LIST_STEPS names, then "> ... (+N more, M steps)". */
    public static String stepsText(List<String> steps) {
        if (steps.size() <= LIST_STEPS) return String.join(" > ", steps);
        return String.join(" > ", steps.subList(0, LIST_STEPS)) + " > ... (+" + (steps.size() - LIST_STEPS) + " more, " + steps.size() + " steps)";
    }
}
