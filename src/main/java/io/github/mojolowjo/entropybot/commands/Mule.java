package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.mule.MuleRules;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C6 (mule and fetch): {@code hold this}, {@code give}, {@code carry}, {@code unload}, {@code fetch}. A driver like
 * {@link Gathering}: ticked every 5 ticks, it holds one {@link JobRequests.Request} open for its run (a chain step
 * waits for it; rules and the autominer stay quiet), runs existing verbs (deposit, come, get, gather) as internal
 * steps through {@link Commands#handle}, and walks with Baritone's custom goal (breaking off, the fence checked for
 * every goal). It pauses while a reflex (a fight, a meal) holds the bot; "stop" and a death end it. The rules are in
 * {@link MuleRules}.
 * <p>
 * Loader notes: vanilla client only - ItemEntity (Level.entitiesForRendering), LocalPlayer.lookAt, the THROW click
 * of the inventory menu (GuiCore.drop through McMenu: the server throws in the look direction); Baritone API goals.
 * No NeoForge event or registry.
 */
public final class Mule {
    private static final Logger LOG = LogUtils.getLogger();

    private final Core core;
    private final Commands commands;

    Mule(Core core, Commands commands) {
        this.core = core;
        this.commands = commands;
    }

    enum Phase { HOLD, STEPS, GIVE, CARRY }

    static final class Run {
        MuleRules.Kind kind;
        Phase phase;
        String from, to, item;
        int n, want;
        long started, phaseAt;
        JobRequests.Request req, sub;
        final List<String> steps = new ArrayList<>();
        String step, lastMsg;
        Set<Integer> before = new HashSet<>();
        Map<String, Integer> invBefore;
        int[] goal;
        List<Integer> throwsLeft;
        int given, faced, had, took, made, beforeStep;
        String note;
        List<String> carry = List.of();
    }

    private Run run;
    private List<String> carryList = List.of();

    boolean running() { return run != null; }

    /** 0.24.2: a carry runs (assist's idle mode). */
    boolean carrying() { Run r = run; return r != null && r.phase == Phase.CARRY; }

    String statusText() {
        Run r = run;
        if (r == null) return null;
        return switch (r.phase) {
            case HOLD -> "mule: holding out my hands for " + r.from + "'s items";
            case CARRY -> "mule: following " + r.from + ", carrying " + String.join(", ", r.carry);
            case GIVE -> "mule: giving " + r.to + " " + r.want + " " + GuiCore.shortId(r.item);
            default -> "mule: " + r.kind.name().toLowerCase() + (r.step == null ? "" : " - " + r.step);
        };
    }

    // ==== the verbs ====

