package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import io.github.mojolowjo.entropybot.threat.ThreatRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA_CLEANUP 4: the danger desire's options. Fight, flee, retreat home, cover, dig in, pillar are priced on the threat
 * facts ({@link ThreatFacts}) in seconds: the expected damage (health points x what a point is worth now, plus a likely
 * death) and the time lost. "Stay" is the baseline: the work goes on and the hit response strikes back when hit. The
 * desire's score is the seconds the best way saves over staying ({@code Tuning danger.perS} a second), so "a zombie and
 * a pickaxe" picks flee or dig-in by number, a sword fights, a skeleton at range is covered from. Creepers are left out:
 * the creeper flee is a reflex. Pure; JUnit DangerOptionsTest; loader notes: none.
 */
public final class DangerOptions {
    private DangerOptions() {}

    /** AA_STEP2 1: BOX (the built shell), BURROW (dig down, seal), BASE (flee home), SLEEP, WAIT (in my shell) are the night ways. */
    public enum Way { STAY, FIGHT, FLEE, HOME, COVER, DIG_IN, PILLAR, BOX, BURROW, BASE, SLEEP, WAIT;

        /** The memory key a failure is held by (Failures option): "box", "dig in"... */
        public String key() { return name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    }

    /**
     * AA_STEP2 1: the need behind the danger desire apart from the foes near: the night at the surface ({@code Costs.nightDanger}
     * x shelterAt1) or the day hide (hidePerHp x danger x missing health); score -1 when nothing threatens. The facts the
     * night ways take: in my shell, the bag's shell blocks, a bed known, a base known.
     */
    public record Need(double score, String why, double danger, boolean sheltered, int shellBlocks, boolean bed, boolean base) {
        public static final Need NONE = new Need(-1, "nothing threatens", 0, false, 0, false, false);
    }

    /** One way: possible here, the expected damage (hp), the time lost (s), the cost (s) and its terms. */
    public record Priced(Way way, boolean possible, double damage, double timeS, double costS, String why) {}

    /** The pick: the baseline, the best possible way, every way, the seconds saved and the desire's score (-1 none). */
    public record Choice(Priced stay, Priced best, List<Priced> all, double savedS, double score, String terms) {
        /** The chain for the best way, or null (staying is cheapest). */
        public String chain() { return best == null || best.way() == Way.STAY || score < 0 ? null : chainOf(best); }
    }

    /** A swing every 0.65 s with a sword or axe; a bare hand (hit 1) every 0.25 s. */
    static final double SWING_S = 0.65, FIST_S = 0.25;
    /** Seconds to dig two cells down (or into a wall) and seal; to jump-place a 3-high pillar. */
    static final double DIG_S = 3, PILLAR_S = 1.5;
    /** Blocks and head room a pillar needs (3 up: out of a zombie's reach). */
    static final int PILLAR_UP = 3;

