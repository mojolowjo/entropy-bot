package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The 0.19.1 fight fixes without Minecraft types, so JUnit can pin them: which mob counts as a threat (the owner's
 * hostile list, retaliation against whatever just hit the bot, and the pets/players it never touches), when a stranger
 * that hits the bot is too strong to trade blows with, and the {@code defend hostile} verb with its file
 * ({@code entropybot\defence.json}). The game side is {@link Hostility}.
 */
public final class HostileRules {
    private HostileRules() {}

    public static final String FILE = "defence.json";
    /** At most this many ids on the list. */
    public static final int CAP = 100;
    /** Seeded when there is no file yet (the owner confirmed it hostile, 2026-10-05). */
    public static final List<String> DEFAULTS = List.of("arphex:spider_lunger");
    /** A mob that hit the bot counts as a threat for this long after its last hit (the "hurt" window). */
    public static final int RETALIATE_TICKS = ReflexRules.HURT_TICKS;
    /** At most this many attackers are remembered at once (oldest dropped). */
    public static final int MAX_ATTACKERS = 16;
    /** A stranger with this much max health or more is fled from, not fought (the combat plan's boss bar, 2.1). */
    public static final double STRONG_HP = 100;
    /** ... or with this many times the bot's own weapon hit. */
    public static final double STRONG_HITS = 10;

    private static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    /** Why a mob counts as a threat, or why not. */
    public enum Kind {
        PLAYER(false, "NOT counted: a player (never attacked)"),
        PROTECTED(false, "NOT counted: tamed/owned (protected, never attacked)"),
        HOSTILE_LIST(true, "counted: hostile list"),
        ENEMY(true, "counted: a hostile type (Enemy)"),
        ENEMY_NEUTRAL_HURT(true, "counted: a neutral monster while I was just hit"),
        RETALIATION(true, "counted: hurt by it (retaliation)"),
        PEACEFUL(false, "NOT counted: a villager or golem (never fought back)"),
        NEUTRAL(false, "NOT counted: NeutralMob (only after something hit me)"),
        NOT_HOSTILE(false, "NOT counted: not an Enemy and not on the hostile list (defend hostile add <id>)");

        private final boolean counts;
        private final String text;

        Kind(boolean counts, String text) {
            this.counts = counts;
            this.text = text;
        }

        public boolean counts() { return counts; }

        public String text() { return text; }
    }

    /**
     * The verdict for one living, alive mob. player/tamed/owned always win (the owner's pets are never attacked, on the
     * list or not); then the hostile list; then the old Enemy rule (a NeutralMob only right after a hit); then
     * retaliation (it hit the bot in the last {@link #RETALIATE_TICKS}), never against villagers and golems.
     */
    public static Kind classify(boolean player, boolean tame, boolean hasOwner, boolean onList, boolean enemy, boolean neutral,
                                boolean recentlyHurt, boolean hitMe, boolean peaceful) {
        if (player) return Kind.PLAYER;
        if (protectedMob(false, tame, hasOwner)) return Kind.PROTECTED;
        if (onList) return Kind.HOSTILE_LIST;
        if (enemy && !neutral) return Kind.ENEMY;
        if (enemy && recentlyHurt) return Kind.ENEMY_NEUTRAL_HURT;
        if (hitMe && !peaceful) return Kind.RETALIATION;
        if (hitMe) return Kind.PEACEFUL;
        if (enemy) return Kind.NEUTRAL;
        return Kind.NOT_HOSTILE;
    }

    /** Never attacked, whatever else is true: a player, a tamed mob, or anything with an owner. */
    public static boolean protectedMob(boolean player, boolean tame, boolean hasOwner) {
        return player || tame || hasOwner;
    }

    /**
     * Whether a hit is remembered for retaliation: it has a causing entity, that is a living thing other than the bot,
     * and not a player (fall, fire, drowning, lava have no entity and are ignored).
     */
    public static boolean recordAttacker(boolean hasEntity, boolean self, boolean living, boolean player) {
        return hasEntity && !self && living && !player;
    }

    /** Still within the retaliation window: the last hit was less than {@link #RETALIATE_TICKS} ago. */
    public static boolean retaliating(Long lastHit, long now) {
        return lastHit != null && now - lastHit >= 0 && now - lastHit < RETALIATE_TICKS;
    }

