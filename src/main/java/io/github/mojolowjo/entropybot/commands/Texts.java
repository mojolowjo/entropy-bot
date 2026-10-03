package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The command words, help texts and small text rules of the PM handler (B7a, ported from the KubeJS bridge's
 * handlePm with the same wording, so the owner sees no difference). Plain Java: JUnit tests it.
 */
public final class Texts {
    private Texts() {}

    /** What allowed players who aren't the owner may use (plus the read-only forms in {@link #guestRefusal}). */
    public static final Set<String> GUEST_VERBS = Set.of("help", "?", "", "status", "pos", "inv", "inventory", "queue", "places", "where", "find",
            "recipe", "come", "follow", "goto", "stop", "restart");

    public static final String GUEST_HELP = "status, inv, queue, places, where <item>, find <block>, recipe <item>, come, follow [name], goto x y z, stop, "
            + "routines, routine show <name>, ores, stripmine status, restart ok|no, guard, guard vetoes, area list, area show <name>, poi, poi show <id>";

    /** Every command word; routine names can't use these. */
    public static final List<String> BUILTIN_VERBS = List.of("help", "status", "pos", "inv", "inventory", "stop", "defend", "defense", "defence", "mark",
            "setbase", "sethome", "forget", "places", "where", "zone", "say", "come", "follow", "goto", "spawn", "bed", "go", "base", "home", "death", "build",
            "open", "allow", "deny", "allowed", "b", "baritone", "mine", "craft", "recipe", "eat", "twerk", "drop", "find", "use", "put",
            "take", "close", "scan", "wear", "equip", "kit", "corpse", "deposit", "routine", "routines", "repeat", "run", "wait", "queue", "farm",
            "stripmine", "ores", "dig", "place", "memory", "restart", "area", "protect", "unprotect", "guard", "compact", "rs", "trust", "untrust", "pots",
            "poi", "pois", "explore", "caves", "smelt", "get", "need", "supplies", "restock", "rule", "rules", "autominer", "why", "resume",
            "deaths", "reconnect");

    /**
     * The verbs the bridge script still does (B7a): they are handed to it through {@code BotAPI.bridgeNext}. Each
     * later stage of B7 moves some of them into the mod.
     */
    public static final Set<String> BRIDGE_VERBS = Set.of("memory", "ores", "stripmine",
            "zone", "build", "b", "baritone", "mine", "dig", "place", "explore");

    /**
     * The jobs the mod runs itself: walks since B7b part 1, the storage errands (and "go poi") since part 2, crafting,
     * the furnace, get/restock, the farm and compact since B7c (need/supplies/recipe are instant there).
     */
    public static final Set<String> MOD_JOB_VERBS = Set.of("come", "follow", "goto", "spawn", "bed", "go", "base", "home", "wait", "twerk", "find",
            "open", "scan", "deposit", "corpse", "death", "rs", "pots",
            "craft", "kit", "smelt", "get", "restock", "farm", "compact", "recipe", "need", "supplies");

    /** The instant GUI verbs the mod does since B7b part 2 (no job; they never wait for the bridge's job). */
    public static final Set<String> MOD_VERBS = Set.of("take", "put", "close", "drop", "use", "wear", "equip");

