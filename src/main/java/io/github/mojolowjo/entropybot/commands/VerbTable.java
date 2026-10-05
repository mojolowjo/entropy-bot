package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B7e package N (Part 2, item 1): one table of every command word: its aliases, the section of the guide, who may use
 * it, the usage, one or two examples and the "next" hint (what usually follows). It drives {@code help <verb>},
 * {@code help <page>}, the guests' help and the "did you mean" of an unknown word. Pure (no game classes): JUnit
 * checks it covers {@link Texts#BUILTIN_VERBS} and every command of docs/COMMANDS.md.
 */
public final class VerbTable {
    private VerbTable() {}

    /** Who may use a verb: everyone allowed, only some read-only forms for guests, or the owner alone. */
    public enum Who { GUEST, PARTLY, OWNER }

    /**
     * One command word. guestForms: what guests may use of a PARTLY verb (null otherwise). next: the usual follow-up
     * command (null: none).
     */
    public record Verb(String name, List<String> aliases, String section, Who who, String usage, String what, List<String> examples,
                       String next, String guestForms) {}

    /** The sections of the guide, in page order ("help 1" .. "help N"). */
    public static final List<String> SECTIONS = List.of("moving", "chests", "crafting", "mining", "safety", "automation", "info", "other");

    private static final Map<String, Verb> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, Verb> BY_WORD = new LinkedHashMap<>();

    private static void v(String name, String aliases, String section, Who who, String usage, String what, String examples, String next) {
        v(name, aliases, section, who, usage, what, examples, next, null);
    }

    /** aliases and examples: "|"-separated ("" = none). */
    private static void v(String name, String aliases, String section, Who who, String usage, String what, String examples, String next, String guestForms) {
        List<String> al = aliases.isEmpty() ? List.of() : List.of(aliases.split("\\|"));
        List<String> ex = examples.isEmpty() ? List.of() : List.of(examples.split("\\|"));
        Verb verb = new Verb(name, al, section, who, usage, what, ex, next, guestForms);
        if (!SECTIONS.contains(section)) throw new IllegalStateException("no section " + section);
        if (BY_NAME.put(name, verb) != null) throw new IllegalStateException("twice: " + name);
        for (String w : words(verb)) if (BY_WORD.put(w, verb) != null) throw new IllegalStateException("twice: " + w);
    }

    static {
        Who G = Who.GUEST, P = Who.PARTLY, O = Who.OWNER;
        // ---- moving
        v("come", "", "moving", G, "come", "walk to you (out of view: the companion mod's position)", "come", "follow");
        v("follow", "", "moving", G, "follow [name]", "follow you (or that player) until stop", "follow|follow Steve", "stop");
        v("goto", "", "moving", G, "goto x y z | goto x z | goto me", "walk to a spot", "goto 120 64 -300", "status");
        v("go", "", "moving", O, "go <place> | go poi <id>", "walk to a named place (far: /home first)", "go farm|go poi 11", "places");
        v("base", "", "moving", O, "base", "walk to the base (far: /home first)", "base", "deposit");
        v("home", "", "moving", O, "home", "teleport home with the server's /home", "home", "sethome");
        v("sethome", "", "moving", O, "sethome", "my home = where I stand (/sethome home)", "sethome", "home");
        v("route", "", "moving", O, "route status | route on|off | route mode goal|legs | route build [place|x y z] | route dump x y z | route test <placeA> <placeB> [trips]",
                "the travel map for long walks: its status, on/off, how walks use it, build it toward a place, dump a box, measure trips (to routes/trips.csv)",
                "route status|route test base farm 6", "route status");
        v("mark", "", "moving", O, "mark <name> [x y z] [north|south|east|west]", "remember a spot (where you stand, or the coordinates); mark mine and mark food are special",
                "mark farm|mark mine north", "places");
        v("setbase", "", "moving", O, "setbase [x y z]", "mark base", "setbase", "scan base");
        v("forget", "", "moving", O, "forget <name>", "forget a place", "forget farm", "places");
        v("places", "", "moving", G, "places", "the places I know", "places", "go <place>");
        v("spawn", "bed", "moving", O, "spawn", "walk to the nearest bed and set my respawn point there", "spawn", "status");
        v("stop", "", "moving", G, "stop", "cancel everything, breaking off (the autominer waits 10 min)", "stop", "autominer on");
        v("death", "", "moving", O, "death | death policy on|off", "walk back to where I died and empty my corpse", "death", "deaths");
        v("corpse", "", "moving", O, "corpse", "empty my own corpse when it is near", "corpse", "inv");
        // ---- chests and storage
        v("open", "", "chests", O, "open x y z | open <place>", "walk to a chest and open it (lists what is inside)", "open -28 54 189|open bulk then take spruce_log 192 then close", "take <item> [n]");
        v("take", "", "chests", O, "take <item|all> [n]", "take from the open chest (exact counts)", "take charcoal 64", "close");
        v("put", "", "chests", O, "put <item|all> [n]", "put into the open chest", "put all|put charcoal 50", "close");
        v("close", "", "chests", O, "close", "close the open chest", "close", "inv");
        v("deposit", "", "chests", O, "deposit [item ...]", "put loot away in the base chests (keeps tools, armor, food, supplies)", "deposit|deposit cobblestone dirt", "inv");
        v("scan", "", "chests", O, "scan [radius] | scan base|<place>|x y z [radius]", "open the chests around (there) and remember them", "scan base|scan 8", "where <item>");
        v("where", "", "chests", G, "where <item>", "which chest has it (and the RS network)", "where iron", "open <place>");
        v("find", "", "chests", G, "find <block>", "the nearest block of that kind", "find chest|find crafting_table", "goto x y z");
        v("trust", "", "chests", O, "trust | trust x y z|<place>", "list the chests I keep out of, or let me use one again", "trust", "untrust x y z");
        v("untrust", "", "chests", O, "untrust x y z|<place>", "keep me out of a chest (craft trips, deposits, the food run)", "untrust -20 53 180", "trust");
        v("drop", "", "chests", O, "drop <item|all> [n]", "throw items on the ground", "drop dirt 64", "inv");
        v("use", "", "chests", O, "use x y z", "right-click a block", "use -23 53 156", "close");
        v("wear", "equip", "chests", O, "wear", "put on armor from my bag", "wear", "inv");
        v("rs", "", "chests", O, "rs [x y z] | rs take <item> [n] | rs put <item|all> [n] | rs disks [x y z]", "the Refined Storage network: read it, take, put, the disks",
                "rs|rs take bread 32", "where <item>");
        v("pots", "", "chests", O, "pots [chests]", "empty the botany pots at the base into the RS network (or the chests)", "pots", "rs");
        // ---- crafting, smelting, the farm
        v("craft", "", "crafting", O, "craft <item> [n] | craft <material> armor|tools | craft a, b 16", "craft it, fetching materials and making the parts (table and furnace as needed)",
                "craft stick 16|craft copper armor", "inv");
        v("kit", "", "crafting", O, "kit <material>", "craft that material's armor and tools, then wear the armor", "kit copper", "inv");
        v("recipe", "", "crafting", G, "recipe <item>", "what an item needs", "recipe hopper", "need <item> [n]");
        v("need", "", "crafting", O, "need <item> [n]", "what it takes and what is missing", "need refinedstorage:basic_processor 4", "craft <item> [n]");
        v("get", "", "crafting", O, "get <item> [n]", "fetch from the chests or the RS network", "get coal 16", "inv");
        v("supplies", "", "crafting", O, "supplies | supplies set <item n, ...> | supplies clear", "what I always carry (set replaces the whole list)",
                "supplies set torch 32, bread 16, iron_pickaxe 1|supplies", "restock");
        v("restock", "", "crafting", O, "restock", "top my supplies up: from storage first, the rest crafted", "restock", "supplies");
        v("smelt", "", "crafting", O, "smelt <item> [n] | smelt jobs | smelt collect [all] | smelt mode efficient|wait | smelt forget <#|all>",
                "smelt at a base furnace and go on with other things", "smelt iron_ingot 9|smelt jobs", "smelt collect");
        v("compact", "", "crafting", O, "compact <item> [here|<place>|x y z]", "turn 9 (or 4) into a block in the chests near you (or me)", "compact inferium_essence", "where <item>");
        v("farm", "", "crafting", O, "farm | farm here | farm compact block|prudentium|off", "one farm round: twerk till ripe, harvest, pick up, make blocks",
                "farm|repeat forever farm", "deposit");
        v("infuse", "", "crafting", O, "infuse <seed> [n]", "make seeds on the infusion altar (never touches what isn't mine)", "infuse silicon 2", "inv");
        v("upgrade", "", "crafting", O, "upgrade <essence> [n]", "climb the essence tiers with the infusion crystal", "upgrade imperium 4", "inv");
        v("eat", "", "crafting", O, "eat", "eat now (I also eat by myself)", "eat", "mark food");
        v("hotbar", "", "crafting", O, "hotbar | hotbar set <slot> <kind|item> ... | hotbar clear <slot>|all", "keep tools in hotbar slots (pickaxe, sword, axe, shovel, hoe, food, torch, or an item)",
                "hotbar set 1 pickaxe 2 sword 3 food 4 torch", "hotbar");
        v("tools", "", "crafting", O, "tools | tools ores iron|cheapest", "which pickaxe ores get", "tools ores iron", "tools");
        // ---- digging and mining
        v("mine", "", "mining", O, "mine <ore> [n] [dig] | mine strip <ores> [n] [at <mine>] | mine cave <ores> [n | <min>m] [at <cave>]",
                "mine ores in view (dig: may dig to them), strip-mine at a mine, or go caving", "mine iron_ore 10|mine strip iron,diamond 16", "deposit");
        v("stripmine", "", "mining", P, "stripmine [branches] [length] | stripmine status | stripmine reset | stripmine ores collect|list | stripmine turn left|right",
                "dig more branches at the marked mine (reset asks to confirm)", "stripmine 3 16|repeat forever stripmine", "stripmine status", "stripmine status");
        v("caves", "", "mining", P, "caves | caves rename <old> <new>", "the caves I know", "caves", "mine cave any 20 10m", "caves");
        v("explore", "", "mining", O, "explore [minutes]", "walk unvisited land inside my areas, then home", "explore 5", "poi");
        v("ores", "", "mining", P, "ores [name] | ores clear | ores prefer <ores>", "ores I left in place, nearest first; the preferred ore list", "ores iron|ores prefer diamond,iron",
                "mine <ore> [n]", "ores [name], ores prefer (just looking)");
        v("dig", "", "mining", O, "dig x1 y1 z1 x2 y2 z2 [ores] [force] [floor [block]] [junk drop] [water [large]]",
                "clear a box the careful way (20000 blocks max; over 1000 asks to confirm; force: built blocks, 64 max); water or lava in the way "
                        + "ends it \"blocked by water at x y z\"; water: seal the water off with junk blocks and dig on (large: a big body of water too)",
                "dig 10 60 10 20 64 20 ores|dig 247 -46 853 310 -44 855 floor junk drop water", "deposit");
        v("zone", "", "mining", O, "zone corner1|corner2 [x y z] | zone | zone clear", "the work zone for build (stand on opposite corners)", "zone corner1|zone corner2", "build floor <block>");
        v("build", "", "mining", O, "build floor|walls|shell|fill <block> | build clear", "build inside the zone (never breaks); clear breaks the whole zone (asks to confirm)",
                "build floor cobblestone|build clear", "status");
        v("place", "", "mining", O, "place <block> x y z", "place one block (walks into reach)", "place cobblestone 10 64 20", "status");
        // ---- safety and areas
        v("area", "", "safety", P, "area list | area show <name> | area add <name> here <r> | area add <name> x1 z1 x2 z2 [y1 y2] | area corner1 | area corner2 <name> | area grow <name> <n> | area remove <name>",
                "where I may walk and dig (remove asks to confirm)", "area add base here 60|area list", "guard", "area list, area show <name>");
        v("protect", "", "safety", O, "protect | protect <name> here <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2", "a box I never dig (default 8 below, 16 above)",
                "protect base here 16", "protect");
        v("unprotect", "", "safety", O, "unprotect <name> confirm", "remove a protect box", "unprotect base confirm", "protect");
        v("guard", "", "safety", P, "guard | guard vetoes | guard check x y z break|place|go | guard mode strict | guard mode log confirm", "the fence: its mode, what it refused, dry runs",
                "guard|guard check 10 64 20 break", "area list", "guard, guard vetoes");
        v("defend", "defense|defence", "safety", O, "defend on|off | defend creepers flee|melee|bow | defend hostile [list|add <id>...|remove <id>...]",
                "self-defence (fights monsters and hostile-list mobs, hits back at what hits me, never pets or players, avoids creepers, retreats under 6 health)",
                "defend on|defend hostile add arphex:spider_jump", "debug mobs");
        v("deaths", "", "safety", P, "deaths | death policy on|off", "deaths in the last hour; fetch my corpse after a death or not", "deaths", "resume", "deaths");
        v("resume", "", "safety", O, "resume", "carry on after 5 deaths in an hour parked me", "resume", "autominer status");
        v("reconnect", "", "safety", O, "reconnect on|off", "rejoin after a kick (1, 5, 15 min; 3 an hour)", "reconnect on", "status");
        v("restart", "", "safety", G, "restart ok|no", "I may be closed for an update in the next 15 minutes (or not)", "restart ok", "status");
        v("check", "", "safety", O, "check", "a self-test: what I miss to work on my own, each with the command that fixes it", "check", "check");
        v("confirm", "", "safety", O, "confirm", "run the big job I just asked about (within 30 s; anything else cancels it)", "confirm", "status");
        // ---- automation
        v("routine", "routines", "automation", P, "routines | routine save <name> <chain> | routine show <name> | routine delete <name>", "saved chains; say the name to run one",
                "routine save night deposit then eat then base|routines", "<name>", "routines, routine show <name>");
        v("repeat", "", "automation", O, "repeat [n|forever] <routine or chain>", "loop it (a round at most every 10 s)", "repeat forever farm|repeat 3 night", "queue");
        v("run", "", "automation", O, "run <routine>", "run a saved routine", "run night", "queue");
        v("wait", "", "automation", O, "wait <seconds>", "a pause (a step in a chain)", "deposit then wait 30 then farm", "queue");
        v("queue", "", "automation", G, "queue", "where the running chain is", "queue", "stop");
        v("rule", "rules", "automation", P, "rules | rule every <n>m|h do <cmds> | rule at HH:MM do <cmds> | rule when full do <cmds> | rule when idle <n>m do <cmds> | rule delete <n>",
                "things I do by myself when idle", "rule every 30m do farm then deposit|rule when full do deposit", "rules", "rules");
        v("autominer", "", "automation", P, "autominer on|off|status", "mine on my own when idle (deposit, restock, strip mine, else caves)", "autominer on", "why", "autominer status");
        v("why", "", "automation", G, "why", "what the autominer decided and why", "why", "autominer status");
        // ---- info
        v("help", "?", "info", G, "help | help <verb> | help <page> | help all", "this guide: one verb, one page of it, or all", "help mine|help 2", "status");
        v("status", "pos", "info", G, "status", "where I am, health, food and the job", "status", "inv");
        v("inv", "inventory", "info", G, "inv", "what I carry", "inv", "deposit");
        v("poi", "pois", "info", P, "poi [n] | poi <kind> | poi show <id> | poi forget <id>", "points of interest I have seen (dungeons, spawners, villages...)",
                "poi|poi spawner", "go poi <id>", "poi [n], poi <kind>, poi show <id>");
        v("memory", "", "info", O, "memory", "how my note files are", "memory", "status");
        // ---- other (owner only)
        v("say", "", "other", O, "say <text>", "say it in public chat (never a command)", "say hello", "status");
        v("twerk", "", "other", O, "twerk | twerk <seconds>", "crouch on and off (again to stop)", "twerk|twerk 15", "stop");
        v("allow", "", "other", O, "allow <name>", "take orders from that player too (the guest commands)", "allow Steve", "allowed");
        v("deny", "", "other", O, "deny <name>", "stop taking orders from that player", "deny Steve", "allowed");
        v("allowed", "", "other", O, "allowed", "who I take orders from", "allowed", "allow <name>");
        v("b", "baritone", "other", O, "b <baritone command>", "a raw Baritone command (careful: never goto <block name>)", "b set allowSprint true", "status");
        v("debug", "", "other", O, "debug | debug gui|inv|baritone | debug block x y z | debug blocks x1 y1 z1 x2 y2 z2 [at <time>] | debug events [n] | debug guard x y z | debug changes x y z [r] [since <time>] | debug trail [minutes] | debug incident [n]",
                "read-only looks inside the game (from the laptop or the dashboard, not by PM)", "debug inv|debug incident", "debug");
        v("watch", "", "other", O, "watch | watch off | watch status | watch distance 1-8 | watch tunnel [off|status|height 1-40|turn left|right|dollhouse [on|off]|cut [on|off|status|mode cone|outline|radius N]] | watch dollhouse [on|off] | watch cut [on|off|status|mode cone|outline|radius N] | watch steer [on|off|status] | watch turn [on|off|status|rate 5-180] | watch seen [on|off|status] | watch shot | watch probe [off|status]",
                "watch: a camera behind the bot that follows its walking direction; watch tunnel: a camera above it that passes through blocks, the real world hidden, only what the bot's own view saw (tunnels, caves, the ground and trees round it out to the render distance), the bot and mobs drawn (no x-ray); dollhouse (the default): only floors and far walls facing the camera; cut (on by default): nothing between the camera and the bot is drawn; cut mode cone (the default) removes whole blocks in a cone from the camera to the bot (radius 1-6 at the bot, default 2.5; never the floor under its feet), cut mode outline the older bot-shaped hole (radius 0-3 = margin, default 0.6), each radius kept per mode; steer (on by default, active only while a watch view is on): W/S/A/D move the bot away from/towards/left/right of the camera, never while the bot drives itself; turn (on by default): A/D also turn the camera (45 deg/s, watch turn rate 5-180), the arrow keys only turn it; dollhouse and the other watch settings are kept across restarts; watch seen: the faces it records drawn cyan; render only, 60 FPS while on, watch off for the normal view",
                "watch|watch tunnel|watch tunnel dollhouse on|watch cut mode cone|watch cut radius 3|watch seen", "watch off");
    v("mouse", "", "other", O, "mouse free|grab", "let the bot's window keep (grab) or release (free) the mouse", "mouse free", "status");
        v("recorder", "", "other", O, "recorder | recorder off|light|normal|detailed|max | recorder <preset> for <N>m|<N>h | recorder range <chunks> | recorder trail <ticks> | recorder snapshot <blocks>|now | recorder states on|off | recorder keep <hours> | recorder mark <note>",
                "the flight recorder: what the bot saw and did, kept for a while", "recorder|recorder detailed for 30m", "debug incident");
    }

    /** Every verb in table order. */
    public static List<Verb> all() { return List.copyOf(BY_NAME.values()); }

    /** The verb for a word (its name or an alias, any case), or null. */
    public static Verb of(String word) {
        return word == null ? null : BY_WORD.get(word.trim().toLowerCase(Locale.ROOT));
    }

    /** The name and every alias. */
    public static List<String> words(Verb v) {
        List<String> out = new ArrayList<>();
        out.add(v.name());
        out.addAll(v.aliases());
        return out;
    }

    /** The verbs of one section, table order. */
    public static List<Verb> section(String s) {
        List<Verb> out = new ArrayList<>();
        for (Verb v : BY_NAME.values()) if (v.section().equals(s)) out.add(v);
        return out;
    }

    /**
     * The nearest known word for a mistyped one ("minee" -> "mine"), or null: a word that starts the same way, else
     * one at most 2 edits away (1 for words of 4 letters or less).
     */
    public static String suggest(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty() || BY_WORD.containsKey(w)) return null;
        String best = null;
        int bestD = Integer.MAX_VALUE;
        int max = w.length() <= 4 ? 1 : 2;
        for (String k : BY_WORD.keySet()) {
            if (k.length() < 2) continue;
            int d = k.startsWith(w) && w.length() >= 3 ? 0 : distance(w, k);
            if (d <= max && d < bestD) {
                bestD = d;
                best = BY_WORD.get(k).name();
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