    Chains.Reply command(String verb, String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        MuleRules.Args a = MuleRules.parse(verb, rest, from);
        switch (a.kind()) {
            case ERROR: return Chains.Reply.now(a.error());
            case CARRY_LIST:
                return Chains.Reply.now(carryList.isEmpty() ? "carry: nothing on my list - carry <item> [item ...]"
                        : "carry: " + String.join(", ", carryList) + (run != null && run.phase == Phase.CARRY ? " (on, following " + run.from + ")" : " (off)"));
            case CARRY_OFF: {
                Run r = run;
                if (r == null || r.phase != Phase.CARRY) return Chains.Reply.now("carry is off");
                end(r, "ok: carry off - following " + r.from + " plainly");
                return Chains.Reply.now(commands.modJob("follow", "", from, p));
            }
            default: break;
        }
        if (run != null) return Chains.Reply.now("busy: " + statusText() + " (pm \"stop\" first)");
        if (commands.gathering.running()) return Chains.Reply.now("busy: " + commands.gathering.statusText() + " (pm \"stop\" first)");
        if (commands.jobs.running() && !commands.jobs.walking()) return Chains.Reply.now("busy: " + commands.jobs.job.status + " (pm \"stop\" first)");
        Run r = new Run();
        r.kind = a.kind();
        r.from = from;
        r.to = a.player();
        r.started = core.tick();
        r.phaseAt = core.tick();
        String reply;
        switch (a.kind()) {
            case HOLD -> {
                r.phase = Phase.HOLD;
                for (Entity e : p.clientLevel.entitiesForRendering()) if (e instanceof ItemEntity) r.before.add(e.getId());
                r.invBefore = Gui.inventory(p);
                reply = "ok: throw them to me now (15 s, within " + MuleRules.HOLD_RADIUS + " blocks)";
            }
            case GIVE -> {
                String id = GuiCore.resolve(a.item(), Gui.inventory(p).keySet());
                MuleRules.Giveable g = MuleRules.giveable(Storage.held(p), id, commands.storage.keeps());
                MuleRules.GiveDecision d = MuleRules.decide(a.n(), g, id);
                if (d.n() <= 0) return Chains.Reply.now(d.note());
                String why = reachable(p, r.to, from);
                if (why != null) return Chains.Reply.now(why);
                r.phase = Phase.GIVE;
                r.item = id;
                r.want = d.n();
                r.note = d.note();
                reply = "ok: bringing " + (r.to.equalsIgnoreCase(from) ? "you" : r.to) + " " + d.n() + " " + GuiCore.shortId(id);
            }
            case CARRY -> {
                String why = reachable(p, from, from);
                if (why != null) return Chains.Reply.now(why);
                carryList = a.items();
                r.phase = Phase.CARRY;
                r.carry = a.items();
                commands.jobs.replaceWalk();
                reply = "ok: following you and picking up " + String.join(", ", a.items()) + " within " + MuleRules.CARRY_RADIUS + " blocks of you (carry off: plain follow)";
            }
            case UNLOAD -> {
                r.phase = Phase.STEPS;
                r.steps.add("deposit");
                r.steps.add("come");
                reply = "ok: unloading - home, deposit, then back to you";
            }
            case FETCH -> {
                String id = commands.crafting.planner.resolveItem(a.item(), Gui.inventory(p));
                if (id == null) return Chains.Reply.now("error: " + a.item() + " is not an item id (find it with recipe <part of the name>)");
                // what it keeps of that item stays on top of the n it brings
                List<StorageRules.Held> inv = new ArrayList<>(Storage.held(p));
                boolean food = false;
                int bag = 0;
                for (StorageRules.Held h : inv) if (id.equals(h.id())) { food |= h.food(); bag += h.n(); }
                inv.add(new StorageRules.Held(id, a.n(), food));
                MuleRules.Giveable g = MuleRules.giveable(inv, id, commands.storage.keeps());
                if (g.n() <= 0) return Chains.Reply.now("error: I can't give " + GuiCore.shortId(id) + " - " + g.why());
                int keep = bag + a.n() - g.n();
                int stored = Crafting.totals(commands.crafting.storageSources(p)).getOrDefault(id, 0);
                r.steps.addAll(MuleRules.fetchPlan(id, a.n() + keep, bag, stored));
                r.phase = Phase.STEPS;
                r.item = id;
                r.want = a.n();
                r.had = Math.max(0, Math.min(a.n(), bag - keep));
                reply = "ok: fetching " + a.n() + " " + GuiCore.shortId(id) + (r.steps.isEmpty() ? " (I have them)" : " (" + String.join(", then ", r.steps) + ", then to you)");
            }
            default -> { return Chains.Reply.now("error: " + a.kind()); }
        }
        commands.jobs.replaceWalk();
        r.req = commands.requests.local("mule", from, raw, reply, a.kind() == MuleRules.Kind.CARRY ? null : l);
        run = r;
        LOG.info("[entropybot] mule: {} {} for {}", verb, rest, from);
        if (a.kind() == MuleRules.Kind.CARRY) {
            r.req.finished = true;                       // like follow: a carry never "arrives"; nobody waits for it
            return Chains.Reply.now(reply);
        }
        return new Chains.Reply(reply, r.req);
    }

    /** Null when the bot can see (or has the companion's fix of) the player and may walk there, else the answer. */
    private String reachable(LocalPlayer p, String who, String from) {
        int[] t = target(who, from);
        if (t == null) return "I can't see " + (who.equalsIgnoreCase(from) ? "you" : who) + " from here (I'm at " + Jobs.fmt(Jobs.here(p)) + ")";
        String why = commands.jobs.goalAllowed(t[0], t[1], t[2]);
        return why == null ? null : FenceRules.comeRefusal(why, t[0], t[2]);
    }

    /** The player's block: in view, else (only the sender themselves) the companion's fix; null: neither. */
    private int[] target(String who, String from) {
        Player pl = Jobs.findPlayer(who);
        if (pl != null) return Jobs.here(pl);
        return who.equalsIgnoreCase(from) ? commands.ownerFixPos(who) : null;
    }

