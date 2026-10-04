package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chains, routines, {@code repeat}, runs that survive a restart, rules, the autominer and the death policy (B7a:
 * the bridge's B6 code in Java, same wording). Everything persistent lives in the commands store's object (the
 * same keys the bridge kept in memory.json: routines, rules, autominer, run, deaths, parked, deathPolicy,
 * lastDeath). Talks to the game only through {@link Env}, so JUnit drives it with a fake.
 */
public final class Chains {
    public static final long FOREVER = Long.MAX_VALUE;
    static final long RUN_RESUME_MS = 12L * 3600 * 1000;
    static final int DEATHS_PARK = 5;
    static final long AUTOMINER_TICKS = 200;

    /** What the command core gives the chains. */
    public interface Env {
        long tick();

        long now();

        ZoneId zone();

        String owner();

        void whisper(String to, String text);

        void log(String line);

        /** Runs one command line; a step that starts a job comes back pending (its request). */
        Reply dispatch(String from, String text, boolean internal, JobRequests.Listener l);

        /** B7e N: a line the bot sends itself (the corpse fetch): like a typed one, but it never cancels the owner's pending confirm. */
        default Reply dispatchAuto(String from, String text, JobRequests.Listener l) { return dispatch(from, text, false, l); }

        boolean alive();

        /** A reflex holds jobs still (eating, fighting...). */
        boolean holding();

        /** Fighting, fleeing or retreating (the chain waits before a retry). */
        boolean fighting();

        float health();

        /** A job's request is open. */
        boolean busy();

        int freeSlots();

        int bagRoom();

        Map<String, Integer> inventory();

        JsonObject supplies();

        String orePrefer();

        /** The "mine" place or null. */
        JsonObject minePlace();

        boolean inAreas(String dim, int x, int z);

        String dim();

        void saved();

        /** Wave 1: where the bot stands {x, y, z}, or null (a retry after a fight walks back first). */
        default int[] pos() { return null; }

        /** Package D: a furnace job's output is due (the chain picks it up between two steps: "smelt collect"). */
        default boolean furnaceDue() { return false; }
    }

    /** Package D: the step a chain runs between two of its steps when a furnace's output is due. */
    static final String PICKUP_STEP = "smelt collect due";
    /** Ticks after a pickup detour before the next one (a refused or instant pickup must not fire again at every step). */
    static final long PICKUP_COOLDOWN = 6000;

    /** A command's answer now (text, maybe null) or later (pending). */
    public record Reply(String text, Request pending) {
        public static Reply now(String text) { return new Reply(text, null); }
    }

    static final class Chain {
        String name, text, from, lastOk;
        List<String> steps;
        int idx, retries;
        long rounds, round, roundStart, retryAt;
        boolean waiting, replyHandled;
        Request pending;
        /** Wave 1: where the running step was while no fight was on; the walk back before a retry; it is running. */
        int[] jobPos;
        String detour;
        boolean inDetour;
        /** Package D: the detour running is a furnace pickup (not the walk back before a retry); no new one before this tick. */
        boolean pickup;
        long pickupAfter;
    }

    /** Wave 1 (item 9): a retry after a fight walks back first when the bot ended up further away than this. */
    static final int CHAIN_BACK_R = 16;

    private final Env env;
    private final JsonObject mem;
    private Chain chain;
    private Chain deathResume;
    private boolean runResumeChecked, corpsePending, corpseRun;
    private long aliveSince = -1, idleSince = -1, autominerLast = -100000;
    private String lastChainEnd;

    public Chains(Env env, JsonObject mem) {
        this.env = env;
        this.mem = mem;
    }

    public boolean running() { return chain != null; }

    public String name() { return chain == null ? null : chain.name; }

    public boolean parked() { return mem.has("parked") && mem.get("parked").isJsonObject(); }

    // ---- chains ----

    public static boolean forever(long rounds) { return rounds == FOREVER; }

    List<String> expandSteps(List<String> steps, int depth) {
        List<String> out = new ArrayList<>();
        JsonObject routines = routines();
        for (String s : steps) {
            String name = s.toLowerCase();
            if (routines.has(name) && depth < 3) out.addAll(expandSteps(Texts.splitChain(routines.get(name).getAsString()), depth + 1));
            else out.add(s);
        }
        return out;
    }

    public JsonObject routines() {
        if (!mem.has("routines") || !mem.get("routines").isJsonObject()) mem.add("routines", new JsonObject());
        return mem.getAsJsonObject("routines");
    }

    public boolean isRoutine(String name) { return routines().has(name.toLowerCase()); }

    public String startChain(String from, String name, String text, long rounds) {
        List<String> steps = expandSteps(Texts.splitChain(text), 0);
        if (steps.isEmpty()) return "error: nothing to do";
        String tooLong = stepsRefusal(steps.size());
        if (tooLong != null) return tooLong;
        Chain c = new Chain();
        c.name = name;
        c.text = text;
        c.steps = steps;
        c.rounds = rounds;
        c.round = 1;
        c.from = from;
        c.roundStart = env.tick();
        chain = c;
        saveRun();
        return "started: " + name + " - " + Limits.stepsText(steps)
                + (rounds > 1 ? (forever(rounds) ? " (repeating until \"stop\")" : " (" + rounds + " times)") : "");
    }

    /** Package H: a chain (routines expanded) of more than {@link Limits#CHAIN_STEPS} steps is refused. */
    static String stepsRefusal(int steps) {
        return steps <= Limits.CHAIN_STEPS ? null
                : "error: that is " + steps + " steps with the routines in it, the most I run in one chain is " + Limits.CHAIN_STEPS
                + " - make it shorter (\"repeat <n> <routine>\" repeats one without the copies)";
    }

    /** Package H: a routine or rule body longer than {@link Limits#ROUTINE_TEXT} characters, or too many steps, is refused. */
    String bodyRefusal(String body) {
        if (body.length() > Limits.ROUTINE_TEXT) return "error: that is " + body.length() + " characters, the most I keep for one is " + Limits.ROUTINE_TEXT;
        return stepsRefusal(expandSteps(Texts.splitChain(body), 0).size());
    }

    void endChain(String msg) {
        if (chain == null) return;
        env.whisper(chain.from, chain.name + ": " + msg);
        env.log("chain " + chain.name + ": " + msg);
        if (chain.name.equals("autominer")) lastChainEnd = msg;
        chain = null;
        clearRun();
    }

    /** "stop": the chain goes, quietly, and so do the saved run and a chain set aside by a death. */
    public String clear() {
        String n = chain == null ? null : chain.name;
        chain = null;
        deathResume = null;
        clearRun();
        return n;
    }

    void saveRun() {
        if (chain == null) return;
        JsonObject r = new JsonObject();
        r.addProperty("name", chain.name);
        r.addProperty("text", chain.text);
        if (forever(chain.rounds)) r.addProperty("rounds", "forever");
        else r.addProperty("rounds", chain.rounds);
        r.addProperty("round", chain.round);
        r.addProperty("idx", (chain.waiting || chain.pending != null) && !chain.inDetour ? chain.idx - 1 : chain.idx);
        r.addProperty("from", chain.from);
        r.addProperty("savedAt", env.now());
        mem.add("run", r);
        env.saved();
    }

    void clearRun() {
        if (mem.has("run") && !mem.get("run").isJsonNull()) {
            mem.add("run", JsonNull.INSTANCE);
            env.saved();
        }
    }

    /** Once, a little after the bot is in the world: carry on with the run a restart cut short. */
    public void resumeRun() {
        if (runResumeChecked) return;
        if (env.busy()) return;                       // something runs (a PM right after the start): look again later
        runResumeChecked = true;
        JsonObject r = obj(mem, "run");
        if (r == null || chain != null || parked()) return;
        long age = env.now() - num(r, "savedAt", 0);
        String name = str(r, "name", "run");
        if (age > RUN_RESUME_MS) {
            env.whisper(env.owner(), "I had \"" + name + "\" running " + Math.round(age / 3600000.0) + " h ago - too long ago to carry on by myself (PM it again)");
            clearRun();
            return;
        }
        String roundsText = r.has("rounds") ? r.get("rounds").getAsString() : "1";
        long rounds = "forever".equals(roundsText) ? FOREVER : Math.max(1, parseLong(roundsText, 1));
        String s = startChain(str(r, "from", env.owner()), name, str(r, "text", ""), rounds);
        if (!s.startsWith("started") || chain == null) {
            // package H: a run the step cap refuses now (a routine in it grew) is dropped, and the owner hears why
            env.whisper(str(r, "from", env.owner()), "I won't carry on with \"" + name + "\" after the restart: " + s.replaceFirst("^error: ", ""));
            clearRun();
            return;
        }
        chain.round = Math.max(1, num(r, "round", 1));
        chain.idx = (int) Math.min(Math.max(num(r, "idx", 0), 0), chain.steps.size());
        saveRun();
        env.whisper(chain.from, "carrying on with " + name + " (round " + chain.round + ", step " + (chain.idx + 1) + ": "
                + (chain.idx < chain.steps.size() ? chain.steps.get(chain.idx) : "the next round") + ") after a " + (age < 120000 ? "reload" : "restart"));
    }

    public void rearmResume() { runResumeChecked = false; }

    public boolean resumeChecked() { return runResumeChecked; }

    /** Every 5 ticks: starts the next step once the last one has finished. */
    public void stepChain() {
        Chain c = chain;
        if (c == null || !env.alive()) return;
        String st = null;
        if (c.pending != null) {
            Request p = c.pending;
            // the step's spot while no fight is on (wave 1: a retry after a fight walks back there first)
            if (!p.finished && !env.fighting() && !env.holding() && !c.inDetour) {
                int[] here = env.pos();
                if (here != null) c.jobPos = here;
            }
            if (p.replied && !c.replyHandled) {
                c.replyHandled = true;
                if (c.inDetour && (Texts.stepFailed(p.reply) || !p.started)) {
                    // the walk back (or a furnace pickup) was refused or instant: the step itself comes next anyway
                    c.inDetour = false;
                    c.pending = null;
                    env.log("chain " + c.name + ": " + (c.pickup ? "the furnace pickup: " : "the walk back before the retry: ") + p.reply);
                    c.pickup = false;
                    return;
                }
                if (Texts.stepFailed(p.reply)) {
                    c.pending = null;
                    endChain("stopped at step " + c.idx + " (" + c.steps.get(c.idx - 1) + "): " + String.valueOf(p.reply).replaceFirst("^error: ", ""));
                    return;
                }
                if (!p.started) {                              // an instant step: the next one on the next call
                    c.pending = null;
                    saveRun();
                    return;
                }
                c.waiting = true;
                saveRun();
            }
            if (!p.finished) return;                           // its job still runs
            c.pending = null;
            st = p.doneMsg == null ? "" : p.doneMsg;
            if (c.inDetour) {
                // back where the step was (or not: whatever the walk said, the step itself comes next) - unless a fight
                // or low health cut the walk short too: another try, and the walk back again after the fight
                c.inDetour = false;
                c.waiting = false;
                if (c.pickup) {
                    // package D: the pickup ended (whatever it said, the chain goes on; a cut-short pickup is tried later)
                    c.pickup = false;
                    env.log("chain " + c.name + ": furnace pickup: " + st);
                    return;
                }
                if (st.matches("^(stopped|interrupted): (attacked by|low health).*")) {
                    if (c.retries >= 3) {
                        endChain("stopped at step " + (c.idx + 1) + " (" + c.steps.get(c.idx) + "): the walk back was cut short too: " + st);
                        return;
                    }
                    c.retries++;
                    c.retryAt = env.tick() + 200;
                    c.detour = detourFor(c.jobPos, env.pos());
                    env.log("chain " + c.name + ": the walk back was cut short (" + st + ") - again after the fight");
                    return;
                }
                env.log("chain " + c.name + ": back for the retry: " + st);
                return;
            }
            c.waiting = true;
        }
        if (c.waiting) {
            c.waiting = false;
            if (st == null) st = "";
            if (!st.startsWith("ok") && !st.startsWith("done")) {
                // a fight cut the step short (the walk to the mine face goes through caves): run it again once the
                // fight is over, up to 3 times in a row, instead of ending "repeat forever"
                if (st.matches("^(stopped|interrupted): (attacked by|low health).*") && c.retries < 3) {
                    c.retries++;
                    c.idx--;
                    c.retryAt = env.tick() + 200;
                    c.detour = detourFor(c.jobPos, env.pos());
                    env.log("chain " + c.name + ": will retry \"" + c.steps.get(c.idx) + "\" after: " + st + (c.detour != null ? " (" + c.detour + " first)" : ""));
                    return;
                }
                endChain("stopped at step " + c.idx + " (" + c.steps.get(c.idx - 1) + "): " + st);
                return;
            }
            c.retries = 0;
            c.lastOk = st.replaceFirst("^(ok|done): ", "");
        }
        if (c.retryAt > 0) {
            if (env.tick() < c.retryAt || env.fighting() || env.health() < 14) return;
            c.retryAt = 0;
        }
        // (the autominer's whole: "why" shows its last result uncut, package A)
        String said = c.lastOk != null ? " - " + (c.lastOk.length() > 160 && !c.name.equals("autominer") ? c.lastOk.substring(0, 160) + "..." : c.lastOk) : "";
        if (c.idx >= c.steps.size()) {
            if (c.round >= c.rounds) {
                endChain("done" + (c.rounds > 1 ? " (" + c.rounds + " rounds)" : "") + said);
                return;
            }
            if (env.tick() - c.roundStart < 200) return;      // a round of instant steps: not faster than every 10 s
            env.whisper(c.from, c.name + ": round " + c.round + " done" + (forever(c.rounds) ? "" : " of " + c.rounds) + said);
            c.lastOk = null;
            c.round++;
            c.idx = 0;
            c.roundStart = env.tick();
        }
        // package D: a furnace's output is due - pick it up between two steps, then the chain goes on where it was
        if (c.detour == null && !c.inDetour && env.tick() >= c.pickupAfter && env.furnaceDue()) {
            c.detour = PICKUP_STEP;
            c.pickup = true;
            c.pickupAfter = env.tick() + PICKUP_COOLDOWN;
        }
        String step;
        if (c.detour != null) {
            // wave 1: the walk back to where the step was, before its retry (the step's index stays)
            step = c.detour;
            c.detour = null;
            c.inDetour = true;
        } else {
            step = c.steps.get(c.idx++);
            c.jobPos = null;
        }
        Reply r;
        try {
            r = env.dispatch(c.from, step, true, null);
        } catch (RuntimeException e) {
            r = Reply.now("error: " + e);
        }
        if (chain != c) return;                                // the step was "stop"
        if (r.pending() != null) {
            c.pending = r.pending();
            c.replyHandled = false;
            saveRun();
            return;
        }
        if (c.inDetour) {
            c.inDetour = false;                                // an instant answer (a refusal): the step comes next anyway
            env.log("chain " + c.name + ": " + (c.pickup ? "the furnace pickup: " : "the walk back before the retry: ") + r.text());
            c.pickup = false;
            return;
        }
        if (Texts.stepFailed(r.text())) {
            endChain("stopped at step " + c.idx + " (" + step + "): " + String.valueOf(r.text()).replaceFirst("^error: ", ""));
            return;
        }
        saveRun();                                             // where it is, for a resume after a restart
    }

    /** Wave 1 (item 9): "goto x y z" back to where the step was, when the bot is more than CHAIN_BACK_R from it; else null. */
    static String detourFor(int[] was, int[] now) {
        if (was == null || now == null) return null;
        long dx = was[0] - now[0], dy = was[1] - now[1], dz = was[2] - now[2];
        if (dx * dx + dy * dy + dz * dz <= (long) CHAIN_BACK_R * CHAIN_BACK_R) return null;
        return "goto " + was[0] + " " + was[1] + " " + was[2];
    }

    public String chainStatus() {
        Chain c = chain;
        if (c == null) return "nothing queued";
        return c.name + ": step " + Math.min(c.idx, c.steps.size()) + "/" + c.steps.size() + " (" + c.steps.get(Math.max(c.idx - 1, 0)) + ")"
                + (c.rounds > 1 ? ", round " + c.round + (forever(c.rounds) ? "" : "/" + c.rounds) : "")
                + (c.idx < c.steps.size() ? ", next: " + c.steps.get(c.idx) : "");
    }

    /** "routine save <name> <chain>", "routine delete <name>", "routine show <name>", "routines". */
    public String routineCommand(String rest) {
        List<String> p = Texts.words(rest);
        String sub = p.isEmpty() ? "" : p.get(0).toLowerCase(), name = p.size() > 1 ? p.get(1).toLowerCase() : "";
        JsonObject routines = routines();
        if (sub.equals("save") || sub.equals("add")) {
            if (!name.matches("^[a-z0-9_-]{1,20}$")) return "usage: routine save <name> <command> then <command> ...";
            if (Texts.isBuiltin(name)) return "error: \"" + name + "\" is already a command - pick another name";
            String body = String.join(" ", p.subList(Math.min(2, p.size()), p.size()));
            if (Texts.splitChain(body).isEmpty()) return "usage: routine save " + name + " <command> then <command> ...";
            // package H: at most Limits.ROUTINES routines, each at most ROUTINE_TEXT characters and CHAIN_STEPS steps
            String refused = Limits.full(routines.has(name), routines.size(), Limits.ROUTINES, "routines", "delete one first (routine delete <name>; \"routines\" lists them)");
            if (refused == null) refused = bodyRefusal(body);
            if (refused != null) return refused;
            routines.addProperty(name, body);
            env.saved();
            return "saved routine " + name + ": " + Limits.stepsText(Texts.splitChain(body)) + " (PM \"" + name + "\" to run it)";
        }
        if (sub.equals("delete") || sub.equals("remove") || sub.equals("forget")) {
            if (!routines.has(name)) return "I have no routine called " + name + " - next: routines";
            routines.remove(name);
            env.saved();
            return "deleted routine " + name;
        }
        if (sub.equals("show")) {
            return routines.has(name) ? name + ": " + String.join(" > ", Texts.splitChain(routines.get(name).getAsString())) : "I have no routine called " + name + " - next: routines";
        }
        List<String> list = new ArrayList<>(routines.keySet());
        return list.isEmpty() ? "no routines yet - routine save <name> <command> then <command> ..." : "routines: " + String.join(", ", list) + " (routine show <name>)";
    }

    // ---- the death policy ----

    /** The bot died: count it, park after 5 in an hour, else plan the corpse trip; a running chain is set aside. */
    public void noteDeath() {
        long now = env.now();
        JsonArray kept = recentDeaths(now);
        kept.add(now);
        mem.add("deaths", kept);
        if (chain != null) {
            Chain c = chain;
            c.idx = Math.max((c.waiting || c.pending != null) && !c.inDetour ? c.idx - 1 : c.idx, 0);
            c.waiting = false;
            c.pending = null;
            c.inDetour = false;
            c.detour = null;
            c.retryAt = 0;
            c.retries = 0;
            c.lastOk = null;
            deathResume = c;
            chain = null;                                      // quietly: it carries on after the corpse
        }
        if (kept.size() >= DEATHS_PARK) {
            JsonObject p = new JsonObject();
            p.addProperty("at", now);
            p.addProperty("why", kept.size() + " deaths in an hour");
            mem.add("parked", p);
            deathResume = null;
            corpsePending = false;
            clearRun();
            env.whisper(env.owner(), kept.size() + " deaths in an hour - I stay at the base now and do nothing by myself. PM \"resume\" when it is safe.");
        } else {
            corpsePending = !(mem.has("deathPolicy") && !mem.get("deathPolicy").getAsBoolean());
        }
        env.saved();
    }

    JsonArray recentDeaths(long now) {
        JsonArray out = new JsonArray();
        if (mem.has("deaths") && mem.get("deaths").isJsonArray()) {
            for (JsonElement e : mem.getAsJsonArray("deaths")) {
                try {
                    if (now - e.getAsLong() < 3600000) out.add(e.getAsLong());
                } catch (RuntimeException ignored) {}
            }
        }
        return out;
    }

    /** Every 20 ticks: after a respawn (alive 5 s, health 14+), fetch the corpse, then carry on with the chain. */
    public void deathTick(boolean dead) {
        if (dead) {
            aliveSince = -1;
            return;
        }
        if (aliveSince < 0) aliveSince = env.tick();
        if (corpseRun && !env.busy()) {
            corpseRun = false;
            if (deathResume != null && chain == null && !parked()) {
                chain = deathResume;
                chain.roundStart = env.tick();
                env.whisper(chain.from, "back from my corpse - carrying on with " + chain.name);
                saveRun();
            }
            deathResume = null;
            return;
        }
        if (!corpsePending || env.tick() - aliveSince < 100 || env.health() < 14 || env.busy() || env.holding()) return;
        corpsePending = false;
        JsonObject d = obj(mem, "lastDeath");
        String why = null;
        if (d == null) why = "I have no note of where";
        else if (d.has("dim") && !str(d, "dim", "").equals(env.dim())) why = "it is in " + str(d, "dim", "");
        else if (num(d, "y", 0) < -60) why = "it is below y -60";
        if (why != null) {
            env.whisper(env.owner(), "I won't fetch my corpse: " + why + " (" + (d == null ? "?" : num(d, "x", 0) + " " + num(d, "y", 0) + " " + num(d, "z", 0)) + ")");
            corpseRun = true;                                  // the chain still carries on (next deathTick)
            return;
        }
        corpseRun = true;
        Reply r = env.dispatchAuto(env.owner(), "death", q -> {
            if (!JobRequests.quiet(q.doneMsg)) env.whisper(env.owner(), q.doneMsg.replaceFirst("^ok: ", ""));
        });
        if (r.pending() == null && (r.text() == null || !r.text().startsWith("started"))) env.whisper(env.owner(), "couldn't go for my corpse: " + r.text());
    }

    /** "resume": un-park after the deaths, and carry on with what was saved. */
    public String resumeCommand() {
        JsonObject was = obj(mem, "parked");
        mem.add("parked", JsonNull.INSTANCE);
        mem.add("deaths", new JsonArray());
        env.saved();
        runResumeChecked = false;
        return was != null ? "ok: back to work (I was parked: " + str(was, "why", "?") + ")" : "ok: I was not parked";
    }

    /** "deaths", "death policy on|off". */
    public String deathsCommand(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        int n = recentDeaths(env.now()).size();
        if (t.matches("^policy (on|off)$")) {
            mem.addProperty("deathPolicy", t.endsWith("on"));
            env.saved();
        }
        boolean off = mem.has("deathPolicy") && !mem.get("deathPolicy").getAsBoolean();
        JsonObject p = obj(mem, "parked");
        return n + " death" + (n == 1 ? "" : "s") + " in the last hour; fetching my corpse after a death is " + (off ? "OFF" : "on")
                + (p != null ? "; PARKED (" + str(p, "why", "?") + ") - PM \"resume\"" : "");
    }

    // ---- rules ----

    private static final Pattern RULE_EVERY = Pattern.compile("^(every)\\s+(\\d+)(m|h)\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_AT = Pattern.compile("^(at)\\s+(\\d{1,2}):(\\d{2})\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_FULL = Pattern.compile("^(when)\\s+(full)()\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_IDLE = Pattern.compile("^(when)\\s+idle\\s+(\\d+)(m)\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    JsonArray rules() {
        if (!mem.has("rules") || !mem.get("rules").isJsonArray()) mem.add("rules", new JsonArray());
        return mem.getAsJsonArray("rules");
    }

    /** "rule every 30m do <chain>", "rule at 06:30 do ...", "rule when full do ...", "rule when idle 10m do ...", "rules", "rule delete <n>". */
    public String ruleCommand(String rest) {
        String t = rest == null ? "" : rest.trim();
        JsonArray list = rules();
        if (t.isEmpty() || t.equalsIgnoreCase("list")) {
            if (list.isEmpty()) return "no rules - e.g. rule every 30m do farm, rule at 06:30 do deposit, rule when full do deposit, rule when idle 10m do restock";
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                JsonObject x = list.get(i).getAsJsonObject();
                out.add("#" + (i + 1) + " " + str(x, "kind", "") + " " + str(x, "arg", "") + " do " + str(x, "text", ""));
            }
            return String.join(" | ", out);
        }
        Matcher d = Pattern.compile("^(delete|remove)\\s+(\\d+)$", Pattern.CASE_INSENSITIVE).matcher(t);
        if (d.find()) {
            int i = Integer.parseInt(d.group(2)) - 1;
            if (i < 0 || i >= list.size()) return "I have no rule #" + d.group(2);
            JsonObject r = list.remove(i).getAsJsonObject();
            env.saved();
            return "deleted rule: " + str(r, "kind", "") + " " + str(r, "arg", "") + " do " + str(r, "text", "");
        }
        Matcher m = null;
        for (Pattern p : new Pattern[]{RULE_EVERY, RULE_AT, RULE_FULL, RULE_IDLE}) {
            Matcher x = p.matcher(t);
            if (x.find()) {
                m = x;
                break;
            }
        }
        if (m == null) return "usage: rule every <n>m|h do <commands> | rule at HH:MM do ... | rule when full do ... | rule when idle <n>m do ... | rules | rule delete <n>";
        String kind = m.group(1).toLowerCase(), arg, text = m.group(4);
        if (expandSteps(Texts.splitChain(text), 0).isEmpty()) return "error: nothing to do";
        // package H: at most Limits.RULES rules ("rules" lists each one whole)
        String refused = Limits.full(false, list.size(), Limits.RULES, "rules", "delete one first (rule delete <n>; \"rules\" lists them)");
        if (refused == null) refused = bodyRefusal(text);
        if (refused != null) return refused;
        if (kind.equals("every")) arg = m.group(2) + m.group(3).toLowerCase();
        else if (kind.equals("at")) arg = ("0" + m.group(2)).substring(("0" + m.group(2)).length() - 2) + ":" + m.group(3);
        else arg = t.toLowerCase().contains("idle") ? "idle " + m.group(2) + "m" : "full";
        JsonObject r = new JsonObject();
        r.addProperty("kind", kind);
        r.addProperty("arg", arg);
        r.addProperty("text", text);
        r.addProperty("last", env.now());
        list.add(r);
        env.saved();
        return "ok: rule #" + list.size() + ": " + kind + " " + arg + " do " + String.join(" > ", Texts.splitChain(text));
    }

    /** Every 100 ticks: the first rule that is due starts as a chain (only while nothing else runs). */
    public void rulesTick() {
        long now = env.now();
        if (chain != null || env.busy() || parked() || env.holding()) {
            idleSince = -1;
            return;
        }
        if (idleSince < 0) idleSince = now;
        JsonArray list = rules();
        for (int i = 0; i < list.size(); i++) {
            JsonObject r = list.get(i).getAsJsonObject();
            String kind = str(r, "kind", ""), arg = str(r, "arg", "");
            long last = num(r, "last", 0);
            boolean due = false;
            if (kind.equals("every")) {
                long n = leadingInt(arg) * (arg.endsWith("h") ? 3600000L : 60000L);
                due = now - last >= n;
            } else if (kind.equals("at")) {
                try {
                    String[] hm = arg.split(":");
                    ZonedDateTime d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), env.zone()).withSecond(0).withNano(0)
                            .withHour(0).withMinute(0).plusHours(Integer.parseInt(hm[0])).plusMinutes(Integer.parseInt(hm[1]));
                    long at = d.toInstant().toEpochMilli();
                    due = now >= at && last < at;
                } catch (RuntimeException ignored) {}
            } else if (arg.equals("full")) {
                due = env.bagRoom() <= 4 && now - last > 120000;
            } else if (arg.startsWith("idle")) {
                long n = leadingInt(arg.split(" ")[1]) * 60000L;
                due = now - idleSince >= n && now - last > n;
            }
            if (!due) continue;
            r.addProperty("last", now);
            env.saved();
            env.log("rule #" + (i + 1) + " (" + kind + " " + arg + ") -> " + str(r, "text", ""));
            String s = startChain(env.owner(), "rule #" + (i + 1), str(r, "text", ""), 1);
            // package H: a rule whose chain is refused (too many steps since a routine in it grew) says so, once
            if (!s.startsWith("started")) {
                env.log("rule #" + (i + 1) + " refused: " + s);
                if (!s.equals(str(r, "refused", ""))) {
                    r.addProperty("refused", s);
                    env.whisper(env.owner(), "rule #" + (i + 1) + " (" + kind + " " + arg + ") didn't start: " + s.replaceFirst("^error: ", ""));
                }
            } else if (r.has("refused")) {
                r.remove("refused");
            }
            idleSince = -1;
            return;
        }
    }

    // ---- the autominer ----

    JsonObject autominer() { return obj(mem, "autominer"); }

    /**
     * Package A: what the autominer keeps in its supplies by itself (added once; "supplies" changes it): a pickaxe
     * for the ores a stone one can't break, so a deepslate redstone ore never stops the mine for a night.
     */
    static final Map<String, Integer> AUTOMINER_SUPPLIES = Map.of("minecraft:iron_pickaxe", 1);
    static final long RESULT_MAX = 1000;

    /** Adds the autominer's default supplies that are missing (commands.json "supplies"); what it added. */
    List<String> autominerDefaults() {
        List<String> added = new ArrayList<>();
        JsonObject sup = obj(mem, "supplies");
        if (sup == null) {
            sup = new JsonObject();
            mem.add("supplies", sup);
        }
        for (Map.Entry<String, Integer> e : AUTOMINER_SUPPLIES.entrySet()) {
            if (sup.has(e.getKey())) continue;
            sup.addProperty(e.getKey(), e.getValue());
            added.add(e.getValue() + " " + Texts.shortId(e.getKey()));
        }
        JsonObject a = autominer();
        if (a != null) a.addProperty("defaults", true);
        return added;
    }

    /** "autominer on|off|status" (no word: the status too). */
    public String autominerCommand(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        JsonObject a = autominer();
        if (t.equals("on") || t.equals("off")) {
            JsonObject n = new JsonObject();
            n.addProperty("on", t.equals("on"));
            n.addProperty("since", env.now());
            n.add("log", a != null && a.has("log") && a.get("log").isJsonArray() ? a.getAsJsonArray("log") : new JsonArray());
            n.addProperty("pausedUntil", 0);
            mem.add("autominer", n);
            List<String> added = t.equals("on") ? autominerDefaults() : List.of();
            env.saved();
            String pref = env.orePrefer();
            return t.equals("on") ? "ok: autominer on - when I have nothing to do I put things away, restock and mine (" + (pref == null || pref.isEmpty() ? "any ores" : pref)
                    + "); \"why\" says what I decided" + (added.isEmpty() ? "" : "; I keep " + String.join(", ", added) + " in my supplies now") : "ok: autominer off";
        }
        boolean on = a != null && a.has("on") && a.get("on").getAsBoolean();
        long paused = a == null ? 0 : num(a, "pausedUntil", 0), held = a == null ? 0 : num(a, "heldUntil", 0);
        String last = autominerLastText();
        return "autominer is " + (on ? "on" : "off") + (paused > env.now() ? " (paused " + (long) Math.ceil((paused - env.now()) / 60000.0) + " min after two failures)" : "")
                + (on && held > env.now() ? " (waiting " + (long) Math.ceil((held - env.now()) / 60000.0) + " min after a stop)" : "")
                + (last != null ? " - last: " + last : "");
    }

    /** Wave 1 (item 4): how long a "stop" holds the autominer ("autominer on" lifts it). */
    static final long AUTOMINER_HOLD_MS = 600000;

    /** "stop": the autominer waits AUTOMINER_HOLD_MS before its next decision; the stop reply's tail ("" when it is off). */
    public String holdAutominer() {
        JsonObject a = autominer();
        if (a == null || !a.has("on") || !a.get("on").getAsBoolean()) return "";
        a.addProperty("heldUntil", env.now() + AUTOMINER_HOLD_MS);
        env.saved();
        return "; the autominer waits " + AUTOMINER_HOLD_MS / 60000 + " min (\"autominer on\" to go on now)";
    }

    static String decisionText(JsonObject e, long now) {
        return Texts.ago(num(e, "at", 0), now) + ": " + str(e, "what", "") + " because " + str(e, "why", "")
                + (e.has("result") && !e.get("result").isJsonNull() ? " -> " + str(e, "result", "") : "");
    }

    JsonArray autominerLog() {
        JsonObject a = autominer();
        return a != null && a.has("log") && a.get("log").isJsonArray() ? a.getAsJsonArray("log") : new JsonArray();
    }

    /** The last decision ("2m ago: mine strip any 32 because ... -> ..."), for "autominer status" and state.json; null with none. */
    public String autominerLastText() {
        JsonArray log = autominerLog();
        if (log.isEmpty()) return null;
        JsonObject e = log.get(log.size() - 1).getAsJsonObject();
        return decisionText(e, env.now()) + (e.has("result") && !e.get("result").isJsonNull() ? "" : " (running)");
    }

    /** state.json's "autominer": {on, pausedUntil, last}, or null when it was never switched on. */
    public JsonObject autominerState() {
        JsonObject a = autominer();
        if (a == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("on", a.has("on") && a.get("on").getAsBoolean());
        o.addProperty("pausedUntil", num(a, "pausedUntil", 0));
        o.addProperty("heldUntil", num(a, "heldUntil", 0));
        String last = autominerLastText();
        if (last != null) o.addProperty("last", last);
        return o;
    }

    /** The last 5 decisions; the latest whole, on a line of its own (the whisper sends each line by itself). */
    public String whyCommand() {
        JsonObject a = autominer();
        JsonArray log = autominerLog();
        boolean on = a != null && a.has("on") && a.get("on").getAsBoolean();
        if (log.isEmpty()) return "no decisions yet" + (on ? "" : " (autominer is off)");
        List<String> out = new ArrayList<>();
        // package H: the older decisions shortened (a 1000-character result each made "why" 30 whispers); the latest whole
        for (int i = Math.max(0, log.size() - 5); i < log.size() - 1; i++) {
            String d = decisionText(log.get(i).getAsJsonObject(), env.now());
            out.add(d.length() > Limits.WHY_OLDER ? d.substring(0, Limits.WHY_OLDER) + "..." : d);
        }
        return (out.isEmpty() ? "" : String.join(" | ", out) + "\n") + "latest: " + decisionText(log.get(log.size() - 1).getAsJsonObject(), env.now());
    }

    /** A strip run that failed on something the mine couldn't get past (blocked with no way on, stuck, unreachable). */
    static boolean stripGaveUp(JsonObject e) {
        String r = str(e, "result", "");
        return e != null && str(e, "what", "").startsWith("mine strip") && r.matches("(?s)^(stopped|error).*")
                && r.matches("(?s).*(blocked:|stuck|couldn't reach the mine|no progress).*");
    }

    /** The next thing to do, {what, why}, or null. */
    String[] autominerDecide() {
        int free = env.freeSlots();
        if (free <= 4) return new String[]{"deposit", "my bag is nearly full (" + free + " free slots)"};
        JsonObject sup = env.supplies(), a = autominer();
        Map<String, Integer> inv = env.inventory();
        if (sup != null) {
            for (Map.Entry<String, JsonElement> e : sup.entrySet()) {
                int want = e.getValue().getAsInt();
                if (inv.getOrDefault(e.getKey(), 0) < want && env.now() - num(a, "restockAt", 0) > 600000) {
                    a.addProperty("restockAt", env.now());
                    return new String[]{"restock", Texts.shortId(e.getKey()) + " is short of my supplies"};
                }
            }
        }
        String ores = env.orePrefer() == null || env.orePrefer().isEmpty() ? "any" : env.orePrefer();
        // package A: a mine that just gave up (and couldn't turn) is left alone for 30 minutes: caving, not a pause
        JsonArray log = autominerLog();
        JsonObject gaveUp = null;
        for (int i = log.size() - 1; i >= 0; i--) {
            JsonObject e = log.get(i).getAsJsonObject();
            if (!str(e, "what", "").startsWith("mine strip")) continue;
            if (stripGaveUp(e) && env.now() - num(e, "at", 0) < 1800000) gaveUp = e;
            break;
        }
        JsonObject m = env.minePlace();
        if (gaveUp == null && m != null && m.has("dir") && env.inAreas(str(m, "dim", "minecraft:overworld"), (int) num(m, "x", 0), (int) num(m, "z", 0))) {
            return new String[]{"mine strip " + ores + " 32", "my mine at " + num(m, "x", 0) + " " + num(m, "y", 0) + " " + num(m, "z", 0) + " is ready"};
        }
        if (gaveUp != null) {
            String why = str(gaveUp, "result", "").replaceFirst("^stopped at step \\d+ \\([^)]*\\): ", "");
            return new String[]{"mine cave " + ores + " 32 20m", "my mine could not go on (" + (why.length() > 160 ? why.substring(0, 160) : why) + "), so I go caving"};
        }
        return new String[]{"mine cave " + ores + " 32 20m", "I have no mine marked, so I go caving"};
    }

    public void autominerTick() {
        JsonObject a = autominer();
        if (a == null || !a.has("on") || !a.get("on").getAsBoolean() || parked() || chain != null || env.busy() || env.holding()) return;
        // (package A) after a death the chain it cut short carries on after the corpse trip: no new decision before that
        if (corpsePending || corpseRun) return;
        if (env.tick() - autominerLast < AUTOMINER_TICKS || num(a, "pausedUntil", 0) > env.now()) return;
        if (num(a, "heldUntil", 0) > env.now()) return;               // a "stop" (wave 1, item 4)
        autominerLast = env.tick();
        if (!a.has("defaults") && !autominerDefaults().isEmpty()) env.saved();
        JsonArray log = a.has("log") && a.get("log").isJsonArray() ? a.getAsJsonArray("log") : new JsonArray();
        a.add("log", log);
        JsonObject last = log.isEmpty() ? null : log.get(log.size() - 1).getAsJsonObject();
        if (last != null && (!last.has("result") || last.get("result").isJsonNull())) {
            // the whole result (package A: "why" shows it uncut)
            String res = lastChainEnd == null ? "done" : lastChainEnd;
            last.addProperty("result", res.length() > RESULT_MAX ? res.substring(0, (int) RESULT_MAX) : res);
        }
        // the same decision failing twice in a row: a 30-minute pause, and the owner hears it (a mine that gave up is
        // handled in autominerDecide: caving instead)
        if (last != null && log.size() >= 2 && !stripGaveUp(last)) {
            JsonObject prev = log.get(log.size() - 2).getAsJsonObject();
            if (str(prev, "what", "").equals(str(last, "what", "")) && failedResult(prev) && failedResult(last) && env.now() - num(prev, "at", 0) < 600000) {
                a.addProperty("pausedUntil", env.now() + 1800000);
                env.whisper(env.owner(), "autominer: \"" + str(last, "what", "") + "\" failed twice (" + str(last, "result", "") + ") - pausing 30 min");
                env.saved();
                return;
            }
        }
        String[] d = autominerDecide();
        if (d == null) return;
        JsonObject e = new JsonObject();
        e.addProperty("at", env.now());
        e.addProperty("what", d[0]);
        e.addProperty("why", d[1]);
        log.add(e);
        while (log.size() > 20) log.remove(0);
        lastChainEnd = null;
        env.saved();
        env.log("autominer: " + d[0] + " (" + d[1] + ")");
        startChain(env.owner(), "autominer", d[0], 1);
    }

    private static boolean failedResult(JsonObject e) {
        String r = str(e, "result", "");
        return r.contains("stopped") || r.contains("error");
    }

    // ---- small JSON helpers ----

    static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    static String str(JsonObject o, String k, String def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try { return o.get(k).getAsString(); } catch (RuntimeException e) { return def; }
    }

    static long num(JsonObject o, String k, long def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try { return (long) Math.floor(o.get(k).getAsDouble()); } catch (RuntimeException e) { return def; }
    }

    static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return def; }
    }

    static long leadingInt(String s) {
        Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
