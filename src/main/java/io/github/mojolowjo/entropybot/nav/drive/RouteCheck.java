package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.ghost.CheckBudget;
import io.github.mojolowjo.entropybot.ghost.GhostInput;
import io.github.mojolowjo.entropybot.ghost.GhostPlayer;
import io.github.mojolowjo.entropybot.nav.check.CheckBook;
import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Nav step 9c, the route checker (report only): each part the pilot is handed is flown first by a ghost of the player
 * ({@link GhostPlayer}, the game's own movement code on a copy that is never in the world) driven by a second copy of
 * our own {@link Pilot} and {@link MoveDriver}s on the same live cells, so the ghost drives exactly as the real body will.
 * The ghost changes no blocks, so a part is flown only up to its first move that needs the hands (a break or a placed
 * block): "flown". It answers pass, refuse with the pilot's own reason (off the route, too slow, a move failed), or
 * unchecked (out of time, an error); it runs under {@link CheckBudget} beside the real walk and never holds it up. When
 * the real part ends, both go into {@link CheckBook}; a disagreement logs both tracks. Known limits: at a part boundary
 * the real body coasts one tick with no keys while the next part is planned, the ghost does not; the ghost's sprint
 * follows the keys, food and a bump, not vanilla's other sprint stops (water, blindness). Pure: no (the ghost is a game
 * entity; the books are pure). Loader notes: {@link GhostPlayer}'s accessor mixins (vanilla Entity, LivingEntity),
 * {@code Attributes.SNEAKING_SPEED}; Fabric the same.
 */
final class RouteCheck {
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();

    final CheckBook book = new CheckBook();
    private Job job;
    private int gameTick, errors;

    static boolean on() {
        return Tuning.i("ghost.check") != 0;
    }

    /** A new part for the real pilot: the ghost starts flying it (an older unfinished part ends as unknown). */
    void start(Path path, LiveRoute route, LocalPlayer p, float walkTurn, double sensitivity) {
        realEnded(CheckBook.Real.UNKNOWN, "replaced by the next part");
        if (!on()) return;
        Job j = new Job(book.newPart(), gameTick);
        job = j;
        long t0 = System.nanoTime();
        try {
            MoveDriver.MoveWorld w = route.world();
            int hands = firstHands(path, w);
            Path fly = hands < 0 ? path : Path.of(path.positions().subList(0, hands + 1), path.steps().subList(0, hands), path.getGoal());
            j.flownTo = hands;
            if (fly.steps().isEmpty()) {
                j.verdict(CheckBook.Ghost.FLOWN, "move 1 needs the hands");
                return;
            }
            j.ghost = GhostPlayer.of(p);
            j.pilot.start(fly, "ghost");
            j.route = route;
            j.walkTurn = walkTurn;
            j.sensitivity = sensitivity;
            j.sneak = (float) p.getAttributeValue(Attributes.SNEAKING_SPEED);
            j.food = p.getFoodData().getFoodLevel() > 6 || p.getAbilities().mayfly;   // structural: LocalPlayer.hasEnoughFoodToStartSprinting
        } catch (RuntimeException | LinkageError e) {
            error(j, e);
        } finally {
            book.cost((System.nanoTime() - t0) / 1e6);                        // PR 6 review: the scan and the ghost's build count too
        }
    }

    /** One game tick: the ghost flies under the budget. Never throws. */
    void tick() {
        gameTick++;
        Job j = job;
        if (j == null || j.ghostDone()) return;
        long t0 = System.nanoTime();
        try {
            CheckBudget budget = new CheckBudget(Tuning.i("ghost.ticksPerTick"), Tuning.get("ghost.msPerTick"), Tuning.i("ghost.deadlineTicks"));
            if (budget.late(gameTick - j.startTick)) {
                j.verdict(CheckBook.Ghost.UNCHECKED, "out of time after " + j.ghostTicks + " ghost ticks");
                return;
            }
            MoveDriver.MoveWorld w = j.route.world();
            int steps = 0;
            while (!j.ghostDone() && budget.mayStep(steps, (System.nanoTime() - t0) / 1e6)) {
                j.step(w);
                steps++;
            }
        } catch (RuntimeException | LinkageError e) {
            error(j, e);
        } finally {
            book.cost((System.nanoTime() - t0) / 1e6);
            j.tryClose();
        }
    }

    /** The real part ended (arrived, part done, failed, or unknown when stopped from outside). */
    void realEnded(CheckBook.Real real, String note) {
        Job j = job;
        if (j == null || j.real != null) return;
        j.real = real;
        j.note = note;
        if (!j.ghostDone()) j.verdict(CheckBook.Ghost.UNCHECKED, "the real part ended first, after " + j.ghostTicks + " ghost ticks");
        j.tryClose();
    }

    String status() {
        return book.status();
    }

    private void error(Job j, Throwable e) {
        errors++;
        if (errors == 1) LOG.warn("[entropybot] nav check: the ghost failed; this part is unchecked (error)", e);
        else if (errors % 100 == 0) LOG.warn("[entropybot] nav check: the ghost failed {} times: {}", errors, e.toString());   // structural: a count line now and then
        j.verdict(CheckBook.Ghost.UNCHECKED, "error: " + e);
    }

