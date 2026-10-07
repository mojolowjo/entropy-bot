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
    public static final Set<String> GUEST_VERBS = Set.of("help", "?", "", "status", "pos", "inv", "inventory", "queue", "places", "where", "have", "stock", "find",
            "recipe", "come", "follow", "goto", "stop", "restart", "hold", "scout");

    public static final String GUEST_HELP = "status, inv, queue, places, where <item>, have [item], stock [filter], find <block>, recipe <item>, come, follow [name], goto x y z, stop, hold this, "
            + "routines, routine show <name>, ores, stripmine status, restart ok|no, fence, fence vetoes, area list, area show <name>, area near, poi, poi show <id>, scout <dir> [n], find nearest <thing>";

    /** Every command word; routine names can't use these. */
    public static final List<String> BUILTIN_VERBS = List.of("help", "status", "pos", "inv", "inventory", "stop", "defend", "defense", "defence", "mark",
            "setbase", "sethome", "forget", "places", "where", "have", "stock", "say", "come", "follow", "goto", "spawn", "bed", "go", "base", "home", "death", "build",
            "open", "allow", "deny", "allowed", "b", "baritone", "debug", "mouse", "watch", "recorder", "mine", "craft", "recipe", "eat", "twerk", "drop", "find", "use", "put",
            "take", "close", "scan", "wear", "equip", "kit", "corpse", "deposit", "routine", "routines", "repeat", "run", "wait", "queue", "farm",
            "stripmine", "ores", "dig", "place", "memory", "restart", "area", "fence", "compact", "rs", "trust", "untrust", "pots",
            "poi", "pois", "explore", "caves", "smelt", "get", "need", "supplies", "restock", "rule", "rules", "autominer", "why", "resume", "escort",
            "deaths", "reconnect", "hotbar", "tools", "infuse", "upgrade", "check", "confirm", "route", "surface", "restore", "chop", "gather", "cook",
            "bootstrap", "sleep", "light", "junk", "hold", "give", "carry", "unload", "fetch", "attack", "scout");

    /**
     * The jobs the mod runs itself: walks since B7b part 1, the storage errands (and "go poi") since part 2, crafting,
     * the furnace, get/restock, the farm and compact since B7c (need/supplies/recipe are instant there).
     */
    public static final Set<String> MOD_JOB_VERBS = Set.of("come", "follow", "goto", "spawn", "bed", "go", "base", "home", "wait", "twerk", "find",
            "open", "scan", "deposit", "corpse", "death", "rs", "pots",
            "craft", "kit", "smelt", "get", "restock", "farm", "compact", "recipe", "need", "supplies", "infuse", "upgrade",
            "dig", "build", "place", "stripmine", "mine", "explore", "route", "restore", "chop", "cook", "sleep", "scout");

    /** The instant GUI verbs the mod does since B7b part 2 (no job). */
    public static final Set<String> MOD_VERBS = Set.of("take", "put", "close", "drop", "use", "wear", "equip");

    public static final List<String> PM_HELP = List.of(
            "Moving: come, follow [name], goto x y z, go <place>, base, home (/home), sethome (my home = here), death (go back + empty my corpse), corpse (empty my corpse nearby), stop, spawn (use nearest bed) | "
                    + "Mule: hold this (throw me items within 15 s), give me <item> [n] | give <player> <item> [n] (never my tools, armor, last 8 food, 16 torches, supplies), "
                    + "carry <item...> | carry off | carry list (follow you, pick up those drops near you), unload (home, deposit, back to you), fetch <item> [n] (storage, else gather, then give me)",
            "Making things: craft <item> [n] fetches what it needs from my chests and the RS network and uses a furnace when a step needs one; smelt <item> [n] (e.g. smelt iron_ingot 9; I go on with other things and pick it up when it is done), smelt jobs | smelt collect [all] | smelt mode efficient|wait | smelt forget <#|all>, "
                    + "need <item> [n] (what it takes, what is missing), get <item> [n] (from storage), supplies set <item n, ...> | supplies | supplies clear, restock (top the supplies up), infuse <seed> [n] (on the infusion altar: I fetch the ingredients, fill the altar and pedestals, press the button; I never touch what isn't mine), upgrade <essence> [n] (e.g. upgrade imperium 4: the tiers climbed with the infusion crystal kept, in rounds my bag holds)",
            "Items: inv, eat, wear, craft <item> [n] (planks = any wood; \"copper armor\", \"iron tools\"; comma lists), kit <material>, recipe <item>, drop <item|all> [n], mine <ore> [n] [dig] (ores, sand, gravel, clay, grass; inside my areas, 16+ from safe areas; fetches a pickaxe first)",
            "Tools and hotbar: hotbar set 1 pickaxe 2 sword 3 food 4 torch (slots 1-9; pickaxe, sword, axe, shovel, hoe, food, torch or an item; I put them there when idle), hotbar, hotbar clear <slot>|all, "
                    + "tools, tools ores iron|cheapest (ores with the iron pickaxe, or the cheapest that does the job; stone pickaxes for stone)",
            "Chests: scan [radius] (learn the chests around me), scan base|<place>|x y z (go there and learn them), deposit [item...] (put loot away in the base chests), where <item>, open x y z|<place> (walks there), take/put <item|all> [n] (exact; \"only ...\" = fell short), close, untrust/trust x y z|<place> (keep me out of a chest), trust (list) | Places: mark <name>, setbase, places, forget <name>, mark food (next to a chest: I fetch food from it when I run out)",
            "Building: build <floor|walls|shell|fill> <block> <area>, build clear <area> (breaks everything in that area, top down), dig <area> (its whole box; a destroy area loses built blocks too), dig x1 y1 z1 x2 y2 z2 [ores] [force] [floor [block]] [junk drop] [water [large]], place <block> x y z",
            "Mining: mark mine (stand at the start facing the way to dig), stripmine [branches] [length] (try \"repeat forever stripmine\"), stripmine status|reset, stripmine ores collect|list (mine ores inside the mapped area, or only list them), stripmine turn left|right (a new mine from the corridor end; I turn by myself when the corridor is blocked), ores [name|clear]",
            "Mining for ores: mine strip <ores> [n] [at <mine>] (runs until n of them are mined), mine cave <ores> [n | <min>m] [at <cave>] (explores a cave, lights it, mines what it sees; remembers how far it got), "
                    + "ores: iron,diamond or iron_ore or any; ores prefer <ores> (the default list), caves (list), caves rename <old> <new>, explore [minutes] (walk unvisited land inside my areas, note what is there) | "
                    + "Wood: chop <logs> [log type] | chop trees <n> [log type] (fell trees in my areas, pick up, replant; never next to builds), chop status | "
                    + "Gathering: gather <item> [n] [<min>m] (n into my bag: from storage, crafted or smelted, the raw items mined, chopped or farmed; 60 min at most), "
                    + "gather status, gather sources [item], gather source <item> <command with {n}> | clear",
            "Farm: farm (one round: twerk till ripe, harvest, pick up, essence into blocks; try \"repeat forever farm\"), farm here (the farm is by me), farm compact block|prudentium|off, "
                    + "compact <item> [here|<place>|x y z] (the chests within 6 of you or me: 9 into a block, blocks back into their chest) | rs [x y z] (read the Refined Storage grid; \"where\" lists it then), rs take <item> [n], rs put <item|all> [n], rs disks [x y z] (which disks the drive holds, and how full) | pots [chests] (empty the botany pots at the base into the RS network, or the chests)",
            "Places of interest (I note them as I go): poi [n], poi <kind> (dungeon spawner, trial chamber, village, geode, mineshaft, lava lake, diamonds, loot chest...), poi show <id>, poi forget <id>, go poi <id>",
            "Other: status, defend on|off, defend creepers flee|melee|bow, defend hostile list|add|remove <id>, find <block>, say <text>, twerk (on/off; twerk <s> for a set time), wait <s>, memory (how my note files are), restart ok|no (I may be closed for an update, 15 min), restore [status] | restore now [r] | restore forget <n>|all confirm | restore ignore x y z | restore mode auto|manual|off (blocks I broke on the way, put back) | Only for you: allow/deny <name>, allowed, b <baritone cmd>, mouse free|grab, route status|on|off|mode goal|legs|build|dump|test (the travel map for long walks); allowed players get " + GUEST_HELP,
            "On my own: rule every 30m do <cmds> | rule at 06:30 do ... | rule when full do ... | rule when idle 10m do ..., rules, rule delete <n>; autominer on|off|status, why (what it decided); "
                    + "deaths, death policy on|off (fetch my corpse after a death; 5 deaths an hour park me), resume (after parking); reconnect on|off (after a kick); a running routine carries on after a reload or restart (12 h)",
            "Recorder (owner): recorder (settings, disk, incidents), recorder off|light|normal|detailed|max [for 30m|2h], recorder range <chunks> | trail <ticks|Ns> | snapshot <blocks>|now | states on|off | keep <hours> | mark <note>",
            "Chains: cmd then cmd then cmd | routine save <name> <chain>, routines, routine show/delete <name>, <name> (runs it), repeat [n|forever] <name or chain>, queue, stop",
            "Areas (where I may go and dig): area here <r> <name> [type] [down up], area x z x2 z2 <name> [type] [y1 y2] (types: neutral (white, the default), destroy (red: dig <area> breaks built blocks too), "
                    + "main (blue: the base, built blocks never), safe (green: walk only, never dig)), area change name <name> <new>, area change type <name> <type>, area del <name> confirm, area list, area show <name>, "
                    + "area near [<r>|on|off] (I may work within 16 blocks of you anywhere) | Fence: fence, fence vetoes, fence check x y z break|place|go, fence mode strict | log confirm");

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
    /** 0.21.0: "stock targets|set|clear ..." is C4's base-chest targets; "stock" alone or "stock <filter>" is C2's shared view. */
    public static boolean isStockTargetsForm(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        return r.matches("^(targets|set|clear)(\\s.*)?$");
    }

    public static String guestRefusal(String verb, String rest, String raw, String owner) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (splitChain(raw).size() > 1) return "sorry, only " + owner + " can start chains";
        if (verb.equals("stock") && isStockTargetsForm(r)) return "sorry, stock targets|set|clear are " + owner + "'s - you can use stock [filter]";
        if (GUEST_VERBS.contains(verb)) return null;
        if (verb.equals("guard") && (r.isEmpty() || r.equals("vetoes"))) return OldWords.hint(verb, r);       // V1a: the cut word, its new form
        if ((verb.equals("routine") || verb.equals("routines")) && (r.isEmpty() || r.matches("^show\\b.*"))) return null;
        if (verb.equals("why") || verb.equals("rules") || (verb.equals("deaths") && r.isEmpty()) || (verb.equals("autominer") && (r.isEmpty() || r.equals("status")))) return null;
        if (verb.equals("ores") && !r.matches("^(clear|forget)\\b.*") && !r.matches("^prefer\\s+\\S.*")) return null;
        if (verb.equals("stripmine") && r.equals("status")) return null;
        if (verb.equals("fence") && (r.isEmpty() || r.equals("vetoes") || r.equals("status"))) return null;
        if (verb.equals("escort") && r.matches("^(|status|off|me(\\s+\\d+)?)$")) return null;     // C7: guests escort only themselves
        if (verb.equals("area") && (r.equals("list") || r.matches("^show\\b.*") || r.matches("^near(\\s+status)?$"))) return null;
        if ((verb.equals("poi") || verb.equals("pois")) && !r.matches("^forget\\b.*")) return null;
        if (verb.equals("caves") && !r.matches("^rename\\b.*")) return null;
        if (verb.equals("ores") && r.matches("^prefer\\s+\\S.*")) return "sorry, only " + owner + " can set the preferred ores";
        return "sorry, only " + owner + " can use \"" + verb + "\". You can use: " + HelpCommand.guestList();
    }

    /** B7e: the answer to a cmd.json type the mod doesn't know (the KubeJS bridge used to take the rest). */
    public static String unknownType(String type) {
        return "error: unknown type " + type;
    }

    /** B7e: a "debug" answer (a 4096-cell slice is ~15 KB) goes into the log cut to this many characters and its length. */
    public static final int DEBUG_LOG_CHARS = 200;

    /** What the log line of a cmd.json answer shows: the answer, or for "debug" (a type or a pm) its start and length. */
    public static String cmdLogged(String type, String text, String result) {
        boolean debug = "debug".equals(type) || ("pm".equals(type) && verbAndRest(text)[0].equals("debug"));
        if (!debug || result == null || result.length() <= DEBUG_LOG_CHARS) return result;
        return result.substring(0, DEBUG_LOG_CHARS) + "... (" + result.length() + " chars)";
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

    /** S1: "goto me" / "goto <the sender's own name>" means "come" (the owner typed it live, 2026-10-03). */
    static boolean gotoMeansCome(String rest, String from) {
        String r = rest == null ? "" : rest.trim();
        return r.equalsIgnoreCase("me") || (from != null && !from.isEmpty() && r.equalsIgnoreCase(from));
    }
}