    /**
     * A stranger (a retaliation target, not an Enemy or listed mob) too strong to trade blows with: max health 100 or
     * more, or 10 times the bot's own weapon hit or more. The bot then retreats as at low health.
     */
    public static boolean strong(double maxHealth, double weaponHit) {
        return maxHealth >= STRONG_HP || maxHealth >= STRONG_HITS * Math.max(1, weaponHit);
    }

    /** A typed id to the full form: lower case, "minecraft:" when no namespace; null when it can't be an id. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        return ID.matcher(s).matches() ? s : null;
    }

    // ---- the file ----

    /** What a read gave: the ids, a note (null when fine), and whether the file was broken (a check finding). */
    public record Parsed(List<String> ids, String note, boolean failed) {}

    /** Pure: the file's text to ids. null/blank (no file yet) = the defaults; broken = the defaults with a note. */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) return new Parsed(DEFAULTS, null, false);
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) return new Parsed(DEFAULTS, "not a JSON object, using the default hostile list", true);
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return new Parsed(DEFAULTS, "not valid JSON (" + e.getMessage() + "), using the default hostile list", true);
        }
        if (!o.has("hostile")) return new Parsed(DEFAULTS, null, false);
        if (!o.get("hostile").isJsonArray()) return new Parsed(DEFAULTS, "\"hostile\" is not a list, using the default hostile list", true);
        Set<String> out = new LinkedHashSet<>();
        List<String> bad = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("hostile")) {
            String id = e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? normalize(e.getAsString()) : null;
            if (id == null) bad.add(String.valueOf(e));
            else if (out.size() < CAP) out.add(id);
            else bad.add(id + " (over " + CAP + ")");
        }
        return new Parsed(List.copyOf(out), bad.isEmpty() ? null : "ignored " + bad.size() + " bad entries: " + String.join(", ", bad), false);
    }

    /** Pure: the ids as the file's JSON. */
    public static String toJson(List<String> ids) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        ids.forEach(a::add);
        o.add("hostile", a);
        return o.toString();
    }

    // ---- the verb ----

    /** The answer, and the new list when it changed (null when nothing is to be saved). */
    public record Result(String text, List<String> changed) {}

    /**
     * {@code defend hostile [list] | add <id>... | remove <id>...} (rest: what follows "defend hostile"). known: whether an id
     * is in the game's entity registry. Ids may be separated by spaces or commas; a bare path gets "minecraft:".
     */
    public static Result command(String rest, List<String> current, Predicate<String> known) {
        String a = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        String[] w = a.isEmpty() ? new String[0] : a.split("[\\s,]+");
        if (w.length == 0 || w[0].equals("list") || w[0].equals("status")) return new Result(list(current), null);
        String verb = w[0];
        boolean add = verb.equals("add"), remove = verb.equals("remove") || verb.equals("rm") || verb.equals("delete");
        if (!add && !remove) return new Result("error: defend hostile [list] | add <entity id>... | remove <entity id>...", null);
        if (w.length < 2) return new Result("error: defend hostile " + verb + " <entity id> (e.g. arphex:spider_lunger; debug mobs shows the ids nearby)", null);
        Set<String> next = new LinkedHashSet<>(current);
        List<String> done = new ArrayList<>(), notes = new ArrayList<>();
        for (int i = 1; i < w.length; i++) {
            String id = normalize(w[i]);
            if (id == null) {
                notes.add("\"" + w[i] + "\" is not an entity id");
                continue;
            }
            if (add) {
                if (next.contains(id)) notes.add(id + " is on the list already");
                else if (!known.test(id)) notes.add("unknown entity id " + id + " (not in this game; debug mobs shows the ids nearby)");
                else if (next.size() >= CAP) notes.add("not adding " + id + ": I keep at most " + CAP);
                else {
                    next.add(id);
                    done.add(id);
                }
            } else {
                if (next.remove(id)) done.add(id);
                else notes.add(id + " is not on the list");
            }
        }
        String tail = notes.isEmpty() ? "" : " - " + String.join("; ", notes);
        if (done.isEmpty()) return new Result("error: nothing " + (add ? "added" : "removed") + tail, null);
        List<String> out = List.copyOf(next);
        return new Result("ok: " + (add ? "added " : "removed ") + String.join(", ", done) + tail + " | " + list(out), out);
    }

    public static String list(List<String> ids) {
        if (ids.isEmpty()) return "hostile list: empty (only Enemy-type mobs and whatever hits me are fought)";
        return "hostile list (" + ids.size() + ", fought like monsters, never when tamed): " + String.join(", ", ids);
    }
}
