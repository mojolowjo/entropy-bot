package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B7e package N (item 3): the {@code check} self-test. Pure rules on a small {@link State} the game fills in
 * ({@link SelfCheckLive}): what the bot lacks to work on its own, each line with the command that fixes it. The idle
 * check every 30 minutes whispers only what changed ({@link #diff}).
 */
public final class SelfCheck {
    private SelfCheck() {}

    /** A mine at or below this y walks over lava: lava fills the caves from y -55 down (the 0.12.0 night at -54). */
    public static final int LAVA_Y = -54;
    /** A tool with this share of its uses left or less is nearly broken. */
    public static final double WORN = 0.10;
    /** Free bag slots at or below this: nearly full (as the autominer's deposit). */
    public static final int BAG_LOW = 4;
    /** The companion's position older than this while the owner is online: stale. */
    public static final long COMPANION_STALE_MS = 120_000;

    /** A damageable item in the bag: id, uses left, uses in all. */
    public record Tool(String id, int left, int max) {}

    /**
     * What the rules look at. mine: the "mine" place {x, y, z} (null: none) with its dir; mineGaveUp: the last strip run's
     * end when it gave up (null: fine). supplies: id -> want. companionAgeMs: -1 when owner.json is missing.
     */
    public record State(boolean strict, int areas, boolean baseMarked, int baseChests, boolean foodChest, boolean homeSet,
                        int[] mine, String mineDir, String mineGaveUp, Map<String, Integer> supplies, int freeSlots,
                        List<Tool> tools, boolean ownerOnline, long companionAgeMs) {}

    /** One problem: a stable key (for the idle diff), what is wrong, and the command that fixes it (null: none). */
    public record Finding(String key, String text, String fix) {
        public String line() { return fix == null ? text : text + " - next: " + fix; }
    }

    /** Every problem in the state, most basic first. */
    public static List<Finding> run(State s) {
        List<Finding> out = new ArrayList<>();
        if (s.areas() <= 0) out.add(new Finding("areas", "no work areas: with the fence on I dig and build only near you (area near)","area here 60 <name>"));
        else if (!s.strict()) out.add(new Finding("logmode", "the fence is in log mode (the guard only notes what it would refuse)", "fence mode strict"));
        if (!s.baseMarked()) out.add(new Finding("base", "no base marked", "setbase (standing at the base)"));
        else if (s.baseChests() <= 0) out.add(new Finding("basechests", "no base chests scanned: deposit and crafting can't use them", "scan base"));
        if (!s.foodChest()) out.add(new Finding("food", "no food chest marked: when I run out I can't fetch food", "mark food (next to the food chest)"));
        if (!s.homeSet()) out.add(new Finding("home", "no home set: long trips back and retreats can't teleport", "sethome (with me at the base)"));
        if (s.mine() != null && s.mine().length >= 3 && s.mine()[1] <= LAVA_Y) {
            int[] m = s.mine();
            String dir = s.mineDir() == null ? "north" : s.mineDir();
            out.add(new Finding("minelava", "my mine at " + m[0] + " " + m[1] + " " + m[2] + " is at lava level (y " + LAVA_Y + " and below walk over lava)",
                    "mark mine a few levels up, e.g. mark mine " + m[0] + " " + (LAVA_Y + 4) + " " + m[2] + " " + dir));
        }
        if (s.mine() != null && s.mineGaveUp() != null) {
            String why = s.mineGaveUp().length() > 120 ? s.mineGaveUp().substring(0, 120) + "..." : s.mineGaveUp();
            out.add(new Finding("mineblocked", "my mine couldn't go on (" + why + ")", "stripmine turn left (or mark mine somewhere new)"));
        }
        Map<String, Integer> sup = s.supplies() == null ? Map.of() : s.supplies();
        if (want(sup, "iron_pickaxe") < 1) {
            out.add(new Finding("ironpick", "no iron pickaxe in my supplies: deepslate ores and diamonds need one", suppliesWithIronPick(sup)));
        }
        if (s.freeSlots() <= BAG_LOW) out.add(new Finding("bag", "my bag is nearly full (" + s.freeSlots() + " free slots)", "deposit"));
        Set<String> seen = new LinkedHashSet<>();
        for (Tool t : s.tools() == null ? List.<Tool>of() : s.tools()) {
            if (t.max() <= 0 || t.left() > t.max() * WORN || !seen.add(t.id())) continue;
            String id = Texts.shortId(t.id());
            out.add(new Finding("tool:" + id, "my " + id + " is nearly broken (" + t.left() + " of " + t.max() + " uses left)", "craft " + id));
        }
        if (s.ownerOnline() && s.companionAgeMs() > COMPANION_STALE_MS) {
            out.add(new Finding("companion", "the companion's position is " + Math.round(s.companionAgeMs() / 60000.0) + " min old though you are online (is the companion mod running?)",
                    null));
        }
        return out;
    }

    /** "check": every finding on a line of its own, or all good. */
    public static String report(List<Finding> f) {
        if (f.isEmpty()) return "check: all good (areas, base and its chests, food chest, home, mine, iron pickaxe supply, bag, tools, companion)";
        List<String> lines = new ArrayList<>();
        lines.add("check: " + f.size() + " thing" + (f.size() == 1 ? "" : "s") + " to fix");
        for (int i = 0; i < f.size(); i++) lines.add((i + 1) + ". " + f.get(i).line());
        return String.join("\n", lines);
    }

    /** The idle check's news: the findings whose key is new, and the keys that went away. */
    public record Diff(List<Finding> added, List<String> gone) {
        public boolean empty() { return added.isEmpty() && gone.isEmpty(); }

        /** The whisper ("self-check: ..." lines), or null when nothing changed. */
        public String text() {
            if (empty()) return null;
            List<String> lines = new ArrayList<>();
            for (Finding f : added) lines.add("self-check: " + f.line());
            if (!gone.isEmpty()) lines.add("self-check: fixed now: " + String.join(", ", gone));
            return String.join("\n", lines);
        }
    }

    /** 0.21.2: the near-me zone's findings for "check" (never whispered: not {@link #idleWorthy}). */
    public static List<Finding> nearFindings(long unknownMs, long errors, boolean ownerOnline) {
        List<Finding> out = new ArrayList<>();
        if (ownerOnline && unknownMs >= io.github.mojolowjo.entropybot.guard.NearZone.UNKNOWN_NOTE_MS) {
            out.add(new Finding("nearzone", "the near-me zone hasn't known where you are for " + unknownMs / 60000 + " min though you are online (out of view, no fresh companion position)",
                    "come into view, run the companion (docs/companion.md), or area near off"));
        }
        if (errors > 0) out.add(new Finding("nearzoneerr", "the near-me zone had " + errors + " errors (the game log has them)", "area near status"));
        return out;
    }

    // ---- 0.21.2: the quiet idle check ----

    /** Free bag slots at or below this: the idle check may whisper "bag" (check lists it from {@link #BAG_LOW}). */
    public static final int BAG_FULL = 2;
    /** At most one self-check whisper this often, across restarts (commands.json selfCheck.lastWhisper). */
    public static final long WHISPER_EVERY_MS = 30 * 60_000L;

    /**
     * What the idle check may whisper at all: the bag (when full), no base, no food chest; and gear (a worn tool, tool
     * care) only for an item the OWNER put in the supplies ({@link #ownerSupplyIds}; never the autominer's own defaults).
     * Everything else (areas, mode, home, the mine, the iron pickaxe advice, hooks, routing, the companion, the near-me
     * zone) is for {@code check} only.
     */
    public static boolean idleWorthy(Finding f, Set<String> supplyIds) {
        String k = f.key();
        if (k.equals("bag") || k.equals("base") || k.equals("food")) return true;
        String id = k.startsWith("tool:") ? k.substring(5) : k.startsWith("toolcare:") ? k.substring(9) : null;
        return id != null && supplyIds != null && supplyIds.contains(Texts.shortId(id));
    }

    /**
     * 0.21.2: the supplies the owner set, as short ids. marked: commands.json "suppliesOwner" (the ids "supplies set"
     * wrote; null when it was never written - supplies set before 0.21.2): then every supply but the autominer's own
     * defaults counts as the owner's. Only ids still in the supplies count.
     */
    public static Set<String> ownerSupplyIds(Set<String> supplies, List<String> marked, Set<String> autominerDefaults) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> m = null, defaults = new java.util.HashSet<>();
        if (marked != null) {
            m = new java.util.HashSet<>();
            for (String s : marked) m.add(Texts.shortId(s));
        }
        if (autominerDefaults != null) for (String s : autominerDefaults) defaults.add(Texts.shortId(s));
        for (String id : supplies == null ? Set.<String>of() : supplies) {
            String s = Texts.shortId(id);
            if (m != null ? m.contains(s) : !defaults.contains(s)) out.add(s);
        }
        return out;
    }

    /** The idle check's outcome: the one line to whisper (null: none), and the causes told so far (to keep). */
    public record Idle(String whisper, Set<String> told) {}

    /**
     * The idle check (pure). told: the causes already whispered (kept across restarts); a cause that cleared is
     * forgotten silently (no "fixed now" line), so it may be told again only after it came back. Findings are keyed by
     * cause (a changing count or uses-left never makes a new key). At most ONE line per call, and none within
     * {@link #WHISPER_EVERY_MS} of the last whisper (lastWhisperMs, -1 = never); the rest wait for a later call.
     */
    public static Idle idle(Set<String> told, List<Finding> now, Set<String> supplyIds, int freeSlots, long nowMs, long lastWhisperMs) {
        Set<String> keysNow = keys(now);
        Set<String> kept = new LinkedHashSet<>();
        for (String k : told == null ? Set.<String>of() : told) if (keysNow.contains(k)) kept.add(k);
        List<Finding> waiting = new ArrayList<>();
        for (Finding f : now) {
            if (!idleWorthy(f, supplyIds) || kept.contains(f.key())) continue;
            if (f.key().equals("bag") && freeSlots > BAG_FULL) continue;      // listed by check from 4, whispered from 2
            waiting.add(f);
        }
        if (waiting.isEmpty() || (lastWhisperMs >= 0 && nowMs - lastWhisperMs < WHISPER_EVERY_MS)) return new Idle(null, kept);
        Finding first = waiting.get(0);
        kept.add(first.key());
        String more = waiting.size() > 1 ? " (+" + (waiting.size() - 1) + " more: PM check)" : "";
        return new Idle("self-check: " + first.line() + more, kept);
    }

    public static Diff diff(Set<String> before, List<Finding> now) {
        Set<String> b = before == null ? Set.of() : before;
        List<Finding> added = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Finding f : now) {
            keys.add(f.key());
            if (!b.contains(f.key())) added.add(f);
        }
        List<String> gone = new ArrayList<>();
        for (String k : b) if (!keys.contains(k)) gone.add(k);
        return new Diff(added, gone);
    }

    public static Set<String> keys(List<Finding> f) {
        Set<String> out = new LinkedHashSet<>();
        for (Finding x : f) out.add(x.key());
        return out;
    }

    private static int want(Map<String, Integer> sup, String bare) {
        int n = 0;
        for (Map.Entry<String, Integer> e : sup.entrySet()) {
            String k = e.getKey();
            if (k.equals(bare) || k.endsWith(":" + bare)) n += e.getValue() == null ? 0 : e.getValue();
        }
        return n;
    }

    /** "supplies set" replaces the whole list: the command carries the current lines plus iron_pickaxe 1. */
    static String suppliesWithIronPick(Map<String, Integer> sup) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sup.entrySet()) {
            String id = Texts.shortId(e.getKey());
            if (id.equals("iron_pickaxe")) continue;
            parts.add(id + " " + e.getValue());
        }
        parts.add("iron_pickaxe 1");
        return "supplies set " + String.join(", ", parts);
    }
}
