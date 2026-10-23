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
                       String next, String guestForms, List<Form> forms) {}

    /** 0.39.z27: one form of a verb with its one-line meaning (the area row has them: aa help's "areas and tags"). */
    public record Form(String form, String meaning) {}

    /** The longest one-line meaning in a help list (owner 2026-10-23). */
    public static final int MEANING_MAX = AaCommands.MEANING_MAX;

    /** The sections of the guide, in page order ("help 1" .. "help N"). */
    public static final List<String> SECTIONS = List.of("space", "stocking", "digging", "fighting", "jobs", "materials", "exploring", "brain", "moving", "chests", "crafting", "other");

    private static final Map<String, Verb> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, Verb> BY_WORD = new LinkedHashMap<>();

    private static void v(String name, String aliases, String section, Who who, String usage, String what, String examples, String next) {
        v(name, aliases, section, who, usage, what, examples, next, null);
    }

    /** aliases and examples: "|"-separated ("" = none). */
    private static void v(String name, String aliases, String section, Who who, String usage, String what, String examples, String next, String guestForms) {
        v(name, aliases, section, who, usage, what, examples, next, guestForms, List.of());
    }

    /** A verb whose usage is its forms, each with a meaning (usage = the forms joined by " | "). */
    private static void v(String name, String aliases, String section, Who who, List<Form> forms, String what, String examples, String next, String guestForms) {
        List<String> u = new ArrayList<>();
        for (Form f : forms) {
            if (f.meaning().length() > MEANING_MAX || f.meaning().contains("\n")) throw new IllegalStateException(name + " form " + f.form() + ": meaning over one line");
            u.add(f.form());
        }
        v(name, aliases, section, who, String.join(" | ", u), what, examples, next, guestForms, forms);
    }

    private static void v(String name, String aliases, String section, Who who, String usage, String what, String examples, String next, String guestForms, List<Form> forms) {
        List<String> al = aliases.isEmpty() ? List.of() : List.of(aliases.split("\\|"));
        List<String> ex = examples.isEmpty() ? List.of() : List.of(examples.split("\\|"));
        Verb verb = new Verb(name, al, section, who, usage, what, ex, next, guestForms, List.copyOf(forms));
        if (!SECTIONS.contains(section)) throw new IllegalStateException("no section " + section);
        if (BY_NAME.put(name, verb) != null) throw new IllegalStateException("twice: " + name);
        for (String w : words(verb)) if (BY_WORD.put(w, verb) != null) throw new IllegalStateException("twice: " + w);
    }

    static {
        Who G = Who.GUEST, P = Who.PARTLY, O = Who.OWNER;
        // ---- space: places, markers, areas (VOCABULARY 1)
        v("marker", "", "space", O, "marker <name> [of <place>] [x y z]", "a point under a place (default: the nearest place within 32), e.g. the furnace", "marker furnace|marker door of base", "go <place> <marker>");
        v("places", "", "space", G, "places | places forget <place|marker>", "the places I know, each with its markers (forget: the owner)", "places|places forget farm", "go <place> [marker]");
        v("area", "", "space", P, List.of(
                        new Form("area here <r> <name> [type] [down up]", "an area around me, r blocks each way (type: neutral, destroy, main, safe)"),
                        new Form("area x z x2 z2 <name> [type] [y1 y2]", "an area between two corners"),
                        new Form("area change name <name> <new>", "rename an area"),
                        new Form("area change type <name> <type>", "change an area's type"),
                        new Form("area tag <name> <tag...>", "add tags: break-structures, use-stations"),
                        new Form("area untag <name> <tag...>", "remove those tags"),
                        new Form("area del <name> confirm", "delete an area"),
                        new Form("area list", "the areas I know"),
                        new Form("area show <name>", "show one area"),
                        new Form("area near [<r>|on|off|status]", "the near-me zone (16 blocks around you, on by default)"),
                        new Form("area candidates [accept <n> [name] | reject <n>|all]", "the bases explore found; accept makes one a safe area")),
                "named areas with a type: neutral (white, the default: walk, dig natural blocks), destroy (red: dig <area> breaks built blocks too, never chests), "
                        + "main (blue: the base; built blocks never), safe (green: walk only, never dig or build: someone else's); here = around you (r blocks each way; safe: 8 below, 16 above), "
                        + "and the near-me zone (16 blocks around you, on by default; safe areas always win); candidates: the bases explore and find noted, accept makes one a safe area; "
                        + "tags: break-structures (the structure blocks inside may be broken), use-stations (its stations are used first); a structure block outside every area is never broken unless I placed it",
                "area here 60 base main|area tag house break-structures use-stations|area here 8 house safe|area 0 0 30 30 pit destroy 40 70|area change type pit neutral|area list", "fence", "area list");
        v("fence", "", "space", P, "fence | fence vetoes | fence check x y z break|place|go | fence rules | fence why x y z [break|place] [travel|mine|dig_out] | fence mode strict | fence mode log confirm",
                "the fence: its mode, the areas, what it refused, dry runs (naming the area and its type); rules = the break policy (intent, cost hook, counters), why = its verdict for one cell",
                "fence|fence check 10 64 20 break", "area list", "fence, fence vetoes");
        // ---- stock (VOCABULARY 2)
        v("needs", "", "stocking", O, "needs | needs clear <item>|all", "removed in 0.38.b7 (the old brain): AA does this (aa on, aa want <thing>)", "needs|needs clear torch", "need <item> <n>");
        v("goals", "", "stocking", O, "goals | goals clear <n>|all", "the goals waiting for the brain", "goals|goals clear 1", "goal <text>");
        v("have", "", "stocking", G, "have [item]", "what we have as a group (my bag, the chests, the RS network, your bag) and which chest has it", "have iron_ingot|have", "fetch <item> [n]");
        v("stock", "", "stocking", O, "stock [filter]",
                "the group's totals, biggest first, and how fresh each source is",
                "stock ingot|stock", "have <item>");
        v("supplies", "", "stocking", O, "supplies | supplies set <item n, ...> | supplies clear", "what I always carry (set replaces the whole list)",
                "supplies set torch 32, bread 16, iron_pickaxe 1|supplies", "supplies");
        // ---- digging (VOCABULARY 3)
        v("mine", "", "digging", O, "mine cave <ores> [n | <min>m] [at <cave>] | mine <ore> [n] [dig]",
                "go caving, or mine ores in view (dig: may dig to them); ores: iron,diamond or iron_ore or any or the kind ores; a strip mine is aa want strip <ore> [<feet y>] [<heading>]",
                "mine cave ores 20 10m|mine iron_ore 10", "aa want strip");
        v("junk", "", "digging", O, "junk list | junk add <item> ... | junk remove <item> ... | junk default | junk mode drop|chest",
                "the items my deposit puts in the chest placed as junk (junk mode chest); nothing throws them away mid-job", "junk list|junk add tuff|junk mode chest", "junk list");
        // ---- fighting (VOCABULARY 4)
        v("defence", "", "fighting", O, "defence on|off | defence hostile [list|add <id>...|remove <id>...] | defence players on|off",
                "self-defence (fights monsters and hostile-list mobs, hits back at what hits me, never pets; players only with defence players on, which is off at every game start; avoids creepers, retreats under 6 health)",
                "defence on|defence hostile add arphex:spider_jump", "defend");
        v("defend", "", "fighting", O, "defend", "hold this spot: stay within 4 blocks of where I stand and fight what comes, until dismiss, stop or another order",
                "defend", "dismiss");
        v("guard", "", "fighting", O, "guard <area|place|marker>", "stay in that area (or within 8 of the place or marker) and fight what comes, until dismiss, stop or another order",
                "guard base|guard farm gate", "dismiss");
        v("dismiss", "", "fighting", G, "dismiss", "end assist and defend/guard (the owner)", "dismiss", "status");
        // ---- queue and status (VOCABULARY 5)
        v("queue", "", "jobs", G, "queue | queue <task> | queue clear", "what runs and what waits; queue a task with a finish after the current one (never follow, defend, guard, repeat forever; the owner)",
                "queue|queue craft torch 16", "status");
        v("status", "", "jobs", G, "status", "where I am, health, food, the job, deaths in the last hour and the game stage", "status", "inv");
        v("inv", "", "jobs", G, "inv", "what I carry", "inv", "deposit");
        v("routine", "routines", "jobs", O, "routines | routine save <name> <chain> | routine show <name> | routine delete <name>", "saved chains; say the name to run one",
                "routine save night deposit then eat then go base|routines", "<name>");
        v("rule", "rules", "jobs", O, "rules | rule every <n>m|h do <cmds> | rule at HH:MM do <cmds> | rule when full do <cmds> | rule when idle <n>m do <cmds> | rule when night|day do <cmds> | rule delete <n>",
                "things I do by myself when idle", "rule every 30m do farm then deposit|rule when full do deposit", "rules");
        v("confirm", "", "jobs", O, "confirm", "run the big job I just asked about (within 30 s; anything else cancels it)", "confirm", "status");
        v("check", "", "jobs", O, "check", "a self-test: what I miss to work on my own, each with the command that fixes it", "check", "check");
        v("look", "", "jobs", O, "look | look <area>", "a text map around me (top-down; under ground the feet layer and two side slices) or of an area, with a legend", "look|look <area>", "where");
        v("reply", "", "jobs", O, "reply full | reply short", "answers short (RESULT/HERE/NEW/OPTIONS, about 600 characters) or the whole text", "reply full|reply short", "why");
        v("why", "", "jobs", G, "why | why cut | why brain | why threats", "the last failure in full; why cut: the last answer the short shape cut; why brain: the brain's last decision: the branch, each need's score, what was skipped (and why), the stage; why threats: the threat test's last fight-or-flee verdict, each mob near now (aggro y/n, path N (straight M), distance -1/s -> counts|noted) and the last 32 changes",
                "why|why cut|why brain|why threats", "status");
        v("deaths", "", "jobs", P, "deaths | death policy on|off", "deaths in the last hour; fetch my corpse after a death or not", "deaths", "status", "deaths");
        // ---- kinds and tools (VOCABULARY 6)
        v("kinds", "", "materials", O, "kinds | kinds <kind> | kinds <kind> exclude|include <id>",
                "the kind-words logs, wood, ores, stone, food, seeds (usable for any item word: mine, get, cut, gather, need, fetch) and what each leaves out", "kinds|kinds logs exclude cherry_log", "cut 16 logs");
        v("tools", "", "materials", O, "tools | tools mode best|cheapest|stone", "which tool I use: best (the default), cheapest (wears out first), stone (stone for all but ores that need more); also the tool and armour care lines (worn pieces are replaced at a pause)", "tools mode stone|tools", "tools");
        v("hotbar", "", "materials", O, "hotbar | hotbar set <slot> <kind|item> ... | hotbar clear <slot>|all", "keep tools in hotbar slots (pickaxe, sword, axe, shovel, hoe, food, torch, or an item)",
                "hotbar set 1 pickaxe 2 sword 3 food 4 torch", "hotbar");
        // ---- explore and find (VOCABULARY 6b)
        v("find", "", "exploring", G, "find <block> | find nearest <poi kind|ore> | find cave|<poi kind>|<biome> [minutes]",
                "find <block>: the nearest block of that kind; find cave, village, mineshaft, a biome...: walk until I find one (the owner), then note it as a point of interest", "find chest|find village|find cave|find cherry_grove", "go poi <id>");
        // ---- the brain's words (VOCABULARY 7, BRAIN_LOOP)
        v("brain", "", "brain", O, "brain on|off|status | brain copy on|off|status | brain get [key] | brain set <key> <value> | brain reset <key>|all|parks | brain tree [reload] | brain learned [job kind] | brain learned reset", "removed in 0.38.b7 (the old brain): AA does this (aa on, aa want <thing>)",
                "brain on|brain status|brain copy on", "why");
        v("aa", "", "brain", O, AaCommands.formsLine().replaceFirst("^aa forms: ", ""),
                "the new brain (AA v0, docs/AA_PLAN.md): a behavior tree with fixed priorities, danger > your orders > AI requests > basic needs (stone tools, table and furnace, torches, base, bag, iron tools) > idle (wood, stone, wait), and one task at a time: a task runs to its end before AA chooses again; only danger pauses it and only your order replaces it (logged); status: who drives, the counters, each rung's reason; why: the last 10 decisions; tree: the tree as Mermaid; like: the personality (want weights build/explore/tidy, upkeep factors, sleep/shelter, night shift, margin) in entropybot/aa-personality.json, aa like build 30 sets one",
                "aa on|aa status|aa why", "brain off");
        v("idle", "", "brain", O, "idle list | idle list set <items> | idle list reset", "removed in 0.38.b7 (the old brain): AA does this (aa on, aa want <thing>)",
                "idle list|idle list set strip, cave", "brain on");
        v("dayplan", "", "brain", O, "dayplan | dayplan skip | dayplan clear [hold] | dayplan now", "removed in 0.38.b7 (the old brain): AA does this (aa on, aa want <thing>)",
                "dayplan|dayplan skip|dayplan now", "why");
        v("actions", "", "brain", O, "actions | actions <verb>", "the action table: what each verb needs and gives, which ones a chain runs; all of it with the state is entropybot/actions.json (for a model)",
                "actions|actions craft", "plan <goal>");
        v("done", "free", "brain", O, "done | free", "I have nothing for you: do what you want (the brain may leave 32 blocks of you); your next order or come takes it back", "done", "status");
        v("camp", "", "brain", O, "camp here", "a camp for a night or two: area camp (24 round, neutral), place camp with a bed marker, torches, my bed when I carry one; not a base", "camp here", "sleep");
        // ---- moving
        v("server", "", "moving", O, "server commands [on|off|status]", "whether I may send the server's slash commands (/home, /sethome): off (the default) = I walk home instead and sethome refuses; check lists the jobs that would have used one", "server commands|server commands on", "home");
        v("path", "", "moving", O, "path maxWalk [<blocks>]", "the longest walk I accept: maxWalk (default 2000): a walk further than that, straight, is refused at once; every walk is one Baritone goal (far: x z first, then the block)", "path maxWalk|path maxWalk 5000", "goto x y z");
        v("home", "", "moving", O, "home", "go home: the server's /home with server commands on, else a walk home", "home", "go base");
        v("death", "", "moving", O, "death", "walk back to where I died and empty my corpse", "death", "deaths");
        v("corpse", "", "moving", O, "corpse", "empty my own corpse when it is near", "corpse", "inv");
        // ---- chests and storage
        v("trust", "", "chests", O, "trust | trust x y z|<place>", "list the chests I keep out of, or let me use one again", "trust", "untrust x y z");
        v("untrust", "", "chests", O, "untrust x y z|<place>", "keep me out of a chest (craft trips, deposits, the food run)", "untrust -20 53 180", "trust");
        v("wear", "", "chests", O, "wear", "put on armor from my bag", "wear", "inv");
        // ---- crafting, smelting, the farm
        v("kit", "", "crafting", O, "kit <material>", "craft that material's armor and tools, then wear the armor", "kit copper", "inv");
        v("recipe", "", "crafting", O, "recipe <item>", "what an item needs", "recipe hopper", "need <item>");
        // AA_CLEANUP 4: the danger moves (AA's danger desire runs them; nothing in the reflexes picks them any more)
        v("pillar", "", "other", O, "pillar", "jump-place 3 throwaway blocks under me and hold on top, hitting what comes in reach", "pillar", "aa why");
        // ---- other
        // 0.35.0 the AI operator (off by default; the owner only; the model itself can never send ai)
        v("help", "", "other", G, "help | help <verb> | help <page> | help all", "this guide: one verb, one page of it, or all", "help mine|help 2", "status");
        v("say", "", "other", O, "say <text>", "say it in public chat (never a command)", "say hello", "status");
        v("spawn", "", "other", O, "spawn", "walk to the nearest bed and set my respawn point there", "spawn", "status");
        v("allow", "", "other", O, "allow | allow <name>", "who I take orders from; allow a player too (the guest commands)", "allow|allow Steve", "deny <name>");
        v("deny", "", "other", O, "deny <name>", "stop taking orders from that player", "deny Steve", "allow");
        v("restart", "", "other", G, "restart ok|no", "I may be closed for an update in the next 15 minutes (or not)", "restart ok", "status");
        v("blind", "", "other", O, "blind on|off|status", "stop drawing frames while the bot works on (less CPU and GPU; the window title shows where it is and its job); off draws again; watch needs it off",
                "blind on|blind status", "blind status");
        v("sense", "", "other", O, "sense | sense status", "the one chunk pass (and ores known [family] [r]: the ores it knows within r, nearest first): chunks read (load, resend, rescans), patches applied and dropped, tick time, the summaries and the ore index, files, the self-audit", "sense status", "ores known");
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

    /**
     * 0.39.z27: a verb's one-line meaning for the help list: its what up to the first ": ", "; " or " (", at most
     * {@link #MEANING_MAX} characters (cut at a word, "..." added).
     */
    public static String meaning(Verb v) {
        String w = v.what();
        int cut = w.length();
        for (String s : List.of(": ", "; ", " (")) { int i = w.indexOf(s); if (i > 0 && i < cut) cut = i; }
        w = w.substring(0, cut).trim();
        if (w.length() <= MEANING_MAX) return w;
        int sp = w.lastIndexOf(' ', MEANING_MAX - 3);
        return w.substring(0, sp > 0 ? sp : MEANING_MAX - 3) + "...";
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