    public static final List<String> PM_HELP = List.of(
            "Moving: come, follow [name], goto x y z, go <place>, base, home (/home), sethome (my home = here), death (go back + empty my corpse), corpse (empty my corpse nearby), stop, spawn (use nearest bed)",
            "Making things: craft <item> [n] fetches what it needs from my chests and the RS network and uses a furnace when a step needs one; smelt <item> [n] (e.g. smelt iron_ingot 9), "
                    + "need <item> [n] (what it takes, what is missing), get <item> [n] (from storage), supplies set <item n, ...> | supplies | supplies clear, restock (top the supplies up)",
            "Items: inv, eat, wear, craft <item> [n] (planks = any wood; \"copper armor\", \"iron tools\"; comma lists), kit <material>, recipe <item>, drop <item|all> [n], mine <ore> [n] [dig] (ores, sand, gravel, clay, grass; inside the mapped area)",
            "Chests: scan [radius] (learn the chests around me), scan base|<place>|x y z (go there and learn them), deposit [item...] (put loot away in the base chests), where <item>, open x y z|<place> (walks there), take/put <item|all> [n] (exact; \"only ...\" = fell short), close, untrust/trust x y z|<place> (keep me out of a chest), trust (list) | Places: mark <name>, setbase, places, forget <name>, mark food (next to a chest: I fetch food from it when I run out)",
            "Zone: zone corner1, zone corner2 (stand on them), zone, build <floor|walls|shell|fill> <block>, build clear (breaks everything in the zone, top down), dig x1 y1 z1 x2 y2 z2, place <block> x y z",
            "Mining: mark mine (stand at the start facing the way to dig), stripmine [branches] [length] (try \"repeat forever stripmine\"), stripmine status|reset, stripmine ores collect|list (mine ores inside the mapped area, or only list them), stripmine turn left|right (a new mine from the corridor end; I turn by myself when the corridor is blocked), ores [name|clear]",
            "Mining for ores: mine strip <ores> [n] [at <mine>] (runs until n of them are mined), mine cave <ores> [n | <min>m] [at <cave>] (explores a cave, lights it, mines what it sees; remembers how far it got), "
                    + "ores: iron,diamond or iron_ore or any; ores prefer <ores> (the default list), caves (list), caves rename <old> <new>, explore [minutes] (walk unvisited land inside my areas, note what is there)",
            "Farm: farm (one round: twerk till ripe, harvest, pick up, essence into blocks; try \"repeat forever farm\"), farm here (the farm is by me), farm compact block|prudentium|off, "
                    + "compact <item> [here|<place>|x y z] (the chests within 6 of you or me: 9 into a block, blocks back into their chest) | rs [x y z] (read the Refined Storage grid; \"where\" lists it then), rs take <item> [n], rs put <item|all> [n] | pots [chests] (empty the botany pots at the base into the RS network, or the chests)",
            "Places of interest (I note them as I go): poi [n], poi <kind> (dungeon spawner, trial chamber, village, geode, mineshaft, lava lake, diamonds, loot chest...), poi show <id>, poi forget <id>, go poi <id>",
            "Other: status, defend on|off, find <block>, say <text>, twerk (on/off; twerk <s> for a set time), wait <s>, memory [status|fresh], restart ok|no (I may be closed for an update, 15 min) | Only for you: allow/deny <name>, allowed, b <baritone cmd>; allowed players get " + GUEST_HELP,
            "On my own: rule every 30m do <cmds> | rule at 06:30 do ... | rule when full do ... | rule when idle 10m do ..., rules, rule delete <n>; autominer on|off|status, why (what it decided); "
                    + "deaths, death policy on|off (fetch my corpse after a death; 5 deaths an hour park me), resume (after parking); reconnect on|off (after a kick); a running routine carries on after a reload or restart (12 h)",
            "Chains: cmd then cmd then cmd | routine save <name> <chain>, routines, routine show/delete <name>, <name> (runs it), repeat [n|forever] <name or chain>, queue, stop",
            "Guard (where I may go and dig): area list, area show <name>, area add <name> here <r> | x1 z1 x2 z2 [y1 y2], area corner1, area corner2 <name>, area grow <name> <n>, "
                    + "area remove <name> confirm, protect [<name> here <r> [down up] | <name> x1 y1 z1 x2 y2 z2], unprotect <name> confirm, guard, guard vetoes, guard check x y z break|place|go, "
                    + "guard mode strict | log confirm");

