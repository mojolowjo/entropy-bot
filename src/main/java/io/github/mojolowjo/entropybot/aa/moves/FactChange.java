package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z30 (owner 2026-10-23: a job whose fact changed re-plans and continues): the fact a failing step relied on and found
 * changed - a station forgotten or gone, a known source gone, a needed item missing from the bag at the step's start. The
 * code that finds the change says it here with the fact's key and words; the step hands hand it to {@link Steps} with the
 * step's failure ({@link Steps.Hands#lastFact}), which re-plans the order instead of ending it. One slot, client thread
 * only; cleared at each step start, taken once. Typed: no failure text is ever matched for it. Pure.
 */
public final class FactChange {
    /** {@code key}: the fact's kind and subject ("station minecraft:crafting_table"); {@code text}: the chat words ("table at 4 -60 -104 gone"). */
    public record Fact(String key, String text) {
        /** 0.39.z41: a new price, not a failure: a walk's path became known (the order is priced again, nothing is charged). */
        public boolean price() { return key.startsWith(PRICE); }
    }

    /** 0.39.z41: the key of {@link #priced}. */
    public static final String PRICE = "price ";

    /** 0.39.z41: a walk's complete path landed ({@code text}: what landed); the running order is priced again at its next step. */
    public static Fact priced(String text) { return new Fact(PRICE + "walk", text); }

    /**
     * 0.39.z75 metric fix B: an explore's missing source became known ({@code text}: what was seen) - the order is priced
     * again where the explore runs, and the explore ends only when that price picks other steps (the planner's own test of
     * the source, Steps.reprice(true)); nothing is charged.
     */
    public static Fact found(String text) { return new Fact(PRICE + "source", text); }

    /** FH fix: the fact is a found source's ({@link #found}). */
    public static boolean isFound(Fact f) { return f != null && (PRICE + "source").equals(f.key()); }

    /** 0.39.z77 seen: the key start of {@link #seen} ("price seen iron at 3 40 0"). */
    public static final String SEEN = PRICE + "seen ";

    /**
     * 0.39.z77 seen (owner 2026-10-26 ~17:05; runs BZ, CC: 24 veins near under-ground steps, 12 passed by, none priced within
     * 90 s - CC 16:39:47 iron seen 2.2 blocks from a walk, the walk ended 39 blocks away): a source of a family the running
     * order's steps need was seen near the bot (Seen.saw, or the exposed-face patch with fair play off) - the order is priced
     * again where the running step runs (Steps.reprice(true)): "collect that vein now" against what is left of the running
     * pick, the cheaper runs (Goals.reprice: replan.switchMarginS). A price, nothing is charged. {@code text}: the chat words.
     */
    public static Fact seen(String family, int x, int y, int z, String text) { return new Fact(SEEN + family + " at " + x + " " + y + " " + z, text); }

    /** 0.39.z77 seen: the fact is a seen source's ({@link #seen}). */
    public static boolean isSeen(Fact f) { return f != null && f.key() != null && f.key().startsWith(SEEN); }

