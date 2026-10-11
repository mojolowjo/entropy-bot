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
    public static final List<String> SECTIONS = List.of("space", "stocking", "digging", "fighting", "jobs", "materials", "exploring", "brain", "moving", "chests", "crafting", "other");

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
        // ---- space: places, markers, areas (VOCABULARY 1)
        v("place", "", "space", O, "place <name> [x y z] [north|south|east|west] | place <block> x y z",
                "remember a place (where you stand, or the coordinates; place mine north = a mine, place food = the food chest, place base = the base); with a block and three numbers: put one block there",
                "place farm|place mine north|place cobblestone 10 64 20", "places");
        v("marker", "", "space", O, "marker <name> [of <place>] [x y z]", "a point under a place (default: the nearest place within 32), e.g. the furnace", "marker furnace|marker door of base", "go <place> <marker>");
        v("places", "", "space", G, "places | places forget <place|marker>", "the places I know, each with its markers (forget: the owner)", "places|places forget farm", "go <place> [marker]");
        v("area", "", "space", P, "area here <r> <name> [type] [down up] | area x z x2 z2 <name> [type] [y1 y2] | area change name <name> <new> | area change type <name> <type> "
                        + "| area del <name> confirm | area list | area show <name> | area near [<r>|on|off|status] | area candidates [accept <n> [name] | reject <n>|all]",
                "named areas with a type: neutral (white, the default: walk, dig natural blocks), destroy (red: dig <area> breaks built blocks too, never chests), "
                        + "main (blue: the base; built blocks never), safe (green: walk only, never dig or build: someone else's); here = around you (r blocks each way; safe: 8 below, 16 above), "
                        + "and the near-me zone (16 blocks around you, on by default; safe areas always win); candidates: the bases explore and find noted, accept makes one a safe area",
                "area here 60 base main|area here 8 house safe|area 0 0 30 30 pit destroy 40 70|area change type pit neutral|area list", "fence", "area list");
        v("fence", "", "space", P, "fence | fence vetoes | fence check x y z break|place|go | fence mode strict | fence mode log confirm",
                "the fence: its mode, the areas, what it refused, dry runs (naming the area and its type)",
                "fence|fence check 10 64 20 break", "area list", "fence, fence vetoes");
        // ---- stock (VOCABULARY 2)
        v("fetch", "", "stocking", O, "fetch <item|kind> [n]", "take it from storage (else gather it), then bring it to you", "fetch torch 32|fetch logs 16", "inv");
        v("need", "", "stocking", O, "need <item|kind> <n> | need <item>", "a standing need (the brain works toward it: it gathers when it has under half of n, then up to n); without a count: what it takes and what is missing (that becomes cost <item> in 0.24)",
                "need torch 64|need refinedstorage:basic_processor", "needs");
        v("needs", "", "stocking", O, "needs | needs clear <item>|all", "the standing needs with have/want (the group's stock), and the brain's need scores", "needs|needs clear torch", "need <item> <n>");
        v("goal", "", "stocking", O, "goal camp | goal stone tools | goal iron tools | goal iron <n> | goal food <n> | goal wood <n> | goal light <area> | goal bed | goal furnace | goal table | goal shelter | goal <item> <n> | goal show [name]",
                "a longer task: the planner finds a chain of verbs for it (cut, craft, place, smelt, mine, fetch...), saves it as the routine goal_<name> (yours to read, edit or delete; an edited one is kept and used) and runs it now, or notes it for the brain when I'm busy; no way -> what is missing",
                "goal bed|goal iron 16|goal stone tools|goal show bed", "goals");
        v("goals", "", "stocking", O, "goals | goals clear <n>|all", "the goals waiting for the brain", "goals|goals clear 1", "goal <text>");
        v("have", "", "stocking", G, "have [item]", "what we have as a group (my bag, the chests, the RS network, your bag) and which chest has it", "have iron_ingot|have", "fetch <item> [n]");
        v("stock", "", "stocking", O, "stock [filter] | stock targets | stock set <item> <n> | stock clear <item>|all",
                "the group's totals, biggest first, and how fresh each source is; targets/set/clear: how much the base chests should hold",
                "stock ingot|stock set torch 64|stock targets", "have <item>");
        v("get", "", "stocking", O, "get <item|kind> [n]", "fetch from the chests or the RS network", "get coal 16|get logs 32", "inv");
        v("supplies", "", "stocking", O, "supplies | supplies set <item n, ...> | supplies clear", "what I always carry (set replaces the whole list)",
                "supplies set torch 32, bread 16, iron_pickaxe 1|supplies", "supplies");
        v("deposit", "", "stocking", O, "deposit [item ...]", "put loot away in the base chests (keeps tools, armor, food, supplies)", "deposit|deposit cobblestone dirt", "inv");
        v("restock", "", "stocking", O, "restock", "top my supplies up: from storage first, the rest crafted", "restock", "supplies");
        v("hold", "", "stocking", G, "hold this", "pick up the items you throw me in the next 15 s (within 4 blocks)", "hold this", "inv");
        v("give", "", "stocking", O, "give me <item> [n] | give <player> <item> [n]", "walk to you (or them) and throw the items (never my tools, armor, last 8 food, 16 torches or supplies)",
                "give me cobblestone 32|give Steve bread 4", "inv");
        v("carry", "", "stocking", O, "carry <item> [item ...] | carry all | carry off | carry list", "follow you and pick up those drops (all: every drop) within 6 blocks of you; off = plain follow",
                "carry oak_log cobblestone|carry off", "unload");
        v("unload", "", "stocking", O, "unload", "go home, deposit (keeping tools, food, torches, supplies), then come back to you", "unload", "carry list");
        // ---- digging (VOCABULARY 3)
        v("mine", "", "digging", O, "mine strip [<ores>] [n] [at <mine>] | mine strip status|reset|turn left|right|ores collect|list | mine cave <ores> [n | <min>m] [at <cave>] | mine <ore> [n] [dig]",
                "strip-mine at the mine (place mine north starts one; reset asks to confirm), go caving, or mine ores in view (dig: may dig to them); ores: iron,diamond or iron_ore or any or the kind ores; a strip mine needs room in its area: 17 blocks each side of the corridor (16-long branches + torches) and the corridor's length ahead, else it turns at the edge (blocked:area)",
                "mine strip iron,diamond 16|mine strip|mine cave ores 20 10m|mine iron_ore 10", "deposit");
        v("dig", "", "digging", O, "dig <area> [-N|+N] [ores] [junk drop] [water [large]] | dig x1 y1 z1 x2 y2 z2 [ores] [force] [floor [block]] [junk drop] [water [large]] | dig x1 z1 x2 z2 -N|+N [same words, no floor] | dig stairs north|south|east|west <n>",
                "dig stairs <dir> <n>: a 1-wide walkable staircase n steps down from where I stand, a torch every 6 (also how death reaches a corpse down a shaft); dig <area>: the box of an area with heights (an all-heights area needs -N or +N; asks to confirm; a destroy area loses built blocks too, never chests; never a safe area); "
                        + "a box the careful way (20000 blocks max; over 1000 asks to confirm; force: built blocks, 64 max); water or lava in the way "
                        + "ends it \"blocked by water at x y z\"; water: seal the water off with junk blocks and dig on (large: a big body of water too); "
                        + "any coordinate may be ~ or ~N (from my feet); -N: each column's surface block and N-1 below, +N: the N blocks above the surface (N 1-64)",
                "dig 10 60 10 20 64 20 ores|dig pit -3|dig ~-2 ~ ~-2 ~2 ~-5 ~2|dig ~-8 ~-8 ~8 ~8 +10|dig 247 -46 853 310 -44 855 floor junk drop water", "deposit");
        v("build", "", "digging", O, "build floor|walls|shell|fill <block> <area>", "build inside an area with heights (never breaks a block)", "build floor cobblestone yard", "status");
        v("cut", "", "digging", O, "cut <n> [logs|<log type>] | cut trees <n> [log type] | cut status",
                "fell trees in my areas for n logs (logs: any kind but what kinds logs leaves out), never next to builds; pick up, replant; 20 min at most", "cut 16|cut 16 logs|cut trees 3 birch", "deposit");
        v("gather", "", "digging", O, "gather <item|kind> [n] [<min>m] | gather status | gather sources [item] | gather source <item> <command with {n}> | gather source <item> clear",
                "get n of an item into my bag: from storage first, else crafted or smelted, the raw items mined (strip mine, ore in view, cave), "
                        + "cut or farmed, step by step; 60 min at most, 3 failed tries at one thing stop it",
                "gather iron_ingot 16|gather oak_planks 32 20m|gather sources torch", "deposit");
        v("light", "", "digging", O, "light here <r> | light x1 z1 x2 z2", "torches on the ground every 6 blocks, inside my areas (fetches or crafts torches first)",
                "light here 8|light 0 0 30 30", "status");
        v("junk", "", "digging", O, "junk list | junk add <item> ... | junk remove <item> ... | junk default | junk mode drop|chest",
                "what I throw away when my bag is nearly full mid-job (or put in the chest placed as junk)", "junk list|junk add tuff|junk mode chest", "junk list");
        // ---- fighting (VOCABULARY 4)
        v("defence", "", "fighting", O, "defence on|off | defence creepers flee|melee|bow | defence hostile [list|add <id>...|remove <id>...] | defence players on|off",
                "self-defence (fights monsters and hostile-list mobs, hits back at what hits me, never pets; players only with defence players on, which is off at every game start; avoids creepers, retreats under 6 health)",
                "defence on|defence hostile add arphex:spider_jump", "defend");
        v("defend", "", "fighting", O, "defend", "hold this spot: stay within 4 blocks of where I stand and fight what comes, until dismiss, stop or another order",
                "defend", "dismiss");
        v("guard", "", "fighting", O, "guard <area|place|marker>", "stay in that area (or within 8 of the place or marker) and fight what comes, until dismiss, stop or another order",
                "guard base|guard farm gate", "dismiss");
        v("escort", "", "fighting", P, "escort [player] [radius] | escort me [radius] | escort status",
                "go with that player (you when not given) and fight monsters near them (radius 6, 2-16), stand between them and a creeper, throw food when they're hungry; until dismiss",
                "escort|escort me 10|escort Steve", "dismiss", "escort me [radius], escort status");
        v("assist", "", "fighting", O, "assist | assist off | assist status",
                "help you with what you do (your companion mod 0.3.1 tells me): follow you and fight what threatens either of us; chopping: the half-cut trees near your last log, else cut that kind; "
                        + "mining: that ore or stone within 12 of your last block; farming: a farm round when the farm place is near you; building: hand you the block you place when you run low; "
                        + "idle: follow and pick up drops (after 60 s idle: just follow); a new activity counts after 5 s; whispers only when it changes; off, dismiss or stop ends it; brain copy on|off is the same",
                "assist|assist status|assist off", "dismiss");
        v("dismiss", "", "fighting", G, "dismiss", "end the escort (you, or the player I escort), assist and defend/guard (the owner)", "dismiss", "status");
        v("attack", "", "fighting", O, "attack <mob kind|player|entity id> [confirm] | attack nearest | attack target <id>",
                "fight one mob: hostile goes; a passive one asks confirm unless you type its kind; a named one asks confirm (a name has letters, not just digits); a player never unless defence players on; never pets. Ends when it dies, leaves 24 blocks or after 30 s",
                "attack zombie|attack nearest|attack 812 confirm", "defence");
        // ---- queue and status (VOCABULARY 5)
        v("queue", "", "jobs", G, "queue | queue <task> | queue clear", "what runs and what waits; queue a task with a finish after the current one (never escort, follow, defend, guard, repeat forever; the owner)",
                "queue|queue craft torch 16", "status");
        v("status", "", "jobs", G, "status", "where I am, health, food, the job, deaths in the last hour and the game stage", "status", "inv");
        v("inv", "", "jobs", G, "inv", "what I carry", "inv", "deposit");
        v("stop", "", "jobs", G, "stop", "cancel everything, breaking off (the queue stays: queue clear)", "stop", "status");
        v("routine", "routines", "jobs", O, "routines | routine save <name> <chain> | routine show <name> | routine delete <name>", "saved chains; say the name to run one",
                "routine save night deposit then eat then go base|routines", "<name>");
        v("rule", "rules", "jobs", O, "rules | rule every <n>m|h do <cmds> | rule at HH:MM do <cmds> | rule when full do <cmds> | rule when idle <n>m do <cmds> | rule when night|day do <cmds> | rule delete <n>",
                "things I do by myself when idle", "rule every 30m do farm then deposit|rule when full do deposit", "rules");
        v("repeat", "", "jobs", O, "repeat [n|forever] <routine or chain>", "loop it (a round at most every 10 s)", "repeat forever farm|repeat 3 night", "queue");
        v("wait", "", "jobs", O, "wait <seconds>", "a pause (a step in a chain)", "deposit then wait 30 then farm", "queue");
        v("confirm", "", "jobs", O, "confirm", "run the big job I just asked about (within 30 s; anything else cancels it)", "confirm", "status");
        v("summary", "", "jobs", O, "summary", "the day so far: items gathered and deposited, jobs done and failed, deaths, needs met, blocks put back, distance walked, the brain's top picks (also whispered at dawn, and in summary.json)", "summary", "check");
        v("check", "", "jobs", O, "check", "a self-test: what I miss to work on my own, each with the command that fixes it", "check", "check");
        v("why", "", "jobs", G, "why | why threats", "the brain's last decision: the branch, each need's score, what was skipped (and why), the stage; why threats: the threat test's last fight-or-flee verdict, each mob near now (aggro y/n, path N (straight M), distance -1/s -> counts|noted) and the last 32 changes",
                "why|why threats", "status");
        v("deaths", "", "jobs", P, "deaths | death policy on|off", "deaths in the last hour; fetch my corpse after a death or not (5 deaths an hour park me at base)", "deaths", "resume", "deaths");
        v("resume", "", "jobs", O, "resume", "carry on after 5 deaths in an hour parked me", "resume", "status");
        // ---- kinds and tools (VOCABULARY 6)
        v("kinds", "", "materials", O, "kinds | kinds <kind> | kinds <kind> exclude|include <id>",
                "the kind-words logs, wood, ores, stone, food, seeds (usable for any item word: mine, get, cut, gather, need, fetch) and what each leaves out", "kinds|kinds logs exclude cherry_log", "cut 16 logs");
        v("tools", "", "materials", O, "tools | tools mode best|cheapest|stone", "which tool I use: best (the default), cheapest (wears out first), stone (stone for all but ores that need more); also the tool and armour care lines (worn pieces are replaced at a pause)", "tools mode stone|tools", "tools");
        v("hotbar", "", "materials", O, "hotbar | hotbar set <slot> <kind|item> ... | hotbar clear <slot>|all", "keep tools in hotbar slots (pickaxe, sword, axe, shovel, hoe, food, torch, or an item)",
                "hotbar set 1 pickaxe 2 sword 3 food 4 torch", "hotbar");
        // ---- explore and find (VOCABULARY 6b)
        v("explore", "", "exploring", O, "explore [north|south|east|west] [minutes] [gather off]",
                "walk land I've never seen and not on the map (that way), inside or outside my areas, then home; out there I cut a tree or mine a surface ore on the way (gather off: not), the report says what I took; someone's base: I keep 16 off, take nothing, and tell you", "explore 5|explore north 10|explore west 5 gather off", "places");
        v("find", "", "exploring", G, "find <block> | find nearest <poi kind|ore> | find cave|<poi kind>|<biome> [minutes]",
                "find <block>: the nearest block of that kind; find cave, village, mineshaft, a biome...: walk until I find one (the owner), then note it as a point of interest", "find chest|find village|find cave|find cherry_grove", "go poi <id>");
        v("scout", "", "exploring", O, "scout <north|south|east|west|x z> [n] [<min>m] [from me] | scout status",
                "walk up to n blocks (64, max 256) that way inside my areas, come back and report places, ores and mobs seen", "scout north 64|scout 120 -40", "find nearest <poi kind|ore>");
        // ---- the brain's words (VOCABULARY 7, BRAIN_LOOP)
        v("brain", "", "brain", O, "brain on|off|status | brain copy on|off|status | brain get [key] | brain set <key> <value> | brain reset <key>|all | brain tree [reload]", "the brain: every 2 s it scores the needs (safety, food, tools, bag, your needs and goals, copy, the idle list) and starts the best job; it whispers only when it starts one (with why), when one fails 3 times (parked 30 min), and stays within 32 of you until done; off stops its own job, yours run on; copy: chop, mine or farm what you do near you; get/set/reset: its weights and thresholds (saved in brain-config.json; need.<name>.weight 0-5); tree reload: loads brain-tree.override.json (refused: the built-in tree, check says why)",
                "brain on|brain status|brain copy on", "why");
        v("idle", "", "brain", O, "idle list | idle list set <items> | idle list reset", "what the brain does when nothing is asked, in order, skipping what cannot run: restock, strip (the marked mine), cave, farm, explore (default restock, strip, cave, farm)",
                "idle list|idle list set strip, cave", "brain on");
        v("plan", "", "brain", O, "plan <goal>", "a dry run of goal <goal>: the chain the planner finds with each step's cost, nothing runs (or what is missing)", "plan furnace|plan iron tools", "goal <goal>");
        v("actions", "", "brain", O, "actions | actions <verb>", "the action table: what each verb needs and gives, which ones the planner chains; all of it with the state is entropybot/actions.json (for a model)",
                "actions|actions craft", "plan <goal>");
        v("done", "free", "brain", O, "done | free", "I have nothing for you: do what you want (the brain may leave 32 blocks of you); your next order, come or escort takes it back", "done", "status");
        v("sleep", "", "brain", O, "sleep | sleep status | sleep auto on|off", "go to the nearest bed (or put mine down at night) and sleep until morning or stop; auto: go to bed when others sleep (the brain, default on)",
                "sleep|sleep auto off", "status");
        v("shelter", "", "brain", O, "shelter | shelter 5m | shelter keep | shelter status | shelter leave",
                "build a 1x2 shell around me from my planks, cobblestone or dirt (cut logs first when short), a torch inside, wait until day (or the timer), open my own wall and step out; keep: close it behind me; \"night status\" shows how safe a night out is (score, band, time to dusk; brain set night.shelterBelow|night.workBelow|night.duskHours <n>)",
                "shelter|shelter status", "sleep");
        v("hunting", "", "brain", O, "hunting | hunting on|off", "may I hunt farm animals (default off; gather food and the food need hunt when on)", "hunting on", "hunt");
        v("hunt", "", "brain", O, "hunt <n> [cow|pig|chicken|sheep] | hunt status",
                "with hunting on: kill the allowed animals (kinds animals; never pets, named ones, babies, villagers or players) within 32 inside my areas until I have n raw meat, picking up the drops; then cook",
                "hunt 3 cow", "hunting");
        v("camp", "", "brain", O, "camp here", "a camp for a night or two: area camp (24 round, neutral), place camp with a bed marker, torches, my bed when I carry one; not a base", "camp here", "sleep");
        v("bootstrap", "", "brain", O, "bootstrap | bootstrap status", "a camp from nothing: cut, table, wooden then stone tools, furnace, chest, charcoal, torches, base and camp placed",
                "bootstrap|bootstrap status", "places");
        v("restore", "", "brain", O, "restore [status] | restore now [r] | restore forget <n>|all confirm | restore ignore x y z | restore mode auto|manual|off",
                "blocks I broke on the way (mine ... dig tunnels, digging out of a stuck spot) and put back; build hints to protect",
                "restore status|restore now|restore ignore 10 64 -20", "status");
        // ---- moving
        v("come", "", "moving", G, "come", "walk to you (out of view: the companion mod's position)", "come", "follow");
        v("follow", "", "moving", G, "follow [name]", "follow you (or that player) until stop", "follow|follow Steve", "stop");
        v("goto", "", "moving", G, "goto x y z | goto x z | goto me", "walk to a spot (the owner's own goto may leave my areas)", "goto 120 64 -300", "status");
        v("go", "", "moving", O, "go <place> [marker] | go <marker> | go poi <id>", "walk to a place, a marker or a point of interest (far: /home first)", "go farm|go base furnace|go poi 11", "places");
        v("server", "", "moving", O, "server commands [on|off|status]", "whether I may send the server's slash commands (/home, /sethome): off (the default) = I walk home instead and sethome refuses; check lists the jobs that would have used one", "server commands|server commands on", "home");
        v("path", "", "moving", O, "path [status] | path assist on|off|status | path maxWalk <blocks> | path penalties clear", "how I walk: far walks (over 48 blocks or 10 levels) go leg by leg through my long-route process (assist on, the default; off: plain Baritone); maxWalk (default 2000): a walk further than that, straight, is refused at once; status: command-to-first-move, chain gaps, legs, fails, penalised route edges, ready paths", "path status|path assist off", "goto x y z");
        v("home", "", "moving", O, "home", "go home: the server's /home with server commands on, else a walk home", "home", "go base");
        v("death", "", "moving", O, "death", "walk back to where I died and empty my corpse", "death", "deaths");
        v("corpse", "", "moving", O, "corpse", "empty my own corpse when it is near", "corpse", "inv");
        // ---- chests and storage
        v("open", "", "chests", O, "open x y z | open <place>", "walk to a chest and open it (lists what is inside)", "open -28 54 189|open bulk then take spruce_log 192 then close", "take <item> [n]");
        v("take", "", "chests", O, "take <item|all> [n]", "take from the open chest (exact counts)", "take charcoal 64", "close");
        v("put", "", "chests", O, "put <item|all> [n]", "put into the open chest", "put all|put charcoal 50", "close");
        v("close", "", "chests", O, "close", "close the open chest", "close", "inv");
        v("trust", "", "chests", O, "trust | trust x y z|<place>", "list the chests I keep out of, or let me use one again", "trust", "untrust x y z");
        v("untrust", "", "chests", O, "untrust x y z|<place>", "keep me out of a chest (craft trips, deposits, the food run)", "untrust -20 53 180", "trust");
        v("drop", "", "chests", O, "drop <item|all> [n]", "throw items on the ground", "drop dirt 64", "inv");
        v("wear", "", "chests", O, "wear", "put on armor from my bag", "wear", "inv");
        v("scan", "", "chests", O, "scan [radius] | scan base|<place>|x y z [radius]", "open the chests around (there) and remember them", "scan base|scan 8", "have <item>");
        v("rs", "", "chests", O, "rs [x y z] | rs take <item> [n] | rs put <item|all> [n] | rs disks [x y z]", "the Refined Storage network: read it, take, put, the disks",
                "rs|rs take bread 32", "have <item>");
        v("pots", "", "chests", O, "pots [chests]", "empty the botany pots at the base into the RS network (or the chests)", "pots", "rs");
        // ---- crafting, smelting, the farm
        v("craft", "", "crafting", O, "craft <item> [n] | craft <material> armor|tools | craft a, b 16", "craft it, fetching materials and making the parts (table and furnace as needed)",
                "craft stick 16|craft copper armor", "inv");
        v("kit", "", "crafting", O, "kit <material>", "craft that material's armor and tools, then wear the armor", "kit copper", "inv");
        v("recipe", "", "crafting", O, "recipe <item>", "what an item needs", "recipe hopper", "need <item>");
        v("smelt", "", "crafting", O, "smelt <item> [n] | smelt jobs | smelt collect [all] | smelt mode efficient|wait | smelt forget <#|all>",
                "smelt at a base furnace and go on with other things", "smelt iron_ingot 9|smelt jobs", "smelt collect");
        v("cook", "", "crafting", O, "cook <food> [n]", "cook raw food at a furnace (smelt cooked_<food>)", "cook beef 8", "smelt jobs");
        v("eat", "", "crafting", O, "eat", "eat now (I also eat by myself)", "eat", "place food");
        v("farm", "", "crafting", O, "farm | farm here | farm status | farm mode modded|vanilla|auto | farm grow twerk on|off|auto | farm plant <crop> [x1 z1 x2 z2 | here <r>] | farm compact block|prudentium|off",
                "one farm round (harvest, replant or let Harvest with Ease replant, pick up); plant a new field", "farm|farm plant wheat here 4|farm status", "deposit");
        v("compact", "", "crafting", O, "compact <item> [here|<place>|x y z]", "turn 9 (or 4) into a block in the chests near you (or me)", "compact inferium_essence", "have <item>");
        v("infuse", "", "crafting", O, "infuse <seed> [n]", "make seeds on the infusion altar (never touches what isn't mine)", "infuse silicon 2", "inv");
        v("upgrade", "", "crafting", O, "upgrade <essence> [n]", "climb the essence tiers with the infusion crystal", "upgrade imperium 4", "inv");
        // ---- other
        v("help", "", "other", G, "help | help <verb> | help <page> | help all", "this guide: one verb, one page of it, or all", "help mine|help 2", "status");
        v("say", "", "other", O, "say <text>", "say it in public chat (never a command)", "say hello", "status");
        v("twerk", "", "other", O, "twerk | twerk <seconds>", "crouch on and off (again to stop)", "twerk|twerk 15", "stop");
        v("spawn", "", "other", O, "spawn", "walk to the nearest bed and set my respawn point there", "spawn", "status");
        v("allow", "", "other", O, "allow | allow <name>", "who I take orders from; allow a player too (the guest commands)", "allow|allow Steve", "deny <name>");
        v("deny", "", "other", O, "deny <name>", "stop taking orders from that player", "deny Steve", "allow");
        v("restart", "", "other", G, "restart ok|no", "I may be closed for an update in the next 15 minutes (or not)", "restart ok", "status");
        v("blind", "", "other", O, "blind on|off|status", "stop drawing frames while the bot works on (less CPU and GPU; the window title shows where it is and its job); off draws again; watch needs it off",
                "blind on|blind status", "blind status");
        v("surface", "", "other", O, "surface | surface status | surface on|off", "the ground round the bot for the dashboard's 3D view (one file per loaded chunk; on by default)", "surface status", "status");
        v("debug", "", "other", O, "debug gui|inv|baritone|mobs | debug block x y z | debug blocks x1 y1 z1 x2 y2 z2 [at <time>] | debug events [n] | debug guard x y z | debug threats [x y z] | debug changes x y z [r] [since <time>] | debug trail [minutes] | debug incident [n] | debug hits [n] "
                        + "| debug <plumbing verb> ...: b, memory, watch, recorder, mouse, route, autominer, reconnect, sethome, use, caves, ores, poi",
                "read-only looks inside the game, and the plumbing verbs that left the main list (they work as before after the word debug: debug autominer on, debug b set allowSprint true, debug watch tunnel)",
                "debug inv|debug autominer on|debug watch|debug autominer status", "debug");
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