    /** Prices every way on the facts. Pure. */
    public static Choice choose(ThreatFacts f) {
        List<ThreatFacts.Foe> foes = new ArrayList<>();
        int creepers = 0;
        if (f != null && f.foes() != null)
            for (ThreatFacts.Foe x : f.foes()) {
                if (ThreatRules.creeper(x.kind())) creepers++;
                else foes.add(x);
            }
        if (foes.isEmpty()) {
            String t = creepers > 0 ? "creeper only: the creeper flee is a reflex, nothing to price" : "no foes near";
            return new Choice(null, null, List.of(), 0, -1, t);
        }
        ThreatFacts.Me me = f.me();
        ThreatFacts.Ways w = f.ways() == null ? ThreatFacts.Ways.NONE : f.ways();
        double h = Tuning.get("danger.horizonS");
        double speed = ThreatFacts.runSpeed(me.food());
        double[] fight = fight(me, foes, false, h), stayFight = fight(me, foes, true, h);
        List<Priced> all = new ArrayList<>();
        // stay: the hit response fights the walkers that reach me with what I hold; ranged ones shoot on
        Priced stay = priced(me, Way.STAY, true, stayFight[0], stayFight[1], "work on, the hit response strikes back");
        all.add(stay);
        // AA_STEP2 1: fight only armed (Tuning danger.fightHit), with the exchange in my favour (the expected damage under my
        // health by the measured numbers) and never at a shooter out of reach
        boolean shooterOff = false;
        for (ThreatFacts.Foe x : foes) shooterOff |= x.ranged() && x.dist() > 3.5;
        String fightOff = me.weaponHit() < Tuning.get("danger.fightHit") ? "hit " + fmt(me.weaponHit()) + " under " + fmt(Tuning.get("danger.fightHit"))
                : fight[0] >= me.health() ? "the exchange is against me (" + fmt(fight[0]) + " hp taken, " + fmt(me.health()) + " left)"
                : shooterOff ? "a shooter out of reach in the open" : null;
        all.add(priced(me, Way.FIGHT, fightOff == null, fight[0], fight[1], fightOff != null ? fightOff : "kill them (hit " + fmt(me.weaponHit()) + ", " + fmt(fight[1]) + " s)"));
        // flee: the least-threat heading; slower walkers get one hit at most, the rest follow later (danger.fleeBack)
        double runT = w.fleeRun() / speed;
        double fleeD = w.fleeRun() > 0 ? away(foes, runT, speed) + Tuning.get("danger.fleeBack") * stayFight[0] : 0;
        all.add(priced(me, Way.FLEE, w.fleeRun() > 0, fleeD, 2 * runT, w.fleeRun() > 0 ? "run " + w.fleeRun() + " and back" : "cornered: no heading clear of mobs"));
        // round 4 fix 2 (live: "home saves 356s" at 3.7 hp with an 18.5 s walk, slain by the zombie 14 s later): the walk
        // home is a Baritone path at walking pace, not a sprint away, and every chaser follows it the whole way
        double homeT = w.baseDist() > 0 ? w.baseDist() / HOME_SPEED : 0;
        all.add(priced(me, Way.HOME, w.baseDist() > 0, w.baseDist() > 0 ? pursuit(foes, homeT, HOME_SPEED) : 0, 2 * homeT,
                w.baseDist() > 0 ? "the base " + Math.round(w.baseDist()) + " away, there and back, the chasers counted" : "no base in reach"));
        boolean ranged = false;
        for (ThreatFacts.Foe x : foes) ranged |= x.ranged();
        double moveT = w.coverDist() < 0 ? 0 : w.coverDist() / 4.3 + 0.5, coverD = 0;
        for (ThreatFacts.Foe x : foes) coverD += x.ranged() ? x.dps() * moveT : 0;
        coverD += meleeOnly(me, foes, h);
        all.add(priced(me, Way.COVER, ranged && w.coverDist() >= 0, coverD, moveT + Tuning.get("danger.coverS"),
                !ranged ? "nothing shoots" : w.coverDist() < 0 ? "no cell out of its sight" : "a cell out of sight " + w.coverDist() + " moves away"));
        double digD = 0;
        for (ThreatFacts.Foe x : foes) digD += x.dps() * Math.max(0, DIG_S - x.reachT());
        all.add(priced(me, Way.DIG_IN, w.digIn(), digD, DIG_S + Tuning.get("danger.holdS"), w.digIn() ? "dig in and seal, wait" : "no ground to dig into here"));
        double pilD = 0;
        for (ThreatFacts.Foe x : foes) {
            if (x.ranged() || x.flies()) pilD += x.dps() * h;                       // a pillar is no cover from arrows or fliers
            else if (x.move() == ThreatRules.Move.SPIDER) pilD += x.dps() * Math.max(0, h - x.reachT());       // spiders climb
            else pilD += x.dps() * Math.max(0, PILLAR_S - x.reachT());
        }
        boolean pillar = w.onGround() && w.pillarBlocks() >= PILLAR_UP && w.headRoom() >= PILLAR_UP;
        all.add(priced(me, Way.PILLAR, pillar, pilD, PILLAR_S + Tuning.get("danger.holdS"),
                pillar ? "pillar " + PILLAR_UP + " up, wait" : "no pillar (" + w.pillarBlocks() + " blocks, head room " + w.headRoom() + ")"));
        Priced best = stay;
        for (Priced p : all) if (p.possible() && p.costS() < best.costS()) best = p;
        double saved = stay.costS() - best.costS();
        double score = best == stay || saved < Tuning.get("danger.minS") ? -1 : Math.min(99, saved * Tuning.get("danger.perS"));
        return new Choice(stay, best, all, saved, score, terms(all, best, saved, score, creepers));
    }

    /** Seconds one health point is worth at this health (more as health runs low). */
    static double hpS(ThreatFacts.Me me) { return Tuning.get("danger.hpS") * me.maxHealth() / Math.max(1, me.health()); }