    /** 0.39.z77 seen: the seen cell of a {@link #seen} fact (x y z), null: none or not one. */
    public static int[] seenCell(Fact f) {
        if (!isSeen(f)) return null;
        int at = f.key().lastIndexOf(" at ");
        if (at < 0) return null;
        String[] p = f.key().substring(at + " at ".length()).trim().split(" ");
        if (p.length != 3) return null;            // structural: x y z
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            io.github.mojolowjo.entropybot.Quiet.warn("FactChange.seenCell: no cell in " + f.key(), e);
            return null;
        }
    }

    /** 0.39.z77 seen: the seen fact's family ("iron"), null: not one. */
    public static String seenFamily(Fact f) {
        if (!isSeen(f)) return null;
        int at = f.key().lastIndexOf(" at ");
        return at < SEEN.length() ? null : f.key().substring(SEEN.length(), at);
    }

    /**
     * 0.39.z77 seen (CC 16:41:51: the vein dropped as "walk: path not known yet (computing)"): the start of a seen re-price's
     * answer while the walk to the seen source is not priced yet - Steps keeps the seen fact and asks it again on the next
     * landed path (one landing, one ask), until the answer is a price.
     */
    public static final String SEEN_WAITS = "the seen source waits on its walk's price: ";

    /**
     * FK fix review (rule 23): the stall verdicts' words, written by their one writer each and read by {@link #isStall} -
     * the brain's fast watch ("stuck N s", Brain), the step monitor's ("no new best for N s" / "no progress N s: ...",
     * StepMonitor), the run's outer watch ("no progress N s", RunJob). A reworded writer changes the constant, never the match.
     */
    public static final String STALL_STUCK = "stuck ", STALL_NO_NEW_BEST = "no new best", STALL_NO_PROGRESS = "no progress";

    /** FK fix: {@code text} is a stall verdict's (one of the three writers' words), not an owner's stop or a run's end. */
    public static boolean isStall(String text) {
        if (text == null) return false;
        return text.contains(STALL_STUCK) || text.contains(STALL_NO_NEW_BEST) || text.contains(STALL_NO_PROGRESS);
    }

    private static Fact last;

    private FactChange() {}

    /** A station of the recipe kind the step needed is forgotten or gone ({@code at}: where it was, null: none known anywhere). */
    public static void station(String kind, String name, String at) {
        last = new Fact("station " + kind, name + (at == null ? ": none known" : " at " + at + " gone"));
    }

    /**
     * 0.39.z48 (run I 03:19:34, S62): the fork's use of the known station ended failed from where it stood ("no face visible
     * from ..." after its second try from another side): the station slot; the order re-plans and the chain prices the next
     * known station or making a new one (z33's far-station rule), this one refused from now on.
     */
    public static void stationUnusable(String kind, String name, String at, String why) {
        last = new Fact("station " + kind, name + " at " + at + " unusable: " + why);
    }

    /**
     * 0.39.z65 review 1 (Opus Design): the chain's priced walk to the known station at {@code at} ("x y z") failed (or did not
     * start): a source fact keyed {@link io.github.mojolowjo.entropybot.aa.NeedChain#stationKey}; 0.39.z78 J5 slice 1: held
     * in the one station store (Goals.replan -> aa.brain.StationHolds.walkFailed: for every order, named by this one, back
     * once the bot stands more than failures.moved + the band away from where the walk failed), the order re-plans with the station out (the next known one or
     * a new one placed here). A walk not needed (the station in reach) ends done and holds nothing (StepHands.toStation).
     */
    public static void stationWalk(String name, int x, int y, int z, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.stationKey(x, y, z),
                name + " at " + x + " " + y + " " + z + " out for this order: " + why);
    }

    /**
     * 0.39.z68: the explore's walk up to the surface cell x y z failed (or did not start): a source fact keyed
     * {@link io.github.mojolowjo.entropybot.aa.NeedChain#surfaceKey}, held for this order; the order re-plans and the explore
     * runs from where the bot is (the chain prices no walk up while one is held).
     */
    public static void surface(int x, int y, int z, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.surfaceKey(x, y, z),
                "the walk up to the surface at " + x + " " + y + " " + z + " out for this order: " + why);
    }

    /**
     * 0.39.z75 take (run AI 20:06:10: "take crafting_table at -34 70 -55: cluster 1 block: picked up 0 of 1" stopped the
     * order with no fact): a station take that ended without the station in the bag - the source slot keyed by the take's
     * own key ({@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey}: "take at x y z"); the order re-plans without
     * that take (the walk to it, another station or a new one made and placed).
     */
    public static void take(int x, int y, int z, String why) {
        last = new Fact("source take at " + x + " " + y + " " + z, why);
    }

    /**
     * 0.39.z76 send-back 2 (run AP 00:07-00:17): a collect whose mine walked away from the spot the chain priced - that spot's
     * near-collect fact ({@link io.github.mojolowjo.entropybot.aa.NeedChain#nearKey}): the order prices the item's spots
     * within failures.moved of it out and re-plans.
     */
    public static void nearCollect(String item, int[] at, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.nearKey(item, at), why);
    }

    /**
     * 0.39.z76 send-back 2 (rule 7; run AN 23:30:07: an NPE inside the explore ended the step "error: ..." with no fact, so
     * the chestplate order stopped): a throw inside a step is that step's fact - keyed by the mod's frame it came from
     * ("threw at Mining.exploreStep0:1228"), its text the exception and the frame - and the order re-plans on it. 0.39.z76 sb4
     * review 2: no count bounds it; the same step throwing at the same frame with the same needs in the bag again in this
     * order - from any cell, other tries between - parks (Steps' order tries), as does the same try twice from one cell.
     */
    public static Fact threwFact(Throwable e) {
        String w = io.github.mojolowjo.entropybot.Quiet.where(e);
        return new Fact("threw at " + w, "a throw inside the step: " + e + " at " + w);
    }

    /**
     * 0.39.z77 J2 E2 (runs AX 14:23, BC 06:24:02): a chain explore under ground found no target a player would walk to from
     * there (no cave cell in the band; a never-seen chunk would be a goal through rock): the explore from here is held by
     * position ({@link io.github.mojolowjo.entropybot.aa.NeedChain#exploreFrozenKey}); the order re-plans (the walk up first).
     */
    /**
     * 0.39.z78 J6b run EH (2): a no-break explore found no path from where it stands - "boxed in at x y z", a source fact
     * the next plan prices (its walk up first, NeedChain.boxedHere), never a hold the bot cannot lift.
     */
    public static void boxedIn(int[] feet, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.boxedInKey(feet), why);
    }

    public static void exploreHere(int[] feet, String why) {
        if (feet == null || feet.length < 3) { last = new Fact("explore under ground", why); return; }     // structural: x y z
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.exploreFrozenKey(feet), why);
    }

    /**
     * 0.39.z77 answers (2): the climb step (the climb verb's way up) ended short - a source fact keyed
     * {@link io.github.mojolowjo.entropybot.aa.NeedChain#CLIMB_KEY}, held for the order: the re-plan then has no way up and the
     * order stops with both reasons (the walk up's and this one), never an explore through rock.
     */
    public static void climb(String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY, "the climb up failed: " + why);
    }

    /** 0.39.z76 send-back 2: a hand that caught a throw inside the running step says it ({@link #threwFact}), taken once. */
    public static void threw(Throwable e) { last = threwFact(e); }

    /**
     * 0.39.z76 send-back 3 (rule 22): the standing step stood its first-answer wait with the walks not known - the order is
     * priced again where the bot stands (a price fact, no cap counted), the walks still not known taken by Baritone's estimate.
     */
    public static void repriceAfterStand(String why) { last = priced(why); }

    /** A known source the step went for is gone. */
    public static void source(String block, String at) {
        last = new Fact("source " + block + " " + at, block.replaceFirst("^minecraft:", "") + " at " + at + " gone");
    }

    /**
     * 0.39.z33 (S36): the step's source could not be reached: Baritone's process ended with none of it ({@code why}: its own
     * words, or that it said none). The same slot as {@link #source}: the order re-plans without that source.
     */
    public static void unreachable(String block, String at, String why) {
        last = new Fact("source " + block + " " + at, unreachableText(why));
    }

    /**
     * 0.39.z66 item 2 (run X 08:41:01): the step's blocks were reached and broken but dropped other items than the step
     * counts ({@code why}: the counts). The same source slot as {@link #unreachable}: the order re-plans without that
     * source, in the true words.
     */
    public static void otherDrops(String block, String at, String why) {
        last = new Fact("source " + block + " " + at, why);
    }

    /** Baritone's last chat line since a process started (its count then and now), or that it said none: never guessed. */
    public static String baritoneSaid(long linesAtStart, long linesNow, String lastLine) {
        if (linesNow <= linesAtStart || lastLine == null || lastLine.isBlank()) return "Baritone said nothing since the mine started";
        String l = lastLine.strip();
        return l.length() > 160 ? l.substring(0, 157) + "..." : l;  // structural: one chat line's length in the step text
    }

    /** 0.39.z42: the source key of a band ("to y 5..31"): the explore way that goes to that height first. */
    public static String bandKey(int minY, int maxY) { return "to y " + minY + ".." + maxY; }

    /**
     * 0.39.z42 (run C 20:27:06): Baritone's goal ended outside the band (no path into it from here): the same source slot;
     * the order re-plans without that band (the chain takes the next way or explores overland).
     */
    public static void band(int minY, int maxY, String why) {
        last = new Fact("source " + bandKey(minY, maxY), why);
    }

    /** 0.39.z78 J6c run EU (2): a typed fact the hands found (UseEnds), the step's own fact. */
    public static void set(Fact f) { if (f != null) last = f; }

    /** 0.39.z78 J6b item 14: the prefix of {@link #bandNotKnown}'s key - never a source, never a world fact. */
    public static final String NOT_KNOWN = "not known ";

    /**
     * 0.39.z78 J6b queue item 14 (rule 15): the band leg's path is NOT KNOWN (a budget stop, no estimate, a floorless start):
     * the order re-plans on the facts of now; nothing is held for the order nor learned as a world fact (no "source " key).
     */
    public static void bandNotKnown(int minY, int maxY, String why) {
        last = new Fact(NOT_KNOWN + bandKey(minY, maxY), why);
    }

    /**
     * 0.39.z46: a strip way ended short (the strip's own end - "no path", lava, no pickaxe - or no path down to its band):
     * the order re-plans without it. 0.39.z78 strip: held by the strip's HEADING at its Y (NeedChain.stripHold of {@code key},
     * Step.Strip#key; a fresh start's from that start), the one strip key the strip ways and the playbook's strip read.
     */
    public static void strip(String key, String forkEnd, String why) {
        // strip re-review 3: this end's fact only - never an earlier one left in last
        last = null;
        // strip re-review 1 / 2 (which end holds what, StripEnd: the fork's documented ends, matched whole or by prefix): water /
        // lava ahead and no path hold the heading at its Y; no pickaxe left and bag full are a bag fact (nothing held, the order
        // re-plans on its bag, the same try with the same bag parks: Steps' order tries); stopped (the owner, a monitor, a
        // reflex), lost control and done hold nothing; an end not in the list holds nothing and is said as unmapped (rule 7)
        StripEnd e = StripEnd.of(forkEnd);
        if (e == null) {
            io.github.mojolowjo.entropybot.Quiet.line("strip at " + key + ": the fork's end \"" + forkEnd + "\" is not one IStripProcess lists: nothing held (unmapped; " + why + ")");
            return;
        }
        switch (e.hold) {
            case BAG -> last = new Fact(BAG_STRIP, "strip at " + key + ": " + why + " (the bag's: no heading held; re-planned on the bag)");
            case HEADING -> heading(key, why);
            case NONE -> { }
        }
    }

    /**
     * strip re-review 1: the strip's own yield end (StripYield: the tunnel is spent - its ore below the band's, or another
     * way cheaper than going on here): the heading at its Y is held.
     */
    public static void stripSpent(String key, String why) {
        last = null;
        heading(key, why);
    }

    /** The heading hold of the strip {@code key}; a malformed key holds nothing and is said loud (strip review 1, rule 7). */
    private static void heading(String key, String why) {
        // 0.39.z78 strip (run DS 15:14:33): held by the strip's heading at its Y (NeedChain.stripHold), the key the strip ways
        // and the playbook's strip ask - never the joined ore key no reader asked
        String hold = io.github.mojolowjo.entropybot.aa.NeedChain.stripHold(key);
        if (hold == null) {
            io.github.mojolowjo.entropybot.Quiet.line("strip: not a strip key \"" + key + "\" - no hold written for: " + why);
            return;
        }
        last = new Fact("source " + hold, "strip at " + key + ": " + why);
    }

    /** strip review 2: the key of a strip's bag-caused end (no source: nothing held, the bag decides). */
    public static final String BAG_STRIP = "bag strip";

    /**
     * 0.39.z62 item 1b (run S 04:57:30-35): a strip that ended with no new block in its heading - blocked at its start (a
     * station Baritone will not break, a refused cell) - is a fact about that HEADING at that Y, not about the ores: the
     * source slot keyed by {@link io.github.mojolowjo.entropybot.aa.NeedChain#headingKey}; the order re-plans and every strip
     * way at that Y takes its next heading.
     */
    public static void stripBlocked(int y, String heading, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.headingKey(y, heading),
                "strip " + heading + " at feet y " + y + " blocked at its start: " + why);
    }

    /**
     * 0.39.z77 strip (runs CK, CF, CJ: strips dug on to the 600 s cap): a strip that ended on its own yield check because a
     * tunnel in another heading, or a strip from fresh ground, or (a strip of several families) any other way prices cheaper
     * than going on here is a fact about this HEADING at that Y, as {@link #stripBlocked} is: the order re-plans and every strip
     * way at that Y takes its next heading (a strip from fresh ground keeps its own key).
     */
    public static void stripYield(int y, String heading, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.headingKey(y, heading),
                "strip " + heading + " at feet y " + y + " ended on its yield: " + why);
    }

    /**
     * 0.39.z77 explore (run DC 01:37:05: "strip: no path (strip east feet y 16: 21 blocks, 0 clusters mined)" held the way's
     * own key "strip ores|16|east"; NeedChain.World#withoutSources then dropped the whole strip way, north, south and west
     * never offered, and the order climbed 105 s and explored 5 min on the surface): a strip from the feet whose fork ended
     * "no path" INSIDE its tunnel ({@code cells} dug) is a fact about this HEADING at that Y, as {@link #stripBlocked} and
     * {@link #stripYield} are: the order re-plans and every strip way at that Y takes its next heading (rule 1: the other
     * headings are options with their prices; the climb and the surface explore come after them by price). A strip from
     * fresh ground ({@code s.from()} set) keeps its own key ({@link #strip}), as before. Pure.
     */
    public static void stripNoPath(Step.Strip s, int cells, String why) {
        if (s.from() != null) { strip(s.key(), StripEnd.NO_PATH.reason, why); return; }
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.headingKey(s.y(), s.heading()),
                "strip " + s.heading() + " at feet y " + s.y() + " found no path after " + cells + " cells: " + why);
    }

    /** 0.39.z62: the source key a fact names itself (a blocked strip heading), null: the failed step's key applies. */
    public static String ownSourceKey(Fact f) {
        if (f != null && f.key().startsWith(WALK_TO)) return f.key().substring(WALK_TO.length());     // 0.39.z65 item 1
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.PLACE_AT)) return f.key().substring("source ".length());   // 0.39.z67 item 1
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.NEAR)) return f.key().substring("source ".length());       // z75 review 2
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.EXPLORE_FROZEN)) return f.key().substring("source ".length());   // z76 send-back 2
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.BOXED_IN)) return f.key().substring("source ".length());   // J6b run EH (2)
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.CAVE_KEY)) return f.key().substring("source ".length());   // run EY: the lip's opening
        String p = "source " + io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING;
        return f != null && f.key().startsWith(p) ? f.key().substring("source ".length()) : null;
    }

    /** 0.39.z65: the key prefix of {@link #walkFor}. */
    static final String WALK_TO = "source walk to ";

    /**
     * 0.39.z65 item 1 (run V 07:15:22: "goto -261 75 21 near 2: couldn't get there (walk: Baritone stopped short)" stopped
     * the iron order - a walk's failure said no fact, so the run ended and the order parked): a walk that failed on its way
     * to the next step's source ({@code sourceKey}: that step's {@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey},
     * e.g. "take at x y z") is that source's fact - unreachable from here, the order re-plans without it (rule 7).
     */
    public static Fact walkFor(String sourceKey, String why) {
        return new Fact(WALK_TO + sourceKey, "the walk to " + sourceKey + " failed: " + why);
    }

    /** 0.39.z77 walk: the key prefix of {@link #walkShortFact}. */
    public static final String WALK_SHORT = "walk short to ";

    /**
     * 0.39.z77 walk (run BT 13:14:32, rule 7: the leggings order stopped on "goto 20 21 -10: couldn't get there (22 blocks
     * away, stopped at 28 21 11)"): a walk step to {@code target} that Baritone ended short at {@code feet} - the fact "walk
     * to x y z ended short at fx fy fz: Baritone's reason", keyed by the target and the feet it ended at ("walk short to x y z
     * at fx fy fz"). Not a hold: the order re-plans from the feet of now (a stand cell beside the target, another station,
     * the next leg - the planner's prices from here). The same key again in the order (the same target short at the same
     * feet) is the hold {@link #walkHeld} (Steps.failOrReplan). Pure.
     */
    public static Fact walkShortFact(int[] target, int[] feet, String why) {
        String t = cell(target), f = cell(feet);
        String w = why == null ? "no reason" : why.replaceFirst("^error: ", "");
        return new Fact(WALK_SHORT + t + " at " + f, "walk to " + t + " ended short at " + f + ": " + w);
    }

    /** 0.39.z77 walk: the hands say {@link #walkShortFact} (taken once, as every fact here). */
    public static void walkShort(int[] target, int[] feet, String why) { last = walkShortFact(target, feet, why); }

    /**
     * 0.39.z78 run EY (rule 23 (1); 13:30:45-13:32:54: the lip walk to the cave at 62 94 -354 ended "no standable cell within
     * reach and sight of the entry", a walk-short fact of the running order only, and every one of five orders ran the same
     * lip twice and parked): the lip walk's end - the reach goal empty or Baritone's no progress - is a SOURCE fact keyed by
     * the opening itself ({@link io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay#keyOf}: the one key the cave way filters
     * its openings by), so the order holds it, the world fact holds it for every order (Goals.replan), and the next opening or
     * the next cave is priced. The world fact lifts by DISTANCE only (failures.moved + WorldFacts.MARGIN from where it was
     * learned), as the climb and place facts do - not by a change at the entry (review of 795aa35a: said, not built). Pure.
     */
    public static Fact lipFailedFact(int[] entry, int[] feet, String why) {
        String w = why == null ? "no reason" : why.replaceFirst("^error: ", "");
        return new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay.keyOf(entry),
                "the walk to the cave's lip at " + cell(entry) + " failed at " + cell(feet) + ": " + w);
    }

    /** Run EY: the lip step's hands say {@link #lipFailedFact} (taken once). */
    public static void lipFailed(int[] entry, int[] feet, String why) { last = lipFailedFact(entry, feet, why); }

    /**
     * GA fix (runs FN 20:23:41, FZ 01:30:05, GA 02:16:00-02:26:25: "order iron_pickaxe stopped: ... cave: the caving step at 181 61 0
     * ended - NO_BRANCH" - five orders of one run stopped on a cave's end, no fact, so the run ended FAILED instead of
     * re-planning): the caving step's end with the need unmet - the cave explored to its end (DONE), no reachable branch
     * (NO_BRANCH), no progress - is a SOURCE fact keyed by the cave itself ({@link io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay#keyOf},
     * as {@link #lipFailedFact}): the order holds that cave and prices the next cave, a strip or an explore. {@code end}: the
     * end's name, {@code why}: the walk's own words. Pure.
     */
    public static Fact caveEndedFact(int[] entry, String end, String why) {
        String w = why == null ? "no reason" : why.replaceFirst("^error: ", "");
        return new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay.keyOf(entry),
                "the caving step at " + cell(entry) + " ended - " + end + " (" + w + "): the cave is out for this order");
    }

    /** GA fix: the caving step's hands say {@link #caveEndedFact} (taken once). */
    public static void caveEnded(int[] entry, String end, String why) { last = caveEndedFact(entry, end, why); }

    /** The caving step ran its minutes with the need unmet: {@link #caveMinutesFact} (taken once). */
    public static void caveMinutes(int[] entry, int mined, int passagesLeft, String why) { last = caveMinutesFact(entry, mined, passagesLeft, why); }

    /**
     * D15 (runs JZ / JAE: a MINUTES end was a price fact; a failed step's price fact re-priced against the running pick,
     * "kept the running pick" with no steps, and the whole order stopped): the budget end is the cave's SOURCE fact - the
     * cave is out for this order, the order is re-planned on the sources left (another cave, a strip, an explore), and it
     * stops only when the planner has none. Words: "cave at x y z: budget spent, N ore mined, M passages left (why)". Pure.
     */
    public static Fact caveMinutesFact(int[] entry, int mined, int passagesLeft, String why) {
        return new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay.keyOf(entry),
                "cave at " + cell(entry) + ": budget spent, " + mined + " ore mined, " + (passagesLeft < 0 ? "not measured" : String.valueOf(passagesLeft))
                        + " passages left" + (why == null || why.isBlank() ? "" : " (" + why.replaceFirst("^error: ", "") + ")"));
    }

    /** 0.39.z77 walk: {@code f} is a walk that ended short ({@link #walkShortFact}). */
    public static boolean isWalkShort(Fact f) { return f != null && f.key().startsWith(WALK_SHORT); }

    /**
     * 0.39.z77 walk: the same target ended short again at the same feet in this order - the source fact keyed
     * {@link io.github.mojolowjo.entropybot.aa.NeedChain#walkTargetKey}: held for the order (Goals' per-position hold: back
     * once the bot stands failures.moved closer to it than where it failed), every way whose walk goes there is priced out
     * with this reason (NeedChain.World#withoutSources) and the next way runs.
     */
    public static Fact walkHeld(int[] target, Fact shortFact) {
        return new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.walkTargetKey(target[0], target[1], target[2]),
                (shortFact == null ? "walk to " + cell(target) + " ended short" : shortFact.text()) + " (twice from there: the target held for this order)");
    }

    private static String cell(int[] c) {
        return c == null || c.length < 3 ? "?" : c[0] + " " + c[1] + " " + c[2];     // structural: x y z
    }

    /**
     * 0.39.z46 (run G 23:40:12): a step made no progress (no move, no step change) for the watch's time: the source slot
     * keyed by the step's target ({@code key}); the order re-plans instead of parking.
     */
    public static Fact noProgress(String key, String text) { return new Fact("source " + key, text); }

    /** The reason an unreachable source is dropped with ("unreachable (Baritone: Unable to find any path to ...)"). */
    public static String unreachableText(String why) { return "unreachable (Baritone: " + why + ")"; }

    /**
     * 0.39.z60 item 3 (run R 03:52:52: "place crafting_table near me: no cell within 3 blocks" stopped the iron order with no
     * re-plan): the fork's placeNear found no cell for {@code item} here (none free, none to clear) or its leg failed: the
     * "place" slot keyed by the item; the order re-plans on the facts of now (the same step failing again ends the run with
     * both reasons, Steps).
     */
    public static void placeFailed(String item, String why) {
        last = new Fact("place " + item, "place " + item.replaceFirst("^minecraft:", "") + " near me failed here: " + why);
    }

    /**
     * 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16: the re-plan offered the same "place near me" at the same sea-level spot and
     * the order stopped): the failure is a fact about WHERE it failed ({@code at}: the feet then; null: not known, the
     * plain fact above): a source slot keyed {@link io.github.mojolowjo.entropybot.aa.NeedChain#placeKey}, held for the
     * order; the re-plan places near a cell with room from facts instead, by its priced walk (NeedChain.roomWalk).
     */
    public static void placeFailed(String item, String why, int[] at) {
        if (at == null || at.length < 3) { placeFailed(item, why); return; }     // structural: x y z
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.placeKey(at[0], at[1], at[2]),
                "place " + item.replaceFirst("^minecraft:", "") + " near me failed at " + at[0] + " " + at[1] + " " + at[2] + ": " + why);
    }

    /** An item the step needs is not in the bag (nor in storage) when it starts. */
    public static void bag(String item, String why) {
        last = new Fact("bag " + item, item.replaceFirst("^minecraft:", "") + " not in the bag (" + why + ")");
    }

    /**
     * 0.39.z36 (S41): a craft ran out of one recipe ingredient mid-step ({@code alts}: every item the recipe's Ingredient
     * accepts in that cell, as the game expanded it): the "bag" slot keyed by that ingredient as a need word (several items
     * joined, {@link io.github.mojolowjo.entropybot.aa.NeedChain#word}); the order re-plans on the bag of now.
     */
    public static void ranOut(java.util.List<String> alts, String why) {
        if (alts == null || alts.isEmpty()) return;
        java.util.List<String> sh = new java.util.ArrayList<>();
        for (String a : alts) sh.add(a.replaceFirst("^minecraft:", ""));
        last = new Fact("bag " + io.github.mojolowjo.entropybot.aa.NeedChain.word(String.join("|", alts)),
                "none of " + String.join(", ", sh) + " left in the bag (" + why + ")");
    }

    /**
     * 0.39.z34 (S39): the step's declared need ({@link StepNeeds}) is short in the bag - its tool gone, an item it uses up
     * short. The same "bag" slot as {@link #bag}; {@code what}: the missing need ("pickaxe tier>=1"), {@code text}: the words.
     */
    public static void bagNeed(String what, String text) {
        last = new Fact("bag " + what, text);
    }

    /** The fact said since the last {@link #clear}, taken once; null: none. */
    public static Fact take() {
        Fact f = last;
        last = null;
        return f;
    }

    public static void clear() { last = null; }
}
