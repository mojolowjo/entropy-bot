package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.brain.FailMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.36.a8 (MINING_PLAN 3.6, phase 4): the option scorer inside AA's IRON rung. The planner still says <i>what</i>
 * ({@code mine iron N then smelt raw_iron N then craft iron_pickaxe 1 ...}); the scorer replaces the <i>how</i> of the
 * ore step and of the smelt step from the facts the adapter reads ({@link Facts}, a {@code Supplier} on the blackboard
 * under {@link #FACTS}) and from what failed here before ({@link Memory}):
 *
 * <table>
 * <tr><th>Way</th><th>When</th><th>Cost (blocks of walking)</th><th>Runs</th></tr>
 * <tr><td>WALK_KNOWN</td><td>a known ore with a dry open face</td><td>distance x1 sky-open / x2 cave, x1.5 not loaded, + the clear</td><td>{@code mine iron N}</td></tr>
 * <tr><td>DIG_KNOWN</td><td>a buried known ore under at most {@value #DIG_MAX} blocks, not a dig down from in the water</td><td>distance + {@value #DIG_BLOCK} per block + the clear</td><td>{@code mine iron N dig}</td></tr>
 * <tr><td>PILLAR_KNOWN</td><td>a known ore {@value #PILLAR_MIN_UP}+ above me, enough throwaway blocks, placing allowed</td><td>distance + {@value #PLACE_BLOCK} per block up + the clear</td><td>{@code goto <over the ore> then mine iron N dig} (a TRAVEL walk places by the policy's costs: refilling my own shaft is nearly free)</td></tr>
 * <tr><td>CAVE</td><td>a known cave with frontier left</td><td>distance to its entrance + {@value #CAVE_TIME}</td><td>{@code mine cave iron N}</td></tr>
 * <tr><td>STRIP</td><td>a mine marked, inside my areas</td><td>{@value #STRIP_TIME}</td><td>{@code mine strip iron N}</td></tr>
 * </table>
 * Score = cost x (1 + failures of that option here); an option the policy's floor denies, or whose failure is still
 * remembered ({@link #RETRY_MS} per failure), is dropped with its reason. The nearest ores are each an option of their own,
 * so "give up this ore, pick another" is the next row. Smelting: at the camp furnace when it is within {@value #SMELT_WALK},
 * else on the spot ({@code smelt raw_iron N here}: the furnace from the bag, or one crafted from 8 cobblestone with the spare
 * table) when the bag allows it.
 *
 * <p>A task that ends failed is classified ({@link #switchable}): a precondition or a mining end ("none I could reach",
 * "in the water", "no path", "no progress"...) is recorded against its option and the rung switches to the next best at
 * once (at most {@link #MAX_SWITCHES} per task) instead of failing and cooling down; the owner's stop, a fight or a craft
 * failure end the rung as before. {@link #why()} is the options line for {@code aa why}. Pure (JUnit: IronOptionsTest).
 * Loader notes: none (the facts come from the adapter, {@code AaRuntime.ironFacts}).
 */
public final class IronOptions {
    private IronOptions() {}

    /** The blackboard fact: a {@code Supplier<Facts>} the adapter sets (none: the planner's chain runs as it is). */
    public static final String FACTS = "iron.facts";
    public static final String FAMILY = "iron";

    public enum Way { WALK_KNOWN, DIG_KNOWN, PILLAR_KNOWN, CAVE, STRIP }

    public enum Smelt { CAMP, HERE }

    /**
     * One known ore as the adapter sees it.
     *
     * @param openFace   a dry air cell touches it (walkable to)
     * @param skyOpen    one of its open neighbours sees the sky
     * @param depth      blocks from the surface down to it (1 = at the surface), -1 unknown (not loaded)
     * @param wetDigDown the water rule: I am in water and would have to dig down to it
     * @param deny       the policy's reason against breaking it under MINE (null = allowed); floor = never
     */
    public record Ore(int x, int y, int z, double dist, boolean loaded, boolean openFace, boolean skyOpen, int depth, boolean wetDigDown, String deny, boolean floor) {
        public String key() { return x + " " + y + " " + z; }
    }

    /**
     * @param caveDist     blocks to the nearest known cave entrance with frontier left, -1 none
     * @param stripAllowed a mine is marked and I stand in my areas; else {@code stripWhy} says why not
     * @param furnaceDist  blocks to the nearest known furnace (the camp's), -1 none
     */
    public record Facts(List<Ore> ores, int meY, boolean inWater, int throwaway, boolean placeAllowed, int caveDist,
                        boolean stripAllowed, String stripWhy, int furnaceDist, boolean furnaceInBag, boolean tableInBag, int cobble) {}

    public static final double CLEAR = 10, DIG_BLOCK = 3, PLACE_BLOCK = 5, CAVE_TIME = 120, STRIP_TIME = 200, STALE = 1.5, CAVE_FACE = 2;
    public static final int DIG_MAX = 16, PILLAR_MIN_UP = 3, SMELT_WALK = 24, FURNACE_COBBLE = 8, SMELT_HERE_COST = 10, DEFAULT_N = 3;
    /** Switches within one task before the rung fails as before. */
    public static final int MAX_SWITCHES = 4;
    /** A failed option is skipped this long per failure (at most an hour). */
    public static final long RETRY_MS = 10 * 60_000, RETRY_MAX_MS = 60 * 60_000;

    public record Scored(Way way, Ore target, double cost, String why) {
        String text() { return way.name().toLowerCase(Locale.ROOT) + (target == null ? "" : " " + target.key()) + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** The chosen way (null: none can run), its smelt choice, the line for {@code aa why}. */
    public record Choice(Way way, Ore target, Smelt smelt, List<Scored> ranked, List<String> dropped) {
        public String why() {
            StringBuilder b = new StringBuilder();
            if (way == null) b.append("no way to iron");
            else b.append(ranked.get(0).text());
            if (ranked.size() > 1) {
                b.append("; then ");
                for (int i = 1; i < ranked.size() && i < 4; i++) b.append(i > 1 ? ", " : "").append(ranked.get(i).text());
            }
            if (!dropped.isEmpty()) b.append("; dropped: ").append(String.join(", ", dropped));
            b.append("; smelt ").append(smelt.name().toLowerCase(Locale.ROOT));
            return b.toString();
        }

        /** The ore step for the chosen way. */
        public String command(int n) {
            return switch (way) {
                case WALK_KNOWN -> "mine " + FAMILY + " " + n;
                case DIG_KNOWN -> "mine " + FAMILY + " " + n + " dig";
                case PILLAR_KNOWN -> "goto " + target.x() + " " + (target.y() + 1) + " " + target.z() + " then mine " + FAMILY + " " + n + " dig";
                case CAVE -> "mine cave " + FAMILY + " " + n;
                case STRIP -> "mine strip " + FAMILY + " " + n;
            };
        }
    }

    // ---- the memory of what failed here ----

    /** (way, target) -> failures, skipped until, the last reason. In memory only. */
    public static final class Memory {
        record Rec(int fails, long until, String reason) {}

        private final Map<String, Rec> map = new LinkedHashMap<>();

        /**
         * Review: {@code mine iron N} (walk or dig) picks its own ore, so a failure of those two ways counts against the
         * way, not the scorer's guessed target; the pillar's goto names its ore, so it is remembered per ore.
         */
        static String key(Way w, Ore o) {
            if (w == null) return "smelt here";
            return w + (o == null || w == Way.WALK_KNOWN || w == Way.DIG_KNOWN ? "" : " " + o.key());
        }

        public void failed(Way w, Ore o, String reason, long now) {
            Rec r = map.get(key(w, o));
            int n = r == null ? 1 : r.fails + 1;
            map.put(key(w, o), new Rec(n, now + Math.min(RETRY_MAX_MS, RETRY_MS * n), reason));
        }

        /** Smelting on the spot failed: back to the camp for a while. */
        public void smeltHereFailed(String reason, long now) { failed(null, null, reason, now); }

        public int fails(Way w, Ore o) {
            Rec r = map.get(key(w, o));
            return r == null ? 0 : r.fails;
        }

        public boolean blocked(Way w, Ore o, long now) {
            Rec r = map.get(key(w, o));
            return r != null && r.until > now;
        }

        public String reason(Way w, Ore o) {
            Rec r = map.get(key(w, o));
            return r == null ? null : r.reason;
        }

        public int size() { return map.size(); }

        public String describe(long now) {
            List<String> w = new ArrayList<>();
            for (Map.Entry<String, Rec> e : map.entrySet())
                w.add(e.getKey() + " x" + e.getValue().fails + (e.getValue().until > now ? " (" + Math.max(1, (e.getValue().until - now + 59_999) / 60_000) + " min left)" : ""));
            return String.join(", ", w);
        }
    }

    // ---- the scorer ----

    public static Choice choose(Facts f, Memory m, long now) {
        List<Scored> ranked = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Ore above = null;
        for (Ore o : f.ores()) {
            if (o.floor()) { dropped.add(o.key() + ": " + o.deny()); continue; }
            if (o.deny() != null) { dropped.add(o.key() + ": " + o.deny()); continue; }
            if (o.openFace()) {
                if (m.blocked(Way.WALK_KNOWN, o, now)) dropped.add("walk " + o.key() + ": " + m.reason(Way.WALK_KNOWN, o));
                else ranked.add(scored(Way.WALK_KNOWN, o, o.dist() * (o.skyOpen() ? 1 : CAVE_FACE) * (o.loaded() ? 1 : STALE) + CLEAR,
                        o.loaded() ? (o.skyOpen() ? "open to the sky" : "an open face in a cave") : "known from before, not loaded", m));
            } else if (o.wetDigDown()) dropped.add("dig " + o.key() + ": digging down from in the water");
            else if (!o.loaded()) dropped.add("dig " + o.key() + ": buried, not loaded");
            else if (o.depth() > DIG_MAX) dropped.add("dig " + o.key() + ": " + o.depth() + " blocks deep (dig up to " + DIG_MAX + ")");
            else if (m.blocked(Way.DIG_KNOWN, o, now)) dropped.add("dig " + o.key() + ": " + m.reason(Way.DIG_KNOWN, o));
            else ranked.add(scored(Way.DIG_KNOWN, o, o.dist() + Math.max(1, o.depth()) * DIG_BLOCK + CLEAR, "buried " + o.depth() + " deep", m));
            int up = o.y() - f.meY();
            if (up >= PILLAR_MIN_UP && above == null) above = o;
            if (up >= PILLAR_MIN_UP && !m.blocked(Way.PILLAR_KNOWN, o, now)) {
                if (!f.placeAllowed()) { if (above == o) dropped.add("pillar " + o.key() + ": placing not allowed here"); }
                else if (f.throwaway() < up) { if (above == o) dropped.add("pillar " + o.key() + ": " + f.throwaway() + " of " + up + " blocks to place"); }
                else ranked.add(scored(Way.PILLAR_KNOWN, o, o.dist() + up * PLACE_BLOCK + CLEAR, up + " up, " + f.throwaway() + " blocks to place", m));
            } else if (up >= PILLAR_MIN_UP) dropped.add("pillar " + o.key() + ": " + m.reason(Way.PILLAR_KNOWN, o));
        }
        if (f.caveDist() >= 0) {
            if (m.blocked(Way.CAVE, null, now)) dropped.add("cave: " + m.reason(Way.CAVE, null));
            else ranked.add(scored(Way.CAVE, null, f.caveDist() + CAVE_TIME, "a known cave " + f.caveDist() + " away", m));
        } else dropped.add("cave: none known");
        if (f.stripAllowed()) {
            if (m.blocked(Way.STRIP, null, now)) dropped.add("strip: " + m.reason(Way.STRIP, null));
            else ranked.add(scored(Way.STRIP, null, STRIP_TIME, "a mine is marked", m));
        } else dropped.add("strip: " + (f.stripWhy() == null ? "not allowed" : f.stripWhy()));
        ranked.sort((a, b) -> Double.compare(a.cost(), b.cost()));
        Smelt smelt = smeltWhere(f, m, now);
        if (ranked.isEmpty()) return new Choice(null, null, smelt, ranked, dropped);
        return new Choice(ranked.get(0).way(), ranked.get(0).target(), smelt, ranked, dropped);
    }

    private static Scored scored(Way w, Ore o, double cost, String why, Memory m) {
        int fails = m.fails(w, o);
        return new Scored(w, o, cost * (1 + fails), fails > 0 ? why + ", failed " + fails + "x" : why);
    }

    /**
     * The camp furnace within {@link #SMELT_WALK}: there; else on the spot when a furnace is in the bag ({@code smelt ...
     * here}); else the camp (the planner's way). Review: a furnace crafted on the spot from cobble is not offered yet (the
     * smelt step would end "no furnace"); the table rung keeps a spare table, a spare furnace is the next step.
     */
    public static Smelt smeltWhere(Facts f, Memory m, long now) {
        if (!f.furnaceInBag() || m.blocked(null, null, now)) return Smelt.CAMP;
        if (f.furnaceDist() < 0) return Smelt.HERE;
        return f.furnaceDist() <= SMELT_WALK ? Smelt.CAMP : Smelt.HERE;
    }

    // ---- the chain rewrite ----

    private static final Pattern MINE_STEP = Pattern.compile("^mine\\s+(?:strip\\s+|cave\\s+)?(?:deepslate_)?iron(?:_ore)?(?:\\s+(\\d+))?\\b.*$");
    private static final Pattern SMELT_STEP = Pattern.compile("^smelt\\s+(?!collect\\b|jobs\\b|mode\\b|forget\\b)\\S+(?:\\s+\\d+)?$");

    /** The planner's steps with the ore step and the smelt step replaced by the choice; unchanged when no step matches. */
    public static String rewrite(List<String> steps, Choice c) { return rewrite(steps, c, false); }

    /** @param afterOre the ore step is done already (a smelt-on-the-spot failure): it is left out */
    public static String rewrite(List<String> steps, Choice c, boolean afterOre) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            String t = s.trim();
            Matcher mm = MINE_STEP.matcher(t.toLowerCase(Locale.ROOT));
            if (afterOre && mm.matches()) continue;
            if (c.way() != null && mm.matches()) {
                int n = mm.group(1) == null ? DEFAULT_N : Integer.parseInt(mm.group(1));
                out.add(c.command(n));
            } else if (c.smelt() == Smelt.HERE && SMELT_STEP.matcher(t.toLowerCase(Locale.ROOT)).matches()) out.add(t + " here");
            else out.add(t);
        }
        return String.join(" then ", out);
    }

    /** The chain holds an ore step (else the scorer has nothing to choose, the smelt choice still applies). */
    public static boolean hasMineStep(List<String> steps) {
        for (String s : steps) if (MINE_STEP.matcher(s.trim().toLowerCase(Locale.ROOT)).matches()) return true;
        return false;
    }

    public static List<String> split(String chain) {
        List<String> out = new ArrayList<>();
        for (String s : (chain == null ? "" : chain).split("(?i)\\s+then\\s+")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    // ---- what failed, and may the rung switch ----

    private static final Pattern STEP = Pattern.compile("^stopped at step \\d+ \\(([^)]*)\\)");
    private static final Pattern MINE_END = Pattern.compile("none i could reach|out of reach|buried|in the water|too deep|no progress|no path|stuck|couldn't get to|"
            + "no known cave|no mine marked|ore known|nothing but water|walked \\d+ block|no .* ore (known|listed)|try \\d+/\\d+|cave .* (done|finished|explored)");

    /** The step the chain stopped at ("stopped at step 2 (mine iron 3 dig): ..."), or null. */
    public static String failedStep(String end) {
        if (end == null) return null;
        Matcher m = STEP.matcher(end);
        return m.find() ? m.group(1).trim() : null;
    }

    /** "ore" (the mine/goto step or no step named), "smelt", or "other" (a craft, a fetch...). */
    public static String failedPart(String end) {
        String st = failedStep(end);
        if (st == null) return "ore";
        String w = st.toLowerCase(Locale.ROOT);
        if (w.startsWith("mine") || w.startsWith("goto")) return "ore";
        if (w.startsWith("smelt")) return "smelt";
        return "other";
    }

    /** A failure the scorer may answer with another option: a precondition (FailMemory's classes) or a mining end; never the owner's stop or a fight. */
    public static boolean switchable(String end) {
        if (end == null) return false;
        String part = failedPart(end);
        if (part.equals("other")) return false;        // a craft or fetch step: no other way mines it
        String t = end.toLowerCase(Locale.ROOT);
        if (t.contains("stopped: your") || t.contains("overridden") || t.contains("interrupted") || t.contains("attacked") || t.contains("low health") || t.contains("died")) return false;
        if (part.equals("smelt")) return true;         // the smelt on the spot did not work out: the camp furnace is the other way
        return FailMemory.classify(end) != null || MINE_END.matcher(t).find();
    }

    // ---- the rung's picker ----

    /** The IRON rung's {@link Rung.Picker}: facts in, the rewritten chain out; failures feed the memory. */
    public static final class Picker implements Rung.Picker {
        final Memory memory = new Memory();
        private Choice last;
        private String why = "no choice yet";
        private int switches;
        private boolean switching, afterOre;

        @Override public String pick(Blackboard bb, String planned) {
            Object sup = bb.fact(FACTS);
            if (!switching) switches = 0;
            boolean skipOre = switching && afterOre;        // the smelt on the spot failed: the ore is mined, go on with the camp
            switching = false;
            afterOre = false;
            if (!(sup instanceof Supplier<?> s)) {
                why = "no iron facts from the adapter: the planner's chain as it is";
                last = null;
                return planned;
            }
            Object fo = s.get();
            if (!(fo instanceof Facts f)) {
                why = "iron facts not readable now: the planner's chain as it is";
                last = null;
                return planned;
            }
            List<String> steps = split(planned);
            Choice c = choose(f, memory, bb.clock.now());
            last = c;
            why = c.why();
            if (skipOre) {
                String rest = rewrite(steps, c, true);
                return rest.isEmpty() ? null : rest;
            }
            if (c.way() == null && hasMineStep(steps)) return null;
            return rewrite(steps, c);
        }

        @Override public boolean failed(Blackboard bb, String end) {
            if (last == null || !switchable(end) || switches >= MAX_SWITCHES) return false;
            long now = bb.clock.now();
            String part = failedPart(end);
            if (part.equals("smelt")) {
                if (last.smelt() != Smelt.HERE) return false;
                memory.smeltHereFailed(end, now);
                afterOre = true;
            } else if (part.equals("ore")) {
                if (last.way() == null) return false;
                memory.failed(last.way(), last.target(), end, now);
            } else return false;
            switches++;
            switching = true;
            return true;
        }

        @Override public String why() { return why; }

        public Choice last() { return last; }

        public int switches() { return switches; }
    }
}