    // ==== the driver ====

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
        if (commands.holding() || p.isDeadOrDying()) {      // a fight or a meal: the reflex has the bot
            r.goal = null;
            return;
        }
        switch (r.phase) {
            case HOLD -> hold(r, p);
            case GIVE -> give(r, p);
            case CARRY -> carry(r, p);
            case STEPS -> nextStep(r, p);
        }
    }

    private void hold(Run r, LocalPlayer p) {
        if (core.tick() - r.phaseAt > MuleRules.HOLD_TICKS) {
            stopWalk();
            end(r, MuleRules.holdText(r.invBefore, Gui.inventory(p)));
            return;
        }
        if (Gui.open(p)) return;                            // never picks up while a chest is open
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : p.clientLevel.entitiesForRendering()) {
            if (!(e instanceof ItemEntity ie) || !e.isAlive()) continue;
            MuleRules.Seen s = new MuleRules.Seen(e.getId(), Gui.itemId(ie.getItem()), thrower(ie), e.distanceTo(p));
            if (!MuleRules.holdTakes(s, r.from, r.before) || !room(p, ie) || !allowed(e)) continue;
            if (s.dist() < bd) { bd = s.dist(); best = ie; }
        }
        if (best != null) walkTo(r, block(best), 0);
    }

    private void carry(Run r, LocalPlayer p) {
        int[] o = target(r.from, r.from);
        if (o == null) {                                    // out of sight and no fix: wait where it is
            stopWalk();
            r.goal = null;
            return;
        }
        String why = commands.jobs.goalAllowed(o[0], o[1], o[2]);
        if (why != null) {
            stopWalk();
            end(r, "stopped: carry - " + FenceRules.followRefusal(r.from, o[0], o[2]));
            return;
        }
        if (!Gui.open(p)) {
            ItemEntity best = null;
            double bd = Double.MAX_VALUE;
            for (Entity e : p.clientLevel.entitiesForRendering()) {
                if (!(e instanceof ItemEntity ie) || !e.isAlive()) continue;
                double dx = e.getX() - (o[0] + 0.5), dy = e.getY() - o[1], dz = e.getZ() - (o[2] + 0.5);
                MuleRules.Seen s = new MuleRules.Seen(e.getId(), Gui.itemId(ie.getItem()), null, Math.sqrt(dx * dx + dy * dy + dz * dz));
                if (!MuleRules.carryTakes(s, r.carry) || !room(p, ie) || !allowed(e)) continue;
                double d = e.distanceTo(p);
                if (d < bd) { bd = d; best = ie; }
            }
            if (best != null) {
                walkTo(r, block(best), 0);
                return;
            }
        }
        double d = Math.sqrt(p.distanceToSqr(o[0] + 0.5, o[1], o[2] + 0.5));
        if (d > MuleRules.GIVE_REACH) walkTo(r, o, 2);
        else if (r.goal != null) {
            stopWalk();
            r.goal = null;
        }
    }

    private void give(Run r, LocalPlayer p) {
        int[] t = target(r.to, r.from);
        if (r.throwsLeft == null) {
            if (t == null) {
                stopWalk();
                end(r, "error: I can't see " + (r.to.equalsIgnoreCase(r.from) ? "you" : r.to) + " any more (I'm at " + Jobs.fmt(Jobs.here(p)) + ")");
                return;
            }
            String why = commands.jobs.goalAllowed(t[0], t[1], t[2]);
            if (why != null) {
                stopWalk();
                end(r, "stopped: " + FenceRules.comeRefusal(why, t[0], t[2]));
                return;
            }
            if (core.tick() - r.phaseAt > 20 * 90) {
                stopWalk();
                end(r, "error: I couldn't get within " + MuleRules.GIVE_REACH + " blocks of " + r.to + " in 90 s");
                return;
            }
            double d = Math.sqrt(p.distanceToSqr(t[0] + 0.5, t[1], t[2] + 0.5));
            if (d > MuleRules.GIVE_REACH) {
                walkTo(r, t, 2);
                return;
            }
            stopWalk();
            if (Gui.open(p)) Gui.close(p);
            // re-check what it may give now (a meal or a fight on the way may have used some)
            MuleRules.Giveable g = MuleRules.giveable(Storage.held(p), r.item, commands.storage.keeps());
            int n = Math.min(r.want, g.n());
            if (n <= 0) {
                end(r, "error: I can't give " + GuiCore.shortId(r.item) + " any more - " + g.why());
                return;
            }
            if (n < r.want) r.note = "only " + n + " of " + r.want + (g.why() == null ? "" : " - " + g.why());
            r.throwsLeft = new ArrayList<>(MuleRules.throwPlan(n, maxStack(p, r.item)));
            r.faced = 0;
        }
        // face them (the server throws in the look direction), then one throw per call
        Vec3 at = t != null ? new Vec3(t[0] + 0.5, t[1] + 1.2, t[2] + 0.5) : null;
        Player pl = Jobs.findPlayer(r.to);
        if (pl != null) at = pl.getEyePosition();
        if (at != null) try { p.lookAt(EntityAnchorArgument.Anchor.EYES, at); } catch (RuntimeException ignored) {}
        if (r.faced++ == 0) return;                         // the new look goes out with the next movement packet
        if (r.throwsLeft.isEmpty()) {
            end(r, r.given > 0 ? MuleRules.gaveText(r.to, r.from, r.given, r.item, r.note) + (r.kind == MuleRules.Kind.FETCH ? fetchTail(r) : "")
                    : "error: I couldn't throw any " + GuiCore.shortId(r.item));
            return;
        }
        int k = r.throwsLeft.remove(0);
        int thrown = throwItems(p, r.item, k);
        if (thrown > 0) r.given += thrown;
        else r.throwsLeft.clear();
    }

    private static final java.util.regex.Pattern DROPPED = java.util.regex.Pattern.compile("^ok: dropped (\\d+)");

    /**
     * 0.21.0: the one throw helper (give/carry/fetch here, the escort's food in {@code engine.Escort.feed}): throws up to
     * {@code n} of {@code item} (full id) with the inventory menu's THROW click, in the bot's look direction (face the
     * target first). Returns how many went; 0 when none could (logged).
     */
    public static int throwItems(LocalPlayer p, String item, int n) {
        if (n <= 0) return 0;
        String res = GuiCore.drop(new McMenu(p), item + " " + n);
        java.util.regex.Matcher m = DROPPED.matcher(res == null ? "" : res);
        if (m.find()) return Integer.parseInt(m.group(1));
        LOG.info("[entropybot] throw {} {}: {}", n, item, res);
        return 0;
    }

    private String fetchTail(Run r) {
        return " - " + MuleRules.fetchedText(r.given, r.item, r.took, r.made, r.had).replaceFirst("^fetched \\d+ \\S+: ?", "");
    }

    private void nextStep(Run r, LocalPlayer p) {
        if (r.steps.isEmpty()) {
            if (r.kind == MuleRules.Kind.FETCH) {
                int bag = Gui.inventory(p).getOrDefault(r.item, 0);
                if (bag <= 0) {
                    end(r, "error: fetch " + GuiCore.shortId(r.item) + ": I got none - " + (r.lastMsg == null ? "" : Gathering.shortMsg(r.lastMsg)));
                    return;
                }
                r.phase = Phase.GIVE;
                r.phaseAt = core.tick();
                return;
            }
            end(r, "ok: unloaded and back with you" + (r.lastMsg == null ? "" : " (" + Gathering.shortMsg(r.lastMsg) + ")"));
            return;
        }
        if (commands.jobs.running() && !commands.jobs.walking()) return;
        r.step = r.steps.remove(0);
        r.beforeStep = r.item == null ? 0 : Gui.inventory(p).getOrDefault(r.item, 0);
        Chains.Reply rep;
        try {
            rep = commands.handle(r.from, r.step, true, null);
        } catch (RuntimeException e) {
            rep = Chains.Reply.now("error: " + e);
        }
        if (run != r) return;
        if (rep.pending() != null && !rep.pending().finished) {
            r.sub = rep.pending();
            return;
        }
        afterStep(r, p, rep.pending() != null && rep.pending().doneMsg != null ? rep.pending().doneMsg : rep.text() == null ? "" : rep.text());
    }

    private void afterStep(Run r, LocalPlayer p, String msg) {
        String step = r.step == null ? "" : r.step;
        if (r.item != null) {
            int gain = Gui.inventory(p).getOrDefault(r.item, 0) - r.beforeStep;
            if (step.startsWith("get ")) r.took += Math.max(0, gain);
            else if (step.startsWith("gather ")) r.made += Math.max(0, gain);
        }
        if (r.kind == MuleRules.Kind.UNLOAD) {
            if (step.equals("deposit")) {
                r.lastMsg = msg;
                if (Texts.stepFailed(msg) && !msg.contains("nothing to deposit")) {
                    end(r, "stopped: unload - " + msg.replaceFirst("^(error|stopped): ", ""));
                }
                return;
            }
            if (step.equals("come") && (Texts.stepFailed(msg) || msg.startsWith("I can't see"))) {
                end(r, "ok: unloaded (" + Gathering.shortMsg(r.lastMsg) + ") - staying here: " + msg.replaceFirst("^(error|stopped): ", ""));
            }
            return;
        }
        r.lastMsg = msg;
        if (msg.matches("^(stopped|interrupted): (attacked by|low health).*")) {
            r.steps.add(0, step);                          // a fight cut it short: again once it is over
            return;
        }
        if (Texts.stepFailed(msg) && step.startsWith("get ") && !r.steps.isEmpty()) return;     // the gather after it covers it
        if (Texts.stepFailed(msg) && !step.startsWith("get ")) {
            int bag = Gui.inventory(p).getOrDefault(r.item, 0);
            if (bag <= 0) end(r, "stopped: fetch " + GuiCore.shortId(r.item) + " - " + step + ": " + msg.replaceFirst("^(error|stopped): ", ""));
        }
    }

    // ==== helpers ====

    private void walkTo(Run r, int[] at, int range) {
        IBaritone b = Jobs.baritone();
        if (b == null) return;
        boolean same = r.goal != null && Math.abs(r.goal[0] - at[0]) <= 1 && Math.abs(r.goal[1] - at[1]) <= 1 && Math.abs(r.goal[2] - at[2]) <= 1;
        if (same && !Jobs.idle(b)) return;
        if (commands.jobs.goalAllowed(at[0], at[1], at[2]) != null) return;
        r.goal = at.clone();
        Jobs.safeSettings();
        BlockPos pos = new BlockPos(at[0], at[1], at[2]);
        b.getCustomGoalProcess().setGoalAndPath(range <= 0 ? new GoalBlock(pos) : new GoalNear(pos, range));
    }

    private static void stopWalk() {
        IBaritone b = Jobs.baritone();
        if (b != null && !Jobs.idle(b)) Jobs.cancel(b);
    }

    private boolean allowed(Entity e) {
        int[] at = block(e);
        return commands.jobs.goalAllowed(at[0], at[1], at[2]) == null;
    }

    private static int[] block(Entity e) {
        return new int[]{(int) Math.floor(e.getX()), (int) Math.floor(e.getY() + 0.01), (int) Math.floor(e.getZ())};
    }

    private static boolean room(LocalPlayer p, ItemEntity ie) {
        return p.getInventory().getFreeSlot() >= 0 || p.getInventory().getSlotWithRemainingSpace(ie.getItem()) >= 0;
    }

    /** The thrower's name when the client knows it (usually not: the server keeps it), else null. */
    private static String thrower(ItemEntity ie) {
        try {
            Entity o = ie.getOwner();
            return o instanceof Player pl ? pl.getGameProfile().getName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int maxStack(LocalPlayer p, String id) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && Gui.itemId(s).equals(id)) return s.getMaxStackSize();
        }
        return 64;
    }

    private void end(Run r, String text) {
        if (run != r) return;
        run = null;
        LOG.info("[entropybot] mule: {}", text);
        if (r.req != null && !r.req.finished) commands.requests.done(r.req.id, text);
        else if (r.req != null && r.kind == MuleRules.Kind.CARRY && !text.startsWith("ok: carry off")) commands.whisper(r.from, text);
    }

    /** "stop" (and a death): the run ends now. Returns what ran, or null. */
    String stop(String why) {
        Run r = run;
        if (r == null) return null;
        stopWalk();
        String what = r.kind.name().toLowerCase();
        if (r.kind == MuleRules.Kind.CARRY) {
            run = null;                                     // a carry has nobody waiting: stop's own answer says it
            return what;
        }
        end(r, "stopped: " + what + " - " + why);
        return what;
    }
}