    /** 0..1: how likely this damage kills (its high band 1.25x reaches the health left). */
    static double risk(ThreatFacts.Me me, double damage) {
        if (damage <= 0) return 0;
        return Math.max(0, Math.min(1, (1.25 * damage - (me.health() - 1)) / Math.max(1, 0.5 * damage)));
    }

    static Priced priced(ThreatFacts.Me me, Way way, boolean possible, double damage, double timeS, String why) {
        double cost = hpS(me) * damage + Tuning.get("danger.deathS") * risk(me, damage) + timeS;
        return new Priced(way, possible, damage, timeS, cost, why);
    }

    /**
     * The fight: quickest kill first; a ranged mob out of reach is walked to first (unless {@code stay}: then it shoots
     * on for the horizon). {damage, seconds}.
     */
    static double[] fight(ThreatFacts.Me me, List<ThreatFacts.Foe> foes, boolean stay, double h) {
        List<ThreatFacts.Foe> order = new ArrayList<>(foes);
        order.sort((a, b) -> Double.compare(kill(me, a), kill(me, b)));
        double t = 0, dmg = 0;
        for (ThreatFacts.Foe x : order) {
            if (x.ranged() && x.dist() > 3.5) {
                if (stay) { dmg += x.dps() * h; continue; }
                t += (x.dist() - 2) / 4.3;
            }
            if (x.flies() && stay) { dmg += x.dps() * h; continue; }
            // staying, a walker reaches me first; fighting, I meet it halfway
            double start = stay ? x.reachT() : x.reachT() / 2;
            t = Math.max(t, start) + kill(me, x);
            dmg += x.dps() * Math.max(0, t - x.reachT());
        }
        return new double[]{dmg, t};
    }

    /** The damage the melee foes alone do while the hit response fights them where I stand. */
    static double meleeOnly(ThreatFacts.Me me, List<ThreatFacts.Foe> foes, double h) {
        List<ThreatFacts.Foe> m = new ArrayList<>();
        for (ThreatFacts.Foe x : foes) if (!x.ranged()) m.add(x);
        return m.isEmpty() ? 0 : fight(me, m, true, h)[0];
    }

    /** Seconds to kill one (its health / my hit, a swing each). */
    static double kill(ThreatFacts.Me me, ThreatFacts.Foe x) {
        double hit = Math.max(1, me.weaponHit());
        return Math.ceil(x.hp() / hit) * (hit <= 1 ? FIST_S : SWING_S);
    }

    /**
     * The walk home: Baritone walks a path (4.3 blocks/s on the flat) with turns, jumps and doors, so a pursuer loses
     * less ground than the straight-line speeds say: {@link ThreatFacts#WALK} x {@link #PATH_FACTOR}.
     */
    static final double PATH_FACTOR = 0.6, HOME_SPEED = ThreatFacts.WALK * PATH_FACTOR;
    /** A walker this far behind at the start loses the bot on the way; nearer ones follow for the whole walk. */
    static final double PURSUIT_RANGE = 16;

    /**
     * Round 4 fix 2: the damage the foes do over a whole walk of {@code walkT} s at {@code walkSpeed} (the way home):
     * a shooter while the bot is still in its range, a flier or a faster walker for all of it past its reach time,
     * and a slower walker for the share of the walk it keeps up (its speed over the bot's) scaled by how close it
     * starts (within {@link #PURSUIT_RANGE}). Every chaser adds up. Pure.
     */
    static double pursuit(List<ThreatFacts.Foe> foes, double walkT, double walkSpeed) {
        double d = 0;
        for (ThreatFacts.Foe x : foes) {
            if (x.ranged()) {
                double out = Math.max(0, ThreatFacts.RANGED_RANGE - x.dist()) / Math.max(0.5, walkSpeed - x.speed());
                d += x.dps() * Math.min(walkT, out);
            } else if (x.flies() || x.speed() >= walkSpeed) {
                d += x.dps() * Math.max(0, walkT - x.reachT());
            } else {
                double share = Math.min(1, x.speed() / walkSpeed) * Math.max(0, 1 - x.dist() / PURSUIT_RANGE);
                d += x.dps() * Math.max(0, walkT - x.reachT()) * share;
            }
        }
        return d;
    }