    private static final Pattern CHAIN_SPLIT = Pattern.compile("\\s*;\\s*|\\s+then\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED = Pattern.compile("^(error|busy|unknown|usage|only |sorry|i can't|i cannot|i have no|i don't|i haven't|no |nothing|none|say what)",
            Pattern.CASE_INSENSITIVE);

    /** "a then b; c" -> [a, b, c] (empty parts dropped). */
    public static List<String> splitChain(String text) {
        List<String> out = new ArrayList<>();
        for (String s : CHAIN_SPLIT.split(text == null ? "" : text, -1)) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** A reply that means the step didn't happen. */
    public static boolean stepFailed(String reply) {
        String r = reply == null ? "" : reply.replaceFirst("^ok: ", "");
        return FAILED.matcher(r).find();
    }

    /** Null when an allowed player who isn't the owner may run this, else the refusal. */
    public static String guestRefusal(String verb, String rest, String raw, String owner) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (splitChain(raw).size() > 1) return "sorry, only " + owner + " can start chains";
        if (GUEST_VERBS.contains(verb)) return null;
        if ((verb.equals("routine") || verb.equals("routines")) && (r.isEmpty() || r.matches("^show\\b.*"))) return null;
        if (verb.equals("why") || verb.equals("rules") || (verb.equals("deaths") && r.isEmpty()) || (verb.equals("autominer") && (r.isEmpty() || r.equals("status")))) return null;
        if (verb.equals("ores") && !r.matches("^(clear|forget)\\b.*") && !r.matches("^prefer\\s+\\S.*")) return null;
        if (verb.equals("stripmine") && r.equals("status")) return null;
        if (verb.equals("guard") && (r.isEmpty() || r.equals("vetoes"))) return null;
        if (verb.equals("area") && (r.equals("list") || r.matches("^show\\b.*"))) return null;
        if ((verb.equals("poi") || verb.equals("pois")) && !r.matches("^forget\\b.*")) return null;
        if (verb.equals("caves") && !r.matches("^rename\\b.*")) return null;
        if (verb.equals("ores") && r.matches("^prefer\\s+\\S.*")) return "sorry, only " + owner + " can set the preferred ores";
        return "sorry, only " + owner + " can use \"" + verb + "\". You can use: " + GUEST_HELP;
    }

    /** "say" only ever talks: a line starting with "/" or Baritone's prefix would be a command. */
    public static String sayRefusal(String text, String prefix) {
        String t = text == null ? "" : text.trim(), p = prefix == null ? "#" : prefix;
        if (t.startsWith("/") || t.startsWith("#") || (!p.isEmpty() && t.startsWith(p))) {
            return "error: say only talks, it won't send commands (lines starting with / or " + p + ")";
        }
        return null;
    }

    /**
     * A whisper split into pieces of at most 200 characters at spaces (a chat command maxes out at 256). Each line
     * starts a piece of its own (package A: "why" puts the latest decision on a line by itself).
     */
    public static List<String> whisperParts(String text) {
        List<String> out = new ArrayList<>();
        for (String line : String.valueOf(text).split("\n")) {
            String t = line.replaceAll("\\s+", " ").trim();
            while (!t.isEmpty()) {
                int cut = t.length() <= 200 ? t.length() : t.lastIndexOf(' ', 200);
                if (cut <= 0) cut = 200;
                out.add(t.substring(0, cut));
                t = t.substring(cut).trim();
            }
        }
        return out;
    }

    /** "12s ago", "5m ago", "3h ago". */
    public static String ago(long ms, long now) {
        long s = Math.round((now - ms) / 1000.0);
        if (s < 90) return s + "s ago";
        if (s < 5400) return Math.round(s / 60.0) + "m ago";
        return Math.round(s / 3600.0) + "h ago";
    }

    public static String shortId(String id) {
        return id == null ? "" : id.replaceFirst("^minecraft:", "");
    }

    /** The first word (lower case) and the rest of a command line, a leading "!" dropped. */
    public static String[] verbAndRest(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.startsWith("!")) raw = raw.substring(1);
        int sp = raw.indexOf(' ');
        String verb = (sp < 0 ? raw : raw.substring(0, sp)).toLowerCase();
        String rest = sp < 0 ? "" : raw.substring(sp + 1).trim();
        return new String[]{verb, rest, raw};
    }

    static boolean isBuiltin(String verb) {
        return BUILTIN_VERBS.contains(verb);
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : (s == null ? "" : s.trim()).split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }
}
