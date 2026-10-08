package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.MineRules;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.Crafter;
import io.github.mojolowjo.entropybot.craft.RecipeData;
import io.github.mojolowjo.entropybot.gather.GatherPlan;
import io.github.mojolowjo.entropybot.gather.GatherRules;
import io.github.mojolowjo.entropybot.gather.GatherSources;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 (SURVIVAL_PLAN P2): {@code gather <item> [n] [<min>m]}. Not a job of its own: a driver like the chains, which
 * runs ordinary verbs one at a time (get, craft, mine strip / mine / mine cave, chop, farm, ...) through
 * {@link Commands#handle} as internal steps, so the areas, the guard, the tool rules, base trips and fights apply
 * unchanged - they live in those verbs. After each step it re-plans from the bag and the storage notes
 * ({@link GatherPlan}), so partial progress counts. It holds one {@link JobRequests.Request} open for its whole run:
 * a chain step or a rule waits for it, rules, the autominer and the furnace pickup stay quiet meanwhile.
 * <p>
 * Loader notes: none - pure orchestration over existing verbs; the stock and the recipes come through the existing
 * adapters (Gui.inventory, Crafting's storage sources, the client recipe planner).
 */
final class Gathering {
    private static final Logger LOG = LogUtils.getLogger();
    static final String OVERRIDES = "gatherSources";

    private final Core core;
    private final Commands commands;

    Gathering(Core core, Commands commands) {
        this.core = core;
        this.commands = commands;
    }

    static final class Run {
        String from, item;
        int want, minutes, steps, noProgress;
        long deadline, retryAt;
        JobRequests.Request req, sub;
        GatherPlan.Step step;
        String command, lastFail;
        int beforeLeaf, beforeBag;
        final Map<String, Integer> leafFails = new HashMap<>();
        final GatherRules.Tally tally = new GatherRules.Tally();
    }

    private Run run;
    private String lastEnd = "none yet";

    boolean running() { return run != null; }

    String statusText() {
        Run r = run;
        if (r == null) return null;
        LocalPlayer p = Minecraft.getInstance().player;
        int have = p == null ? 0 : Gui.inventory(p).getOrDefault(r.item, 0);
        return GatherRules.status(r.item, r.want, have, r.steps, r.command);
    }

    // ==== the verb ====

    Chains.Reply command(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        GatherRules.Args a = GatherRules.parse(rest);
        switch (a.mode()) {
            case ERROR: return Chains.Reply.now(a.error());
            case STATUS: return Chains.Reply.now(run != null ? statusText() : "gather: not running; last: " + lastEnd);
            case SOURCES: return Chains.Reply.now(a.item() == null ? GatherSources.table(overrides()) : sourcesFor(a.item(), p));
            case SOURCE_SET: {
                JsonObject o = overridesJson(true);
                o.addProperty(a.item(), a.command());
                commands.saved();
                return Chains.Reply.now("ok: gather gets " + GatherSources.shortId(a.item()) + " with \"" + a.command() + "\" from now on"
                        + (a.command().contains("{n}") ? "" : " (add {n} where the count goes)"));
            }
            case SOURCE_CLEAR: {
                JsonObject o = overridesJson(false);
                boolean had = o != null && o.remove(a.item()) != null;
                commands.saved();
                return Chains.Reply.now(had ? "ok: " + GatherSources.shortId(a.item()) + " has its default source again" : "I had no source of yours for " + GatherSources.shortId(a.item()));
            }
            default: break;
        }
        if (run != null) return Chains.Reply.now("busy: " + statusText() + " (pm \"stop\" first)");
        if (commands.jobs.running() && !commands.jobs.walking()) return Chains.Reply.now("busy: " + commands.jobs.job.status + " (pm \"stop\" first)");
        if (!commands.crafting.planner.itemExists(a.item())) {
            return Chains.Reply.now("error: " + GatherSources.shortId(a.item()) + " is not an item id (find it with recipe <part of the name>)");
        }
        try {
            if (MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "areas", PolicyCommands.DEFAULT_DIM).isEmpty())
                return Chains.Reply.now("error: I gather only inside my areas, and there are none - " + PolicyCommands.AREA_HINT);
        } catch (MineRules.BadPolicy e) {
            return Chains.Reply.now("error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't gather"));
        }
        int[] me = Jobs.here(p);
        if (!commands.inAreas(Storage.dim(), me[0], me[2])) return Chains.Reply.now("error: I'm outside my areas - walk me into one first (" + PolicyCommands.AREA_HINT.trim() + ")");
        commands.jobs.replaceWalk();
        Run r = new Run();
        r.from = from;
        r.item = a.item();
        r.want = a.n();
        r.minutes = a.minutes();
        r.deadline = GatherRules.deadline(core.tick(), a.minutes());
        String reply = "started: gathering " + a.n() + " " + GatherSources.shortId(a.item()) + " (up to " + a.minutes() + " min; \"gather status\" shows it)";
        r.req = commands.requests.local("gather", from, raw, reply, l);
        run = r;
        LOG.info("[entropybot] gather: {} {} ({} min) for {}", a.n(), a.item(), a.minutes(), from);
        return new Chains.Reply(reply, r.req);
    }

    private String sourcesFor(String item, LocalPlayer p) {
        GatherPlan.Step s = GatherPlan.next(item, 1, world(p));
        String x = GatherSources.shortId(item);
        return switch (s.kind()) {
            case DONE -> x + ": I have some; to get more: " + leafLine(item, p);
            case NO_WAY -> x + ": no way to get " + GatherSources.shortId(s.leaf()) + " - " + s.why();
            case SOURCE -> x + ": next step now: " + s.command(0) + (s.commands().size() > 1 ? " (then " + String.join(", then ", s.commands().subList(1, s.commands().size())) + ")" : "")
                    + (s.leaf().equals(item) ? "" : " - for " + GatherSources.shortId(s.leaf()));
            default -> x + ": next step now: " + s.command(0);
        };
    }

    private String leafLine(String item, LocalPlayer p) {
        GatherSources.Source src = GatherSources.resolve(item, overrides());
        if (src == null) return "crafted or smelted";
        if (src.kind() == GatherSources.Kind.NONE) return "none - " + src.hint();
        return String.join(", then ", GatherSources.commands(src, 1, mineMarked(), false));
    }

    // ==== the driver (every 5 ticks) ====

    void tick(LocalPlayer p) {
        Run r = run;
        if (r == null) return;
        if (r.sub != null) {
            if (!r.sub.finished) return;
            String msg = r.sub.doneMsg == null ? "" : r.sub.doneMsg;
            r.sub = null;
            afterStep(r, p, msg);
            return;
        }
        if (core.tick() < r.retryAt || commands.holding() || p.isDeadOrDying()) return;
        if (commands.jobs.running() && !commands.jobs.walking()) return;      // something else got in first: wait
        String why = GatherRules.stopReason(core.tick(), r.deadline, r.minutes, r.step == null ? 0 : r.leafFails.getOrDefault(r.step.leaf(), 0),
                r.step == null ? r.item : r.step.leaf(), r.lastFail, r.noProgress, r.steps);
        if (why != null) {
            end(r, p, GatherRules.endText(r.item, r.want, bag(p, r.item), r.tally, why, nextFor(r)));
            return;
        }
        int[] me = Jobs.here(p);
        if (!commands.inAreas(Storage.dim(), me[0], me[2])) {
            end(r, p, GatherRules.endText(r.item, r.want, bag(p, r.item), r.tally, "I'm outside my areas at " + Jobs.fmt(me), "walk me back into one, or \"area here <r> <name>\""));
            return;
        }
        GatherPlan.Step s;
        try {
            s = GatherPlan.next(r.item, r.want, world(p));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] gather: planning failed", e);
            end(r, p, GatherRules.endText(r.item, r.want, bag(p, r.item), r.tally, "planning failed: " + e, null));
            return;
        }
        if (s.kind() == GatherPlan.Kind.DONE) {
            end(r, p, GatherRules.endText(r.item, r.want, bag(p, r.item), r.tally, null, null));
            return;
        }
        if (s.kind() == GatherPlan.Kind.NO_WAY) {
            end(r, p, GatherRules.noWayText(s.leaf(), r.item, r.want, bag(p, r.item), r.tally, s.why()));
            return;
        }
        // a new leaf (or a new kind of step) starts its own tries
        if (r.step == null || !r.step.leaf().equals(s.leaf()) || r.step.kind() != s.kind()) r.leafFails.putIfAbsent(s.leaf(), 0);
        r.step = s;
        r.command = s.command(r.leafFails.getOrDefault(s.leaf(), 0));
        r.beforeLeaf = measure(p, s.leaf());
        r.beforeBag = bag(p, s.leaf());
        r.steps++;
        LOG.info("[entropybot] gather {}: step {}: {}", GatherSources.shortId(r.item), r.steps, r.command);
        Chains.Reply rep;
        try {
            rep = commands.handle(r.from, r.command, true, null);
        } catch (RuntimeException e) {
            rep = Chains.Reply.now("error: " + e);
        }
        if (run != r) return;                                  // the step was "stop"
        if (rep.pending() != null) {
            r.sub = rep.pending();
            return;
        }
        String text = rep.text() == null ? "" : rep.text();
        if (text.startsWith("busy")) {                          // something else runs: try again in 5 s
            r.steps--;
            r.retryAt = core.tick() + 100;
            return;
        }
        if (Texts.stepFailed(text)) {
            // refused at once: another source command may still work (the strip mine refused -> the ore in view)
            int f = r.leafFails.merge(s.leaf(), 1, Integer::sum);
            r.noProgress++;
            r.lastFail = r.command + ": " + text;
            whisper(r, "step " + r.steps + " (" + r.command + ") refused: " + GatherRules.status(r.item, r.want, bag(p, r.item), r.steps, null).replaceFirst("^gather: ", "")
                    + " - " + text.replaceFirst("^error: ", ""));
            if (s.kind() != GatherPlan.Kind.SOURCE || f >= s.commands().size()) {
                end(r, p, GatherRules.endText(r.item, r.want, bag(p, r.item), r.tally, r.command + " - " + text.replaceFirst("^error: ", ""), null));
            }
            return;
        }
        afterStep(r, p, text);
    }

    private void afterStep(Run r, LocalPlayer p, String msg) {
        GatherPlan.Step s = r.step;
        int gainLeaf = measure(p, s.leaf()) - r.beforeLeaf, gainBag = bag(p, s.leaf()) - r.beforeBag;
        switch (s.kind()) {
            case GET -> r.tally.add("took", s.leaf(), gainBag);
            case CRAFT -> r.tally.add(smeltOnly(s.leaf()) || msg.contains("with the furnace") ? "smelted" : "crafted", s.leaf(), gainBag);
            default -> r.tally.add(GatherSources.doneWord(r.command), s.leaf(), Math.max(gainLeaf, gainBag));
        }
        boolean fight = msg.matches("^(stopped|interrupted): (attacked by|low health).*");
        boolean progress = gainLeaf > 0 || gainBag > 0;
        if (progress) {
            r.noProgress = 0;
            r.leafFails.put(s.leaf(), 0);
        } else if (fight) {
            r.retryAt = core.tick() + 200;                     // a fight cut it short: again once it is over, not a failure
        } else {
            r.leafFails.merge(s.leaf(), 1, Integer::sum);
            r.noProgress++;
            r.lastFail = r.command + ": " + msg;
        }
        int have = bag(p, r.item);
        whisper(r, "step " + r.steps + " (" + r.command + "): " + shortMsg(msg) + " - " + have + "/" + r.want + " " + GatherSources.shortId(r.item));
    }

    static String shortMsg(String m) {
        String t = m == null ? "" : m.replaceFirst("^ok: ", "");
        return t.length() > 140 ? t.substring(0, 140) + "..." : t;
    }

    private String nextFor(Run r) {
        if (r.step == null) return null;
        if (r.step.kind() != GatherPlan.Kind.SOURCE) return null;
        return GatherRules.nextHint(GatherSources.resolve(r.step.leaf(), overrides()), r.step.leaf());
    }

    private void whisper(Run r, String text) {
        commands.whisper(r.from, "gather " + GatherSources.shortId(r.item) + ": " + text);
    }

    private void end(Run r, LocalPlayer p, String text) {
        if (run != r) return;
        run = null;
        lastEnd = text;
        LOG.info("[entropybot] gather: {}", text);
        commands.requests.done(r.req.id, text);
    }

    /** "stop" (and a death): the run ends now; its request hears it (a PM's whisper; a chain set aside by a death doesn't). */
    String stop(String why) {
        Run r = run;
        if (r == null) return null;
        LocalPlayer p = Minecraft.getInstance().player;
        int have = p == null ? 0 : bag(p, r.item);
        end(r, p, "stopped: gather " + GatherSources.shortId(r.item) + ": " + Math.min(have, r.want) + "/" + r.want
                + (r.tally.isEmpty() ? "" : " (" + r.tally.text() + ")") + " - " + why);
        return GatherSources.shortId(r.item);
    }

    // ==== what the planner sees ====

    private static int bag(LocalPlayer p, String id) {
        return Gui.inventory(p).getOrDefault(id, 0);
    }

    private int measure(LocalPlayer p, String id) {
        return bag(p, id) + Crafting.totals(commands.crafting.storageSources(p)).getOrDefault(id, 0);
    }

    private boolean smeltOnly(String id) {
        return commands.crafting.planner.craftingRecipes(id).isEmpty() && !commands.crafting.planner.smeltingRecipes(id).isEmpty();
    }

    private JsonObject overridesJson(boolean create) {
        JsonObject b = commands.brainData();
        if (b.has(OVERRIDES) && b.get(OVERRIDES).isJsonObject()) return b.getAsJsonObject(OVERRIDES);
        if (!create) return null;
        JsonObject o = new JsonObject();
        b.add(OVERRIDES, o);
        return o;
    }

    Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        // 0.24.3: hunting on: raw meat comes from hunt (the owner's own source for an item still wins)
        if (commands.hunting != null && commands.hunting.on()) for (String raw : io.github.mojolowjo.entropybot.vocab.HuntRules.RAW) out.put(raw, io.github.mojolowjo.entropybot.vocab.HuntRules.source(raw));
        JsonObject o = overridesJson(false);
        if (o != null) for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().getAsString());
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] gather: a bad source for {} in commands.json: {}", e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private boolean mineMarked() {
        JsonObject m = core.knowledge.places().get("mine");
        return m != null && m.has("dir") && Jobs.dimOf(m).equals(Storage.dim());
    }

    GatherPlan.World world(LocalPlayer p) {
        Map<String, Integer> inv = Gui.inventory(p);
        Map<String, Integer> stored = Crafting.totals(commands.crafting.storageSources(p));
        Map<String, Integer> combined = new LinkedHashMap<>(inv);
        stored.forEach((k, v) -> combined.merge(k, v, Integer::sum));
        CraftPlanner planner = commands.crafting.planner;
        Map<String, String> ov = overrides();
        boolean mine = mineMarked();
        return new GatherPlan.World() {
            @Override public int bag(String id) { return inv.getOrDefault(id, 0); }

            @Override public int stored(String id) { return stored.getOrDefault(id, 0); }

            @Override public boolean canMake(String id, int n) {
                return planner.planAll(List.of(new CraftPlanner.Target(id, n)), new LinkedHashMap<>(combined), combined).ok();
            }

            @Override public List<GatherPlan.Recipe> recipes(String id) {
                List<GatherPlan.Recipe> out = new ArrayList<>();
                for (RecipeData r : planner.craftingRecipes(id)) {
                    if (planner.isBreakdown(r)) continue;
                    out.add(new GatherPlan.Recipe(needs(planner.usedNeeds(r)), r.outCount(), false));
                }
                for (RecipeData r : planner.smeltingRecipes(id)) out.add(new GatherPlan.Recipe(needs(r.needs()), Math.max(1, r.outCount()), true));
                return out;
            }

            @Override public boolean mineMarked() { return mine; }

            @Override public Map<String, String> overrides() { return ov; }

            @Override public boolean knownBlock(String block) { return knownOre(block); }
        };
    }

    /** 0.24.3: a modded ore block is known once the bot has listed one (ores.json) or the owner set a source for it. */
    private boolean knownOre(String block) {
        if (block == null || block.startsWith("minecraft:")) return true;
        try {
            for (JsonObject o : core.mineNotes.ores().values()) {
                String id = o.has("id") ? o.get("id").getAsString() : "";
                if (GatherSources.full(id).equals(block) || GatherSources.full(id).equals(block.replaceFirst(":", ":deepslate_"))) return true;
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] gather: reading the listed ores failed", e);
            return true;      // don't fail a plan on a read error
        }
        return false;
    }

    private static List<GatherPlan.Need> needs(List<Crafter.Need> in) {
        List<GatherPlan.Need> out = new ArrayList<>();
        for (Crafter.Need n : in) out.add(new GatherPlan.Need(n.alts(), n.amount()));
        return out;
    }
}