    /** The damage taken while running away for {@code runT} s: slower walkers one hit if in reach, faster ones and shooters while they keep up. */
    static double away(List<ThreatFacts.Foe> foes, double runT, double speed) {
        double d = 0;
        for (ThreatFacts.Foe x : foes) {
            if (x.ranged()) {
                double out = Math.max(0, ThreatFacts.RANGED_RANGE - x.dist()) / Math.max(0.5, speed - x.speed());
                d += x.dps() * Math.min(runT, out);
            } else if (x.flies() || x.speed() >= speed) d += x.dps() * Math.max(0, runT - x.reachT());
            else d += x.reachT() <= 0 ? x.dps() * ThreatFacts.HIT_EVERY_S : 0;
        }
        return d;
    }

    /** The chain the danger desire runs for a way. */
    static String chainOf(Priced p) {
        int wait = (int) Math.max(3, Math.min(60, Math.round(p.timeS())));
        return switch (p.way()) {
            case FIGHT -> "attack nearest then wait " + wait;
            case FLEE -> "flee then wait " + wait;
            case HOME -> "go base";
            case COVER -> "cover then wait " + wait;
            case DIG_IN -> "burrow then wait " + wait;
            case PILLAR -> "pillar then wait " + wait;
            case BOX -> "shelter";
            case BURROW -> "burrow then wait 120";
            case BASE -> "go base then shelter";
            case SLEEP -> "sleep";
            case WAIT -> "wait 60";
            case STAY -> null;
        };
    }

    // ---- AA_STEP2 1: the night ways (one danger desire; night.hide and night.shelter are gone) ----

    /** Review 2: the open-air fallback's length (s). */
    static final double OPEN_WAIT_S = 60;

    /** Review 2: the night ways other than the fallback that are possible and not held (a failure then holds only briefly when 0). */
    public static int openWays(Choice c, java.util.function.Function<String, String> held) {
        int n = 0;
        if (c != null) for (Priced p : c.all()) if (p.possible() && p.way() != Way.WAIT && p.way() != Way.STAY && (held == null || held.apply(p.way().key()) == null)) n++;
        return n;
    }

    /** As {@link #choose(ThreatFacts)} with the need and no holds. */
    public static Choice choose(ThreatFacts f, Need need) { return choose(f, need, k -> null); }