    /** The index of the first move that needs the hands now (a break in the way, a block to place), or -1. */
    static int firstHands(Path path, MoveDriver.MoveWorld w) {
        List<Step> steps = path.steps();
        MoveDriver.Body still = new MoveDriver.Body(0, 0, 0, 0, 0, 0, true, false, 0, 0);
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            MoveDriver d = MoveDriver.of(s);
            if (d.prepared(still, w, MoveDriver.Hands.NONE) != null) return i;
            MoveCatalog m = s.move();
            boolean places = switch (m) {
                case PILLAR -> !(w.isWater(s.src().x, s.src().y, s.src().z) && w.isWater(s.dest().x, s.dest().y, s.dest().z));
                case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST, ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST ->
                        !w.canWalkOn(s.dest().below());
                default -> false;
            };
            if (places) return i;
        }
        return -1;
    }

    /** One part being checked. */
    private final class Job {
        final int part, startTick;
        final Pilot pilot = new Pilot();
        GhostPlayer ghost;
        LiveRoute route;
        float walkTurn, sneak;
        double sensitivity;
        boolean food;
        int flownTo = -1, ghostTicks;
        CheckBook.Ghost ghostSays;
        String why;
        CheckBook.Real real;
        String note;
        final StringBuilder track = new StringBuilder();
        boolean closed;

        Job(int part, int startTick) {
            this.part = part;
            this.startTick = startTick;
        }

        boolean ghostDone() { return ghostSays != null; }

        void verdict(CheckBook.Ghost g, String reason) {
            if (ghostSays != null) return;
            ghostSays = g;
            why = reason;
            ghost = null;
            if (g == CheckBook.Ghost.REFUSE) LOG.info("[entropybot] nav check part {}: ghost refuses: {}", part, reason);
        }

        void step(MoveDriver.MoveWorld w) {
            ghostTicks++;
            GhostPlayer g = ghost;
            MoveDriver.Body b = new MoveDriver.Body(g.getX(), g.getY(), g.getZ(), g.getDeltaMovement().x, g.getDeltaMovement().y, g.getDeltaMovement().z,
                    g.onGround(), g.horizontalCollision, g.getYRot(), g.getXRot());
            Pilot.Act act = pilot.tick(b, w, MoveDriver.Hands.NONE, false, false, true, route::impossible);
            if (ghostTicks % 5 == 1) track.append(String.format(Locale.ROOT, " %.2f,%.2f,%.2f", g.getX(), g.getY(), g.getZ()));   // structural: a track point every 5 ticks
            if (act.ended() != null) {
                track.append(String.format(Locale.ROOT, " end %.2f,%.2f,%.2f", g.getX(), g.getY(), g.getZ()));
                String reason = act.ended().startsWith("ghost: ") ? act.ended().substring(7) : act.ended();
                verdict(act.ok() ? CheckBook.Ghost.PASS : CheckBook.Ghost.REFUSE, reason + " in " + ghostTicks + " ghost ticks");
                return;
            }
            if (act.replan()) {
                verdict(flownTo >= 0 ? CheckBook.Ghost.FLOWN : CheckBook.Ghost.PASS,
                        (flownTo >= 0 ? "to move " + (flownTo + 1) + ", the hands from there" : "the part done") + " in " + ghostTicks + " ghost ticks");
                return;
            }
            if (act.hand() != null) {
                verdict(CheckBook.Ghost.UNCHECKED, "a move wants the hands (" + pilot.where() + ")");
                return;
            }
            Keys k = new Keys();
            k.set(act.keys());
            float yaw = g.getYRot(), pitch = g.getXRot();
            if (act.yaw() != null || act.pitch() != null) {
                LookMath.Turn n = LookMath.step(yaw, pitch, act.yaw() == null ? yaw : act.yaw(), act.pitch() == null ? pitch : act.pitch(), walkTurn, sensitivity);
                yaw = n.yaw();
                pitch = n.pitch();
            }
            g.aim(yaw, pitch);
            Keys.Impulse i = k.impulse(sneak);                                  // as NavInput: the sneak key scales (PR 6 review)
            boolean sprint = k.isDown(Keys.Key.SPRINT) && k.isDown(Keys.Key.FORWARD) && food && !g.horizontalCollision;
            g.step(new GhostInput(i.forward(), i.left(), k.isDown(Keys.Key.JUMP), sprint, k.isDown(Keys.Key.SNEAK), yaw, pitch));
        }

        void tryClose() {
            if (closed || ghostSays == null || real == null) return;
            closed = true;
            if (job == this) job = null;
            CheckBook.Row row = new CheckBook.Row(part, ghostSays, why, real, note);
            Boolean a = book.close(row);
            if (Boolean.FALSE.equals(a)) {
                LOG.info("[entropybot] nav check DISAGREE {}; ghost track{}", row.line(), track);
            } else {
                LOG.info("[entropybot] nav check {}", row.line());
            }
        }
    }
}
