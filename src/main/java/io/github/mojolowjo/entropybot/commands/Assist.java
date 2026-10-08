package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.assist.AssistRules;
import io.github.mojolowjo.entropybot.assist.AssistRules.Act;
import io.github.mojolowjo.entropybot.mule.MuleRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 0.24.2 {@code assist}: the game side of {@link AssistRules}. Once a second (Commands' tick) it reads owner.json,
 * settles the owner's activity and does its part: chopping = the half-cut trees near their last log, else {@code cut};
 * mining = that kind within 12 of their last block ({@code dig} box by box: natural blocks, the areas and the near-me
 * zone as for any dig); farming = a farm round when the farm place is within 16 of them; building = stay close and
 * {@code give} the block they place when they run low; fighting = the escort; idle = follow and {@code carry all}.
 * Between jobs, and when the owner is idle for 60 s or there is no fresh report, it escorts (follow + fight what
 * threatens either). Its jobs run as the quiet chain "assist". Never throws out of tick (Commands catches too).
 * Loader notes: vanilla client classes only (the level's block states, the registry).
 */
final class Assist {
    private static final Logger LOG = LogUtils.getLogger();
    static final String CHAIN = "assist";

    private final Commands c;
    private volatile boolean on;
    private AssistRules.Switcher sw = new AssistRules.Switcher();
    private AssistRules.Switcher.Phase phase;
    private AssistRules.Report report;
    private long reportMtime = Long.MIN_VALUE, lastGive = Long.MIN_VALUE / 2, lastStart = Long.MIN_VALUE / 2;
    private String lastKey, doing = "starting", chainAct;

    Assist(Commands c) { this.c = c; }

    boolean on() { return on; }

    /** "assist" | "assist off" | "assist status" (the owner; Texts keeps guests out). */
    String command(String from, String rest, LocalPlayer p) {
        switch (AssistRules.word(rest)) {
            case STATUS -> { return statusText(); }
            case OFF -> {
                if (!on) return "ok: assist is off";
                end(p);
                return "ok: assist off - I stay where I am";
            }
            case ERROR -> { return AssistRules.USAGE; }
            default -> { }
        }
        if (on) return statusText();
        if (c.chains.running()) return "busy: " + c.chains.chainStatus() + " (stop first)";
        if (c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (stop first)";
        if (c.mule.running()) return "busy: " + c.mule.statusText() + " (stop first)";
        on = true;
        sw = new AssistRules.Switcher();
        phase = null;
        lastKey = null;
        chainAct = null;
        doing = "following you";
        follow(p);
        AssistRules.Report r = readReport();
        boolean fresh = r != null && System.currentTimeMillis() - r.received() <= AssistRules.FRESH_MS;
        LOG.info("[entropybot] assist on for {}", from);
        return "ok: assisting you - I follow you, fight what threatens either of us and do what you do (chop, mine, farm; I hand you blocks while you build)"
                + (fresh ? "" : " - no fresh report from your companion mod yet, so for now I only follow")
                + "; assist off, dismiss or stop ends it";
    }

    /** "stop" ended everything already: only forget assist. */
    void forget() {
        if (on) LOG.info("[entropybot] assist ended by stop");
        on = false;
    }

    /** assist off / dismiss: its chain, its escort or carry end; the bot stays. */
    void end(LocalPlayer p) {
        on = false;
        if (ourChain()) {
            c.chains.clear();
            if (c.jobs.running()) c.jobs.finish("stopped: assist off");
        }
        if (c.core().reflexes.escort.active()) {
            c.core().reflexes.escort.stop("assist off");
            c.endEscortFollow();
        }
        if (c.mule.carrying()) c.mule.stop("assist off");
        LOG.info("[entropybot] assist off");
    }

    String statusText() {
        if (!on) return "assist is off (assist: I follow you and help with what you do)";
        AssistRules.Switcher.Phase ph = phase;
        return "assist: on - " + (ph == null ? "no activity seen yet" : "you are " + ph.act().word() + (ph.mirroring() ? "" : " (idle 60 s: just following)"))
                + "; I am " + doing;
    }

    JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on);
        if (on && phase != null) {
            o.addProperty("act", phase.act().word());
            o.addProperty("mirroring", phase.mirroring());
        }
        if (on) o.addProperty("doing", doing);
        return o;
    }

    private boolean ourChain() { return c.chains.running() && CHAIN.equals(c.chains.runningName()); }

    /** Once a second. */
    void tick(LocalPlayer p) {
        if (!on) return;
        long now = System.currentTimeMillis();
        AssistRules.Report r = readReport();
        String owner = c.owner();
        String dim = p.level().dimension().location().toString();
        boolean fresh = r != null && r.name().equalsIgnoreCase(owner) && now - r.received() <= AssistRules.FRESH_MS && r.dim().equals(dim);
        if (!fresh) {
            if (ourChain()) return;                 // the job carries on; the report may come back
            doing = "following you (no fresh report from your companion mod)";
            follow(p);
            return;
        }
        AssistRules.Switcher.Phase ph = sw.update(AssistRules.guess(r, now), now);
        phase = ph;
        if (ph.changed()) {
            String what = switch (ph.act()) {
                case CHOPPING, MINING -> r.broke() == null ? null : r.broke().id();
                case BUILDING -> r.placed() == null ? null : r.placed().id();
                default -> null;
            };
            c.whisper(owner, AssistRules.text(ph.act(), ph.mirroring(), what));
        }
        if (c.chains.running() && !ourChain()) {
            doing = "waiting: " + c.chains.chainStatus();
            return;                                  // the owner's own chain runs; assist steps aside
        }
        if (ourChain()) {
            if (ph.mirroring() && ph.act().word().equals(chainAct)) return;
            c.chains.clear();
            if (c.jobs.running()) c.jobs.finish("stopped: assist switched to " + ph.act().word());
            chainAct = null;
        }
        if (c.mule.running() && !c.mule.carrying()) return;     // a hand-over is under way
        if (!ph.mirroring()) {
            doing = "following you";
            follow(p);
            return;
        }
        switch (ph.act()) {
            case CHOPPING -> chop(p, r, now);
            case MINING -> mine(p, r, now);
            case FARMING -> farm(p, r, now);
            case BUILDING -> build(p, r, now);
            case FIGHTING -> { doing = "guarding you"; follow(p); }
            case IDLE -> carry(p);
        }
    }

    private AssistRules.World world(LocalPlayer p) {
        return (x, y, z) -> {
            BlockPos bp = new BlockPos(x, y, z);
            if (!p.clientLevel.isLoaded(bp)) return null;
            return BuiltInRegistries.BLOCK.getKey(p.clientLevel.getBlockState(bp).getBlock()).toString();
        };
    }

    private void chop(LocalPlayer p, AssistRules.Report r, long now) {
        AssistRules.Ev b = r.broke();
        if (b == null || !AssistRules.isLog(b.id())) {
            doing = "following you (no log broken yet)";
            follow(p);
            return;
        }
        String chain = AssistRules.chain(AssistRules.halfCutTrees(world(p), b.x(), b.y(), b.z(), b.id()), false);
        if (chain == null && dist(p, r.x(), r.z()) <= AssistRules.CHOP_R) chain = "cut 16 " + AssistRules.path(b.id());
        start(p, chain, Act.CHOPPING, "cutting " + AssistRules.path(b.id()), now);
    }

    private void mine(LocalPlayer p, AssistRules.Report r, long now) {
        AssistRules.Ev b = r.broke();
        if (b == null || !AssistRules.mineable(b.id())) {
            doing = "following you (nothing of a natural kind to mine)";
            follow(p);
            return;
        }
        String chain = AssistRules.chain(AssistRules.mineTargets(world(p), b.x(), b.y(), b.z(), b.id()), AssistRules.isOre(b.id()));
        start(p, chain, Act.MINING, "mining " + AssistRules.path(b.id()), now);
    }

    private void farm(LocalPlayer p, AssistRules.Report r, long now) {
        JsonObject f = Core.INSTANCE.knowledge.places().get("farm");
        int[] at = f == null ? null : BrainRuntime.xyz(f);
        boolean near = at != null && Math.hypot(at[0] - r.x(), at[2] - r.z()) <= AssistRules.FARM_R;
        if (!near) {
            doing = "following you (no farm place within " + AssistRules.FARM_R + " of you: place farm marks one)";
            follow(p);
            return;
        }
        start(p, "farm", Act.FARMING, "farming", now);
    }

    private void build(LocalPlayer p, AssistRules.Report r, long now) {
        AssistRules.Ev pl = r.placed();
        if (pl != null && !c.mule.running()) {
            MuleRules.Giveable g = MuleRules.giveable(Storage.held(p), pl.id(), c.storage.keeps());
            int n = AssistRules.give(pl, g.n(), lastGive, now);
            if (n > 0) {
                lastGive = now;
                quietEscortOff();
                if (c.mule.carrying()) c.mule.stop("assist: a hand-over");
                Chains.Reply rep = c.mule.command("give", c.owner(), "me " + pl.id() + " " + n, "give me " + pl.id() + " " + n, null, p);
                doing = "handing you " + n + " " + AssistRules.path(pl.id());
                LOG.info("[entropybot] assist give: {}", rep.text());
                return;
            }
        }
        doing = "staying close while you build";
        follow(p);
    }

    private void carry(LocalPlayer p) {
        doing = "following you and picking up drops";
        if (c.mule.carrying()) return;
        if (c.jobs.running() && !c.jobs.walking()) return;
        quietEscortOff();
        Chains.Reply rep = c.mule.command("carry", c.owner(), "all", "carry all", null, p);
        if (rep.text() == null || !rep.text().startsWith("ok")) {
            LOG.info("[entropybot] assist carry: {}", rep.text());
            follow(p);
        }
    }

    /** Starts the chain for an activity (the same chain not again within a minute), else follows. */
    private void start(LocalPlayer p, String chain, Act a, String what, long now) {
        if (chain == null || (chain.equals(lastKey) && now - lastStart < AssistRules.RETRY_MS) || (c.jobs.running() && !c.jobs.walking())) {
            doing = "following you (" + (chain == null ? "nothing to " + a.word() + " near you" : "done " + what + " there") + ")";
            follow(p);
            return;
        }
        quietEscortOff();
        if (c.mule.carrying()) c.mule.stop("assist: " + what);
        String s = c.chains.startChain(c.owner(), CHAIN, chain, 1);
        lastKey = chain;
        lastStart = now;
        if (s.startsWith("started")) {
            chainAct = a.word();
            doing = what;
            LOG.info("[entropybot] assist: {} ({})", what, chain);
        } else {
            doing = "following you (" + s + ")";
            LOG.info("[entropybot] assist: {} refused: {}", what, s);
        }
    }

    /** The escort (follow + fight for the owner), or a plain follow when self-defence is off. Never twice. */
    private void follow(LocalPlayer p) {
        if (c.mule.carrying()) c.mule.stop("assist: following");
        if (c.core().reflexes.escort.active()) return;
        if (c.chains.running() || c.mule.running() || (c.jobs.running() && !c.jobs.walking()) || c.core().reflexes.hold()) return;
        Jobs.Job j = c.jobs.job;
        if (j != null && j.label != null && j.label.equalsIgnoreCase("following " + c.owner()) && c.stillFollowingPub()) return;
        String r = c.escortCommand(c.owner(), "me", p);
        if (!r.startsWith("ok")) {
            String f = c.modJob("follow", "", c.owner(), p);
            LOG.info("[entropybot] assist: escort {}; follow {}", r, f);
        }
    }

    /** Ends the escort without its "follow ended" whisper: a job of assist's own takes over the walking. */
    private void quietEscortOff() {
        if (!c.core().reflexes.escort.active()) return;
        c.core().reflexes.escort.stop("assist: a job");
        c.endEscortFollow();
    }

    private static double dist(LocalPlayer p, int x, int z) { return Math.hypot(p.getX() - x, p.getZ() - z); }

    private AssistRules.Report readReport() {
        try {
            if (Core.INSTANCE.files() == null) return report;
            Path f = Core.INSTANCE.files().root().resolve("owner.json");
            if (!Files.exists(f)) return report;
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m != reportMtime) {
                reportMtime = m;
                AssistRules.Report r = AssistRules.parse(Files.readString(f, StandardCharsets.UTF_8));
                if (r != null) report = r;
            }
        } catch (Exception e) {
            // caught mid-replace: the next second reads again
        }
        return report;
    }
}
