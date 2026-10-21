package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rm-execution: {@link Steps.Hands} in the game. Baritone underneath: a walk is the travel job's one Baritone goal
 * (GoalNear when the step asks for it); a tree or a block to collect is Baritone's own MineProcess on the blocks the
 * game data names (logs: the block tag; a collected item: the blocks whose loot gives it), counted in the bag; a hunt
 * walks with GoalNear to the animal and hands it to the strike input (the reflexes' fight loop); a build is the builder
 * leg ({@code JobHands.build}); the item actions (craft, smelt, cook, scan) and the mine verb's per-ore and discovery
 * steps start their own job with one command and are followed through its request. Nothing here picks a stand spot, a
 * face or a click target. Loader notes: Baritone API (MineProcess, GoalNear, GoalXZ, BlockOptionalMetaLookup), vanilla
 * registries, block tags; the loot book read on the integrated server. Client thread only. Never throws (a failure is a
 * FAILED status with its reason).
 */
public final class StepHands implements Steps.Hands {
    private static final Logger LOG = LogUtils.getLogger();

    private final Commands c;
    /** The running step's own state. */
    private Step step;
    private JobRequests.Request req;
    private String instant;
    private Jobs.Job walkJob;
    private int startCount, want;
    private List<String> countIds = List.of();
    private boolean mining;
    private String miningWhat;
    // the hunt
    private int huntTarget = -1, huntTicks;
    private int[] huntDrop;
    private boolean huntArrived, huntVisited;
    private long huntWalkAt = -1;
    private String huntWhy;

    StepHands(Commands c) {
        this.c = c;
    }

    @Override public String start(Step s) {
        reset();
        step = s;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "error: not in a world";
        try {
            return switch (s) {
                case Step.Walk w -> walk(w);
                case Step.ChopTree t -> chop(p, t);
                case Step.Collect k -> collect(p, k);
                case Step.HuntAt h -> hunt(p, h);
                case Step.Build b -> build(b);
                case Step.Climb k -> climb(p);
                default -> verb(StepCommands.command(s));   // the item actions and the mine verb's steps: one command, its own job
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} did not start: {}", s.text(), e.toString());
            return "error: " + s.text() + ": " + e;
        }
    }

    @Override public Car.Status status(Step s) {
        if (s != step) return Car.Status.failed("not the running step");
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return Car.Status.failed("not in a world");
        try {
            return switch (s) {
                case Step.ChopTree t -> mineStatus(p);
                case Step.Collect k -> mineStatus(p);
                case Step.HuntAt h -> huntStatus(p, h);
                case Step.Build b -> buildStatus(b);
                case Step.Climb k -> climbStatus();
                default -> requestStatus();
            };
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: {} status: {}", s.text(), e.toString());
            return Car.Status.failed("status: " + e);
        }
    }

    @Override public void cancel(String why) {
        try {
            if (mining) {
                IBaritone b = Jobs.baritone();
                if (b != null) b.getMineProcess().cancel();
                Jobs.safeSettings();
            }
            if (step instanceof Step.HuntAt) {
                Core.INSTANCE.reflexes.stopAttack();
                IBaritone b = Jobs.baritone();
                if (b != null) Jobs.cancel(b);
            }
            if (step instanceof Step.Build) io.github.mojolowjo.entropybot.aa.moves.JobHands.get().stop(why);
            if (step instanceof Step.Climb && climbing && c.chains.running() && "climb".equals(c.chains.name())) c.chains.clear();
            if ((req != null && !req.finished) || walkJob != null) {
                if (c.jobs.running()) c.jobs.finish("stopped: " + why);
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: cancel of {} ({}) failed: {}", step == null ? "-" : step.text(), why, e.toString());
        }
        reset();
    }

    private void reset() {
        step = null;
        req = null;
        instant = null;
        walkJob = null;
        mining = false;
        miningWhat = null;
        countIds = List.of();
        huntTarget = -1;
        huntTicks = 0;
        huntDrop = null;
        huntArrived = false;
        huntVisited = false;
        huntWalkAt = -1;
        huntWhy = null;
        climbing = false;
        climbEnd = null;
    }

    /** rm-execution2: the planner's routine for a Plan step, typed at the planner's side (AaRuntime.resolveState). */
    @Override public Steps.Routine routine(Step.Plan p) {
        if (c.aaRuntime == null) return new Steps.Routine(null, "no planner (aa not loaded)");
        var r = c.aaRuntime.resolveState(p.goal());
        return switch (r.state()) {
            case READY -> new Steps.Routine(r.steps(), null);
            case SEARCHING -> new Steps.Routine(null, "the planner still searches for " + p.goal());
            case NONE -> new Steps.Routine(null, r.why());
        };
    }

    // ---- the climb: the climb verb plans its own staircase and runs it as a chain of its own ----

    private boolean climbing, climbHooked;
    private String climbEnd;

    private String climb(LocalPlayer p) {
        if (!climbHooked) {
            climbHooked = true;
            c.chains.onEnd((name, msg) -> {
                if (climbing && "climb".equals(name)) climbEnd = msg == null ? "ended without a word" : msg;
            });
        }
        String r = DigCommands.climb(c, p, "", c.owner());
        if (r != null && r.startsWith("ok")) {
            instant = r;
            return null;
        }
        if (r == null || !r.startsWith("started")) return "error: climb: " + (r == null ? "no answer" : r);
        climbing = true;
        return null;
    }

    private Car.Status climbStatus() {
        if (instant != null) return Car.Status.done(instant);
        if (climbEnd != null) {
            String m = climbEnd;
            climbing = false;
            return m.startsWith("done") ? Car.Status.done(m) : Car.Status.failed("climb: " + m);
        }
        if (c.chains.running() && "climb".equals(c.chains.name())) return Car.Status.running("climbing: " + c.chains.stepText());
        climbing = false;
        return Car.Status.failed("climb: its chain went without an end (stopped by someone)");
    }

    // ---- the item actions and the mine verb's steps: one command, its job's request ----

    private String verb(String text) {
        Chains.Reply r;
        var d = io.github.mojolowjo.entropybot.aa.door.Door.current() == null ? io.github.mojolowjo.entropybot.aa.door.Driver.AA : io.github.mojolowjo.entropybot.aa.door.Door.current();
        try (var sc = io.github.mojolowjo.entropybot.aa.door.Door.scope(d)) {
            r = c.handle(c.owner(), text, true, null);
        }
        if (r == null) return "error: " + text + ": no answer";
        if (r.pending() != null) {
            req = r.pending();
            if (Texts.stepFailed(req.reply)) return "error: " + req.reply;
            return null;
        }
        String t = r.text() == null ? "" : r.text();
        if (Texts.stepFailed(t)) return t.startsWith("error") ? t : "error: " + t;
        instant = t;
        return null;
    }

    private Car.Status requestStatus() {
        if (instant != null) return Car.Status.done(instant);
        if (req == null) return Car.Status.failed("no job");
        if (!req.finished) return Car.Status.running(c.jobs.running() ? c.jobs.job.status : step.text());
        String m = req.doneMsg == null ? "" : req.doneMsg;
        if (req.failed || Texts.stepFailed(m) || !(m.startsWith("ok") || m.startsWith("done"))) return Car.Status.failed(m.isEmpty() ? "ended without a word" : m);
        return Car.Status.done(m);
    }

    // ---- walk: the travel job's one Baritone goal ----

    private String walk(Step.Walk w) {
        String r = verb("goto " + w.x() + " " + w.y() + " " + w.z());
        if (r != null) return r;
        if (c.jobs.running() && "travel".equals(c.jobs.job.type)) {
            walkJob = c.jobs.job;
            if (w.near() > 0) {
                GoalNear g = new GoalNear(new BlockPos(w.x(), w.y(), w.z()), w.near());
                IBaritone b = Jobs.baritone();
                if (walkJob.finalGoal != null) walkJob.finalGoal = g;           // the far walk's last leg
                else if (b != null) Jobs.walkGoal(b, walkJob, g);
            }
        }
        return null;
    }

    // ---- Baritone's MineProcess: a tree, a block to collect ----

    private String chop(LocalPlayer p, Step.ChopTree t) {
        List<Block> blocks = new ArrayList<>();
        if (t.log() != null) {
            Block b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(t.log())).orElse(null);
            if (b == null) return "error: no block " + t.log();
            blocks.add(b);
        } else {
            // the owner's logs kind (the game's log tag, less what "kinds logs exclude" left out)
            for (String id : c.vocab.expand("logs")) {
                Block b = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).map(Block::byItem).orElse(net.minecraft.world.level.block.Blocks.AIR);
                if (b != net.minecraft.world.level.block.Blocks.AIR && b.defaultBlockState().is(BlockTags.LOGS) && !blocks.contains(b)) blocks.add(b);
            }
        }
        if (blocks.isEmpty()) return "error: no log block left in the logs kind (kinds logs)";
        List<String> ids = new ArrayList<>();
        for (Block b : blocks) ids.add(BuiltInRegistries.ITEM.getKey(b.asItem()).toString());
        return mine(p, blocks, ids, t.logs(), "cut " + t.logs() + " " + (t.log() == null ? "logs" : t.log().replaceFirst("^minecraft:", "")));
    }

    private String collect(LocalPlayer p, Step.Collect k) {
        String id = k.item().contains(":") ? k.item() : "minecraft:" + k.item();
        // the blocks that give it: the game's loot data (LootBook), and the block of the item itself
        List<Block> blocks = new ArrayList<>();
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        if (book != null) for (String bid : book.blocksDropping(id))
            BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(bid)).ifPresent(blocks::add);
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).ifPresent(it -> {
            Block own = Block.byItem(it);
            if (own != net.minecraft.world.level.block.Blocks.AIR && !blocks.contains(own)) blocks.add(own);
        });
        if (blocks.isEmpty()) return "error: no block gives " + k.item() + (book == null || book.isEmpty() ? " (no loot data in this world)" : " (the game's loot data)");
        return mine(p, blocks, List.of(id), k.n(), "collect " + k.n() + " " + k.item());
    }

    private String mine(LocalPlayer p, List<Block> blocks, List<String> ids, int n, String what) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        countIds = ids;
        startCount = count(p);
        want = Math.max(1, n);
        try {
            Jobs.safeSettings();
            var s = baritone.api.BaritoneAPI.getSettings();
            s.allowBreak.value = true;
            s.allowBreakAnyway.value = new ArrayList<>(blocks);
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE, "steps");
        } catch (RuntimeException | LinkageError e) {
            return "error: Baritone's mine settings: " + e;
        }
        // quantity 0: Baritone mines on; the step counts what came into the bag itself (a drop's item, not the block's)
        b.getMineProcess().mine(0, new BlockOptionalMetaLookup(blocks.toArray(new Block[0])));
        mining = true;
        miningWhat = what;
        LOG.info("[entropybot] steps: {} - Baritone's mine process on {} block kind(s)", what, blocks.size());
        return null;
    }

    private int count(LocalPlayer p) {
        Map<String, Integer> bag = Gui.inventory(p);
        int n = 0;
        for (String id : countIds) n += bag.getOrDefault(id, 0);
        return n;
    }

    private Car.Status mineStatus(LocalPlayer p) {
        int got = count(p) - startCount;
        IBaritone b = Jobs.baritone();
        if (got >= want) {
            if (b != null) b.getMineProcess().cancel();
            Jobs.safeSettings();
            mining = false;
            return Car.Status.done("ok: " + miningWhat + ": got " + got);
        }
        if (b == null) return Car.Status.failed("baritone not loaded");
        if (!b.getMineProcess().isActive() && !Core.INSTANCE.reflexes.hold()) {
            Jobs.safeSettings();
            mining = false;
            return Car.Status.failed(miningWhat + ": Baritone's mine process ended with " + got + " of " + want + " (no more it could reach)");
        }
        return Car.Status.running(miningWhat + ": " + got + " of " + want);
    }

    // ---- build: the builder leg ----

    private String build(Step.Build b) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().build(b.cells(), b.items(), (int) (20 * Tuning.need("hands.buildS")), "steps", null, false);
        return r.failed() ? "error: " + r.why() : null;
    }

    private Car.Status buildStatus(Step.Build b) {
        var r = io.github.mojolowjo.entropybot.aa.moves.JobHands.get().build(b.cells(), b.items(), (int) (20 * Tuning.need("hands.buildS")), "steps", null, false);
        if (r.done()) return Car.Status.done("ok: " + b.text());
        if (r.failed()) return Car.Status.failed(r.why());
        return Car.Status.running(r.why());
    }

    // ---- hunt: GoalNear on the animal, the strike stays the reflexes' input ----

    private String hunt(LocalPlayer p, Step.HuntAt h) {
        String item = h.forItem() != null ? (h.forItem().contains(":") ? h.forItem() : "minecraft:" + h.forItem())
                : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().get(h.type());
        if (item == null) return "error: the " + h.type() + "'s loot gives no food (name what to hunt it for)";
        if (!c.stepRun.on()) return "error: hunting is off (the owner's hunting off; hunting on lets me)";
        if (!c.vocab.expand("animals").contains(h.type())) return "error: " + h.type().replaceFirst("^minecraft:", "") + " is not in the animals kind (kinds animals add ...)";
        countIds = List.of(item);
        startCount = count(p);
        want = Math.max(1, h.forItem() != null ? h.forCount() : h.n());
        return null;
    }

    private Car.Status huntStatus(LocalPlayer p, Step.HuntAt h) {
        int got = count(p) - startCount;
        IBaritone b = Jobs.baritone();
        if (got >= want) {
            if (b != null) Jobs.cancel(b);
            return Car.Status.done("ok: hunted " + got + " " + countIds.get(0).replaceFirst("^minecraft:", ""));
        }
        if (++huntTicks % 10 != 0) return Car.Status.running(huntWhy == null ? step.text() : huntWhy);
        var refl = Core.INSTANCE.reflexes;
        Minecraft mc = Minecraft.getInstance();
        if (refl.forcing()) {                                        // the strike runs on the animal: follow its spot
            Entity tg = huntTarget >= 0 ? mc.level.getEntity(huntTarget) : null;
            if (tg != null) huntDrop = new int[]{tg.getBlockX(), tg.getBlockY(), tg.getBlockZ()};
            return Car.Status.running("striking the " + h.type().replaceFirst("^minecraft:", ""));
        }
        if (refl.hold()) return Car.Status.running("a reflex holds me");
        if (huntTarget >= 0) {
            // the last one died or got away: walk over where it was for the drops (one goal)
            Entity old = mc.level.getEntity(huntTarget);
            huntTarget = -1;
            if ((old == null || !old.isAlive()) && huntDrop != null && b != null) {
                b.getCustomGoalProcess().setGoalAndPath(new GoalNear(new BlockPos(huntDrop[0], huntDrop[1], huntDrop[2]), 0));
                huntDrop = null;
                return Car.Status.running(huntWhy = "picking up the drops");
            }
        }
        if (b != null && b.getCustomGoalProcess().isActive() && huntWhy != null && huntWhy.startsWith("picking")) return Car.Status.running(huntWhy);
        double atR = Tuning.get("steps.huntAtR");
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive()) continue;
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(h.type())) continue;
            if (h.at() != null && Math.hypot(e.getX() - h.at()[0], e.getZ() - h.at()[1]) > atR) continue;
            if (io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e) || e.hasCustomName() || (e instanceof AgeableMob am && am.isBaby())) continue;
            double d = p.distanceTo(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        String name = h.type().replaceFirst("^minecraft:", "");
        if (best == null) {
            if (h.at() != null && !huntArrived) {
                if (Math.hypot(p.getX() - h.at()[0], p.getZ() - h.at()[1]) <= atR) huntArrived = true;
                else {
                    if (b != null && (huntWalkAt < 0 || !b.getCustomGoalProcess().isActive())) {
                        huntWalkAt = huntTicks;
                        b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(h.at()[0], h.at()[1]));
                    }
                    return Car.Status.running(huntWhy = "walking to " + h.at()[0] + " " + h.at()[1] + " where I saw " + name);
                }
            }
            if (h.at() != null) visit(h, false, 0);
            return Car.Status.failed("hunt: " + got + " of " + want + " - no " + name + " I may take in view" + (h.at() == null ? "" : " (none at the place I saw them)"));
        }
        if (h.at() != null) visit(h, true, 1);
        if (bestD > Tuning.get("steps.huntReach")) {
            if (b != null) b.getCustomGoalProcess().setGoalAndPath(new GoalNear(best.blockPosition(), (int) Tuning.get("steps.walkNear")));
            return Car.Status.running(huntWhy = "walking to a " + name + " " + Math.round(bestD) + " away");
        }
        if (b != null && b.getCustomGoalProcess().isActive()) Jobs.cancel(b);
        String r = refl.forceAttack(best, name);
        huntTarget = best.getId();
        huntDrop = new int[]{best.getBlockX(), best.getBlockY(), best.getBlockZ()};
        LOG.info("[entropybot] steps: hunt: {}", r);
        if (r.startsWith("error")) return Car.Status.failed("hunt: " + r.replaceFirst("^error: ", ""));
        return Car.Status.running(huntWhy = "striking the " + name);
    }

    /** AA_ROUND3 5: the visit's outcome for the herd's sighting at the step's place (Sightings), once. Never throws. */
    private void visit(Step.HuntAt h, boolean found, int count) {
        if (huntVisited) return;
        huntVisited = true;
        try {
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            var lv = Minecraft.getInstance().level;
            String dim = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
            if (sg == null || lv == null || dim == null) return;
            sg.visit(dim, h.type(), h.at()[0], h.at()[1], found, count, lv.getGameTime() * 50);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] steps: hunt sighting visit: {}", e.toString());
        }
    }
}