    /**
     * The foes' ways ({@link #choose(ThreatFacts)}) and, when the need scores, the night ways priced the same way (seconds:
     * the damage taken while a way is set up, from the foes near and the open night's {@code danger.nightHpS} x danger, as
     * health-seconds, plus the setup time). A way held in {@code held} (its memory key -> the hold's text, null = open) is
     * out; the others stay open. The score is the need's (or the foes' when higher); the pick is the cheapest open way.
     * With the need and no way open the score is -1 and the terms say why (never silent). Pure.
     */
    public static Choice choose(ThreatFacts f, Need need, java.util.function.Function<String, String> held) {
        Choice foes = choose(f);
        if (need == null || need.score() < 0) return foes;
        ThreatFacts.Me me = f == null || f.me() == null ? ThreatFacts.CALM.me() : f.me();
        ThreatFacts.Ways w = f == null || f.ways() == null ? ThreatFacts.Ways.NONE : f.ways();
        List<ThreatFacts.Foe> near = new ArrayList<>();
        if (f != null && f.foes() != null) for (ThreatFacts.Foe x : f.foes()) if (!ThreatRules.creeper(x.kind())) near.add(x);
        double amb = Tuning.get("danger.nightHpS") * Math.max(0, need.danger());
        List<Priced> all = new ArrayList<>();
        if (need.sheltered()) all.add(new Priced(Way.WAIT, true, 0, 0, 0, "in my shell"));
        else {
            int box = (int) Tuning.get("danger.boxBlocks");
            double bt = Tuning.get("danger.boxS");
            all.add(priced(me, Way.BOX, need.shellBlocks() >= box, during(near, bt) + amb * bt, bt,
                    need.shellBlocks() >= box ? box + " blocks (" + need.shellBlocks() + " in the bag)" : "needs " + box + " blocks, " + need.shellBlocks() + " in the bag"));
            double ut = Tuning.get("danger.burrowS");
            all.add(priced(me, Way.BURROW, w.burrow(), during(near, ut) + amb * ut, ut, w.burrow() ? "dig down and seal" : "no diggable floor"));
            all.add(priced(me, Way.DIG_IN, w.niche(), during(near, DIG_S) + amb * DIG_S, DIG_S, w.niche() ? "a niche in the wall" : "no wall to dig into"));
            boolean pil = w.onGround() && w.pillarBlocks() >= PILLAR_UP && w.headRoom() >= PILLAR_UP;
            double open = Tuning.get("danger.openS");
            all.add(priced(me, Way.PILLAR, pil, during(near, PILLAR_S) + amb * open, PILLAR_S,
                    pil ? "pillar " + PILLAR_UP + " up, open to arrows " + Math.round(open) + " s" : "no pillar (" + w.pillarBlocks() + " blocks, head room " + w.headRoom() + ")"));
            boolean home = need.base() && w.baseDist() > 0;
            double ht = home ? w.baseDist() / HOME_SPEED : 0;
            all.add(priced(me, Way.BASE, home, home ? pursuit(near, ht, HOME_SPEED) + amb * ht : 0, ht,
                    home ? "flee home " + Math.round(w.baseDist()) + " (" + near.size() + " on the way)" : need.base() ? "the base out of reach" : "no base"));
            double st = Tuning.get("danger.bedS");
            all.add(priced(me, Way.SLEEP, need.bed(), during(near, st) + amb * st, st, need.bed() ? "a bed known" : "no bed known"));
            // review 2: the always-possible fallback with its real cost: stand here in the open for a minute (never held)
            all.add(priced(me, Way.WAIT, true, during(near, OPEN_WAIT_S) + amb * OPEN_WAIT_S, 0, "wait here in the open " + Math.round(OPEN_WAIT_S) + " s"));
        }
        Priced best = null;
        List<String> holds = new ArrayList<>();
        for (Priced p : all) {
            String h = held == null || p.way() == Way.WAIT ? null : held.apply(p.way().key());       // WAIT is never held
            if (h != null) { holds.add(p.way().key() + " held: " + h); continue; }
            if (p.possible() && (best == null || p.costS() < best.costS())) best = p;
        }
        String night = nightTerms(all, held) + (holds.isEmpty() ? "" : "; " + String.join(", ", holds));
        if (foes.score() > need.score() && foes.best() != null && foes.best().way() != Way.STAY)
            return new Choice(foes.stay(), foes.best(), foes.all(), foes.savedS(), foes.score(), foes.terms() + " | night: " + night + " -> the foes first");
        if (best == null) return new Choice(null, null, all, 0, -1, need.why() + ": " + night + " -> no way open");
        return new Choice(null, best, all, 0, need.score(), need.why() + ": " + night + " -> " + best.way().key()
                + (foes.stay() != null ? " | foes: " + foes.terms() : ""));
    }

    /** The damage the foes near deal while a way takes {@code t} s (each from when it reaches me). */
    static double during(List<ThreatFacts.Foe> foes, double t) {
        double d = 0;
        for (ThreatFacts.Foe x : foes) d += x.dps() * Math.max(0, t - x.reachT());
        return d;
    }

    /** "box 40s (12 blocks), burrow 25s (dig down and seal), flee home - (no base)" as aa why shows them. */
    static String nightTerms(List<Priced> all, java.util.function.Function<String, String> held) {
        StringBuilder b = new StringBuilder();
        for (Priced p : all) {
            if (b.length() > 0) b.append(" vs ");
            b.append(p.way() == Way.BASE ? "flee home" : p.way().key()).append(' ');
            if (!p.possible()) b.append("- (").append(p.why()).append(')');
            else b.append(Math.round(p.costS())).append(" s (").append(p.why()).append(", ").append(fmt(p.damage())).append(" hp)");
        }
        return b.toString();
    }

    static String terms(List<Priced> all, Priced best, double saved, double score, int creepers) {
        StringBuilder b = new StringBuilder();
        for (Priced p : all) {
            if (b.length() > 0) b.append(", ");
            b.append(p.way().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ');
            if (!p.possible()) b.append("- (").append(p.why()).append(')');
            else b.append(Math.round(p.costS())).append("s (").append(fmt(p.damage())).append(" hp, ").append(fmt(p.timeS())).append(" s)");
        }
        String pick = best.way() == Way.STAY ? "stay (the hit response)" : score < 0 ? best.way().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " saves only " + Math.round(saved) + "s: stay" : best.way().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " saves " + Math.round(saved) + "s -> " + Math.round(score);
        return b + " -> " + pick + (creepers > 0 ? "; " + creepers + " creeper(s) left to the reflex" : "");
    }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
