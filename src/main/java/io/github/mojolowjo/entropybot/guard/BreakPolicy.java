package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.Locale;

/**
 * Mining plan 3.1 (docs/MINING_PLAN.md, owner 2026-10-18): the ONE authority for "may this cell be broken or placed,
 * and at what cost", for every layer on both threads. Pure Java: the caller supplies what it knows about the block
 * ({@link BlockFacts}, from {@code Level} on the client thread or from Baritone's {@code BlockStateInterface} on the
 * pathfinder thread) and about itself ({@link Where}); the answer is deterministic from an immutable {@link Snapshot}.
 *
 * <p>Order (first deny wins, multipliers multiply): the <b>floor</b> (never configurable), the <b>rule set</b> of the
 * area at the cell (3.2; presets per {@link AreaType} until phase 3 puts a rule set on every box), the <b>intent
 * model</b> (3.3). Leases stay the per-job permission outside keep-out and no-break for MINE/DIG_OUT and are checked by
 * {@link GuardCore}, not here. Unknown facts fail closed.
 *
 * <p>Phase 0 (shadow) computes this next to today's decisions; phase 1 makes the TRAVEL column live.
 */
public final class BreakPolicy {
    private BreakPolicy() {}

    /** Why a block would be broken or placed. {@code NONE} = walking only (a route-map context): nothing at all. */
    public enum Intent {
        NONE, TRAVEL, MINE, DIG_OUT, BUILD, RESTORE;

        public static Intent of(String w) {
            if (w == null) return null;
            String s = w.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (s.equals("DIGOUT") || s.equals("DIG")) s = "DIG_OUT";
            for (Intent i : values()) if (i.name().equals(s)) return i;
            return null;
        }
    }

    public enum Action { BREAK, PLACE }

    public enum Fluid { NONE, WATER, LAVA }

    /** What travel and mining may do to natural blocks there. */
    public enum Natural { NEVER, TRAVEL_EXPENSIVE, LENIENT }

    public enum PlaceRule { NEVER, TORCHES, LENIENT }

    /** The numbers of the cost table (3.3). Constants here, nowhere else. */
    public static final class Costs {
        private Costs() {}
        /** Default travel multiplier (about 15 blocks of walking per broken stone block). */
        public static double travel() { return Tuning.get("breakPolicy.travel"); }
        /** The camp and a multiplayer world's outside: travel digs only when the detour is long. */
        public static double travelExpensive() { return Tuning.get("breakPolicy.travelExpensive"); }
        /** TRAVEL: a break next to water on top (flow risk). */
        public static double travelWaterSide() { return Tuning.get("breakPolicy.travelWaterSide"); }
        /** MINE: an ore is cheaper than the stone around it (go through the ore). */
        public static double mineOre() { return Tuning.get("breakPolicy.mineOre"); }
        /** TRAVEL placing: x3 (place cost 60 + walk). */
        public static double travelPlace() { return Tuning.get("breakPolicy.travelPlace"); }
        /**
         * Owner (aa372): placing into a cell the bot itself broke (the restore ledger, this walk's dug cells) costs
         * about nothing: it puts the world back while getting out (pillar up its own shaft, refill a staircase).
         */
        public static double refill() { return Tuning.get("breakPolicy.refill"); }
        /** Travel breaks per walk, all restored by the ledger (owner's default); the multiplier grows toward it. */
        public static int travelCapV() { return Tuning.i("breakPolicy.travelCap"); }
    }

    /** 3.2: the rules behind one box (or behind the open-world floor, where no typed area holds). */
    public record RuleSet(String name, Natural breakNatural, boolean useResources, PlaceRule place,
                          boolean restore, double travelMultiplier, double mineMultiplier) {
        // AREAS (0.39.0): the presets by meaning; keep-out shares no-break's (nothing breaks or is placed; the walk veto is
        // the guard's and the cost advisor's), storage and danger share work's (they are facts for AA, not break rules)
        public static final RuleSet NO_BREAK = new RuleSet("no-break", Natural.NEVER, false, PlaceRule.NEVER, false, Costs.travel(), 1);
        public static final RuleSet KEEP_OUT = new RuleSet("keep-out", Natural.NEVER, false, PlaceRule.NEVER, false, Costs.travel(), 1);
        public static final RuleSet BASE = new RuleSet("base", Natural.TRAVEL_EXPENSIVE, false, PlaceRule.LENIENT, true, Costs.travelExpensive(), 1);
        public static final RuleSet WORK = new RuleSet("work", Natural.LENIENT, true, PlaceRule.LENIENT, true, Costs.travel(), 1);
        public static final RuleSet STORAGE = new RuleSet("storage", Natural.LENIENT, true, PlaceRule.LENIENT, true, Costs.travel(), 1);
        public static final RuleSet DANGER = new RuleSet("danger", Natural.LENIENT, true, PlaceRule.LENIENT, true, Costs.travel(), 1);
        public static final RuleSet DESTROY = new RuleSet("destroy", Natural.LENIENT, true, PlaceRule.LENIENT, false, Costs.travel(), 1);
        /** The open world (no typed area) in a singleplayer world (owner's note 2026-10-18: follows the connection, no toggle). */
        public static final RuleSet OUTSIDE_SINGLEPLAYER = new RuleSet("outside (singleplayer, lenient)", Natural.LENIENT, true, PlaceRule.LENIENT, true, Costs.travel(), 1);      // 0.39.s8 owner: "Place anything outside"
        /** The open world (no typed area) on a server: natural ground still travelled through, but dearly; built blocks never. */
        public static final RuleSet OUTSIDE_MULTIPLAYER = new RuleSet("outside (multiplayer, expensive)", Natural.TRAVEL_EXPENSIVE, true, PlaceRule.LENIENT, true, Costs.travelExpensive(), 1);      // 0.39.s8 owner: "Place anything outside"
        /** Server presets for phase 3: walkable, torches allowed, nothing broken; and travel through dirt, no resources taken. */
        public static final RuleSet NOBREAK = new RuleSet("nobreak", Natural.NEVER, false, PlaceRule.TORCHES, false, Costs.travel(), 1);
        public static final RuleSet NORESOURCES = new RuleSet("noresources", Natural.LENIENT, false, PlaceRule.TORCHES, true, Costs.travel(), 1);

        /** The preset of an area type; {@code null} (the open world, no typed area) = the snapshot's outside rule set, not this. */
        public static RuleSet preset(AreaType t) {
            if (t == null) return null;
            return switch (t) {
                case KEEP_OUT -> KEEP_OUT;
                case NO_BREAK -> NO_BREAK;
                case BASE -> BASE;
                case WORK -> WORK;
                case STORAGE -> STORAGE;
                case DANGER -> DANGER;
                case DESTROY -> DESTROY;
            };
        }

        public String describe() {
            return name + ": natural " + breakNatural.name().toLowerCase(Locale.ROOT) + ", resources " + (useResources ? "yes" : "no")
                    + ", place " + place.name().toLowerCase(Locale.ROOT)
                    + ", restore " + (restore ? "on" : "off") + ", travel x" + fmt(travelMultiplier) + ", mine x" + fmt(mineMultiplier);
        }
    }

    /**
     * What the caller knows about the cell. {@code loaded} false = unknown: fail closed. (owner 2026-10-23: no built or block-entity floor;
     * only the owner's marked areas forbid) {@code torch} = the item being placed is a torch;
     * {@code dugByMe} = the bot itself broke this cell (the restore ledger or this job's breaks): a placement there is a refill.
     * STRUCTURE_BLOCKS (0.39.z26): {@code structure} = the block's structure fact from the game's data ({@link #structureOf});
     * {@code placedByMe} = the ledger says the bot placed the block there (asked only for a structure block).
     */
    public record BlockFacts(boolean loaded, String id, boolean ore, boolean unbreakable,
                             Fluid inCell, Fluid above, Fluid sides, Fluid below, boolean fallingAbove, boolean torch, boolean dugByMe,
                             Structure structure, boolean placedByMe) {
        // 0.39.z26 fix (3, 12): no constructor defaults the structure fact; every facts value states it
        public static final BlockFacts UNKNOWN = new BlockFacts(false, null, false, false, Fluid.NONE, Fluid.NONE, Fluid.NONE, Fluid.NONE, false, false, false, Structure.NOT_LOADED, false);
        /** Plain stone with nothing around it: stated not a structure block. */
        public static final BlockFacts STONE = new BlockFacts(true, "minecraft:stone", false, false, Fluid.NONE, Fluid.NONE, Fluid.NONE, Fluid.NONE, false, false, false, Structure.NO, false);

        public BlockFacts withAbove(Fluid f) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, f, sides, below, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withSides(Fluid f) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, f, below, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withBelow(Fluid f) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, f, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withInCell(Fluid f) { return new BlockFacts(loaded, id, ore, unbreakable, f, above, sides, below, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withOre(boolean o) { return new BlockFacts(loaded, id, o, unbreakable, inCell, above, sides, below, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withFallingAbove(boolean f) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, below, f, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withUnbreakable(boolean u) { return new BlockFacts(loaded, id, ore, u, inCell, above, sides, below, fallingAbove, torch, dugByMe, structure, placedByMe); }
        public BlockFacts withTorch(boolean t) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, below, fallingAbove, t, dugByMe, structure, placedByMe); }
        public BlockFacts withDug(boolean d) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, below, fallingAbove, torch, d, structure, placedByMe); }
        public BlockFacts withStructure(Structure s) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, below, fallingAbove, torch, dugByMe, s, placedByMe); }
        public BlockFacts withPlacedByMe(boolean m) { return new BlockFacts(loaded, id, ore, unbreakable, inCell, above, sides, below, fallingAbove, torch, dugByMe, structure, m); }
    }

    // ---- STRUCTURE_BLOCKS.md (owner 2026-10-23, RULES 16): structure blocks and area tags ----

    /** The structure fact of a block (rule 15: loaded yes, loaded no, or not loaded; not loaded is never cached as no). */
    public enum Structure { NO, YES, NOT_LOADED }

    /** The typed kind of a verdict the callers compare (0.39.z26 fix 11: never by the rule's string). */
    public enum Kind {
        OTHER("other"), STRUCTURE("structure"), STATION("station"), USE_STATIONS(AreaTags.USE_STATIONS);
        public final String word;
        Kind(String word) { this.word = word; }
    }

    /** The refusal outside every area. */
    public static final String STRUCTURE_OUTSIDE = "structure block outside my areas";

    /**
     * The structure rule over the game's facts of one block (the game side reads them: StructureTable). Order: not loaded
     * fails closed; an ore or a log is never a structure; a structure tag, a block entity or a recipe type's station is
     * one; a block with a crafting recipe is one unless world gen places it ({@code worldGen} null = the world-gen set
     * is not loaded yet: NOT_LOADED, refused and never cached as no; 0.39.z26 (2): the set comes from the client's jars).
     */
    public static Structure structureOf(boolean loaded, boolean tagged, boolean oreOrLog, boolean blockEntity, boolean station,
                                        boolean crafted, Boolean worldGen) {
        if (!loaded) return Structure.NOT_LOADED;
        if (oreOrLog) return Structure.NO;
        if (tagged || blockEntity || station) return Structure.YES;
        if (!crafted) return Structure.NO;
        if (worldGen == null) return Structure.NOT_LOADED;
        return worldGen ? Structure.NO : Structure.YES;
    }

    /**
     * The one answer to "may this structure block be broken here": the click (GuardCore's break branch), the path cost
     * (CostAdvisorHook) and AA's way prices (through {@link #judge}) all come here. Not a structure: open (the rest of the
     * policy decides). Keep-out / no-break: refused. Placed by me: open. The innermost area's tags hold break-structures:
     * open. Else refused with the reason.
     */
    public static Verdict structure(Policy p, String dim, int x, int y, int z, Structure s, boolean placedByMe) {
        if (s == null || s == Structure.NOT_LOADED) return Verdict.floor(NOT_LOADED_YET);
        if (s == Structure.NO) return Verdict.ok(1, "not a structure");
        Policy pol = p == null ? Policy.EMPTY : p;
        Box pr = pol.protectAt(dim, x, y, z);
        if (pr != null) return Verdict.floor("in " + (pr.name == null ? "box" : pr.name) + " (" + pr.type.word() + ")");
        if (placedByMe) return Verdict.ok(1, "structure, placed by me");
        Box a = pol.innermostAreaAt(dim, x, y, z);
        String an = a == null ? null : a.name == null ? "box" : a.name;
        if (a != null && a.has(AreaTags.BREAK_STRUCTURES)) return Verdict.ok(1, "structure, " + an + " " + AreaTags.BREAK_STRUCTURES);
        return Verdict.deny(a == null ? STRUCTURE_OUTSIDE : "structure block in " + an + " (no " + AreaTags.BREAK_STRUCTURES + " tag)", Kind.STRUCTURE);
    }

    /** The reason of {@link #structure}'s fail-closed answer while the cell's structure fact is not loaded (rule 15). */
    public static final String NOT_LOADED_YET = "not loaded yet";

    /**
     * 0.39.z60 review 1 (rule 15): the verdict is {@link #structure}'s "the structure fact is not loaded yet" - refused for the
     * click, but for a lookup NOT KNOWN (wait with that reason), never "no".
     */
    public static boolean structureNotLoaded(Verdict v) {
        return v != null && !v.allowed() && v.floor() && NOT_LOADED_YET.equals(v.reason());
    }

    /** A cell's structure fact and whether I placed it (the ledger, asked for a structure block only). */
    public record StructureFacts(Structure structure, boolean placedByMe) {
        public static final StructureFacts NOT_LOADED = new StructureFacts(Structure.NOT_LOADED, false);
    }

    /**
     * The pure half of the one structure-facts reader (PolicyRuntime.structureFacts): a cell not loaded (or a table not
     * built: null) is NOT_LOADED; a structure block asks the ledger of the caller's {@code dim} ({@link OwnPlaced#at}).
     */
    public static StructureFacts cellStructure(boolean loaded, Structure s, String dim, int x, int y, int z) {
        if (!loaded || s == null || s == Structure.NOT_LOADED) return StructureFacts.NOT_LOADED;
        return new StructureFacts(s, s == Structure.YES && OwnPlaced.at(dim, x, y, z));
    }

    /**
     * The path's structure ask (CostAdvisorHook, the non-travel A* hook in Guard): no snapshot refuses ("no snapshot",
     * never NaN or open); else {@link #structure} with the cell's facts, the same the click asks.
     */
    public static Verdict pathStructure(Snapshot s, String dim, int x, int y, int z, StructureFacts f) {
        if (s == null) return Verdict.floor("no snapshot");
        StructureFacts sf = f == null ? StructureFacts.NOT_LOADED : f;
        return structure(s.policy(), dim, x, y, z, sf.structure(), sf.placedByMe());
    }

    /** The verdict is a refusal of this rule (for the notes in aa why). */
    public static boolean structureRefusal(Verdict v) {
        return v != null && !v.allowed() && v.kind() == Kind.STRUCTURE;
    }

    /**
     * Using a station (STRUCTURE_BLOCKS ruling 3 and 6): keep-out refused; placed by me: usable; the innermost area holds
     * use-stations: usable and preferred ({@link #stationPreferred}); inside any of the owner's areas (no-break included:
     * using is not breaking): usable; else a wild station: refused.
     */
    public static Verdict stationUse(Policy p, String dim, int x, int y, int z, boolean placedByMe) {
        Policy pol = p == null ? Policy.EMPTY : p;
        Box ko = pol.keepOutAt(dim, x, y, z);
        if (ko != null) return Verdict.deny("in " + (ko.name == null ? "box" : ko.name) + " (keep-out)", Kind.STATION);
        Box a = pol.innermostAreaAt(dim, x, y, z);
        if (a != null && a.has(AreaTags.USE_STATIONS)) return Verdict.ok(1, AreaTags.USE_STATIONS, Kind.USE_STATIONS);
        if (placedByMe) return Verdict.ok(1, "station, placed by me", Kind.STATION);
        if (a != null || pol.protectAt(dim, x, y, z) != null) return Verdict.ok(1, "station, in my areas", Kind.STATION);
        return Verdict.deny("wild station (not placed by me, outside my areas)", Kind.STATION);
    }

    /** The station sits in a use-stations area: it ranks before every other. */
    public static boolean stationPreferred(Verdict v) {
        return v != null && v.allowed() && v.kind() == Kind.USE_STATIONS;
    }

    /**
     * The bot: in water now (the drowning rule: no digging down with water over the cell while the bot itself is in
     * the water; from dry ground a cell under a pond is a wet side, not a dive); travel breaks made on this walk so far.
     */
    public record Where(boolean inWater, int travelBreaks, int air, int waterAbove, int feetY, int pickTier) {
        public static final Where DRY = new Where(false, 0);
        public static final Where WET = new Where(true, 0);

        /** Air unknown (0): a dive is never allowed (the old rule). */
        public Where(boolean inWater, int travelBreaks) { this(inWater, travelBreaks, 0, 0, Integer.MIN_VALUE, DrownRisk.defaultTier()); }

        public Where(boolean inWater, int travelBreaks, int air, int waterAbove, int feetY) { this(inWater, travelBreaks, air, waterAbove, feetY, DrownRisk.defaultTier()); }

        /** aa-costs: the drowning risk of digging one block down from here (DrownRisk). */
        public double diveRisk() { return DrownRisk.risk(1, waterAbove, air, pickTier); }

        /** aa-costs (review): the risk of digging down to the cell at {@code y}: the whole depth from my feet counts. */
        public double diveRisk(int y) {
            int d = feetY == Integer.MIN_VALUE ? 1 : Math.max(1, feetY - y);
            return DrownRisk.risk(d, waterAbove, air, pickTier);
        }

        public Where withBreaks(int n) { return new Where(inWater, n, air, waterAbove, feetY, pickTier); }
    }

    public record Ask(Action action, Intent intent, String dim, int x, int y, int z, BlockFacts block, Where me) {}

    /**
     * {@code multiplier}: what Baritone's break (or place) cost is multiplied by when allowed (an ore under MINE is below
     * 1). {@code floor}: a deny no rule set can lift. {@code rule}: the rule that decided, for "fence why".
     */
    public record Verdict(boolean allowed, double multiplier, String reason, boolean floor, String rule, Kind kind) {
        public Verdict(boolean allowed, double multiplier, String reason, boolean floor, String rule) { this(allowed, multiplier, reason, floor, rule, Kind.OTHER); }
        static Verdict ok(double mult, String rule) { return new Verdict(true, mult, null, false, rule); }
        static Verdict ok(double mult, String rule, Kind k) { return new Verdict(true, mult, null, false, rule, k); }
        static Verdict deny(String reason, Kind k) { return new Verdict(false, Double.POSITIVE_INFINITY, reason, false, k.word, k); }
        static Verdict floor(String reason) { return new Verdict(false, Double.POSITIVE_INFINITY, reason, true, "floor"); }
        static Verdict deny(String reason, String rule) { return new Verdict(false, Double.POSITIVE_INFINITY, reason, false, rule); }

        public String text() {
            return allowed ? "allowed x" + fmt(multiplier) + " (" + rule + ")" : "denied: " + reason + (floor ? " (floor)" : " (" + rule + ")");
        }
    }

    /**
     * Everything a verdict depends on, immutable: the guard's policy (areas, protect boxes), the rule set in the open world (no typed area) (from the connection), the travel cap. Swapped whole, so the pathfinder thread reads it lock-free.
     */
    public record Snapshot(Policy policy, RuleSet outside, boolean multiplayer, int travelCap) {
        public static Snapshot of(Policy p, boolean multiplayer) {
            return new Snapshot(p == null ? Policy.EMPTY : p, multiplayer ? RuleSet.OUTSIDE_MULTIPLAYER : RuleSet.OUTSIDE_SINGLEPLAYER, multiplayer, Costs.travelCapV());
        }

        /** The rule set at a cell: a protect box (keep-out, no-break) = its preset; else the hardest area's; else the outside rule set. */
        public RuleSet ruleSetAt(String dim, int x, int y, int z) {
            Box pr = policy.protectAt(dim, x, y, z);
            if (pr != null) return RuleSet.preset(pr.type);
            AreaType t = policy.typeAt(dim, x, y, z);          // AREAS: the hardest meaning wins where boxes overlap
            return t == null ? outside : RuleSet.preset(t);
        }
    }

    /**
     * The facts-free part (what Baritone's {@code isPossiblyProtected} hook can answer on the pathfinder thread without
     * block lookups): denied dimensions, protect boxes, a rule set that never breaks, walking-only intent.
     */
    public static Verdict boxes(Snapshot s, Intent intent, String dim, int x, int y, int z) {
        if (s == null) return Verdict.deny("no rule snapshot yet", "snapshot");
        if (intent == null || intent == Intent.NONE) return Verdict.deny("walking only", "intent");
        if (GuardCore.DENIED_DIMS.contains(dim)) return Verdict.floor("no breaking in " + dim);
        Box pr = s.policy().protectAt(dim, x, y, z);
        if (pr != null) return Verdict.floor("in " + (pr.name == null ? "box" : pr.name) + " (" + pr.type.word() + ")");
        RuleSet r = s.ruleSetAt(dim, x, y, z);
        if (r.breakNatural() == Natural.NEVER && intent != Intent.RESTORE) return Verdict.deny("nothing broken or placed in " + r.name(), "area");
        return Verdict.ok(1, "boxes");
    }

    /** The whole answer. Never throws for a well-formed ask; a null snapshot or unknown facts deny. */
    public static Verdict judge(Snapshot s, Ask a) {
        if (a == null || a.block() == null || a.intent() == null) return Verdict.deny("policy error: empty ask", "ask");
        Verdict b = boxes(s, a.intent(), a.dim(), a.x(), a.y(), a.z());
        if (!b.allowed()) return b;
        BlockFacts f = a.block();
        Where me = a.me() == null ? Where.DRY : a.me();
        if (!f.loaded()) return Verdict.floor("not loaded");
        if (a.action() == Action.BREAK) {                 // STRUCTURE_BLOCKS: the structure rule before any price
            Verdict sv = structure(s.policy(), a.dim(), a.x(), a.y(), a.z(), f.structure(), f.placedByMe());
            if (!sv.allowed()) return sv;
        }
        RuleSet r = s.ruleSetAt(a.dim(), a.x(), a.y(), a.z());
        return a.action() == Action.PLACE ? place(a, f, r) : brk(s, a, f, me, r);
    }

    private static Verdict brk(Snapshot s, Ask a, BlockFacts f, Where me, RuleSet r) {
        // ---- the floor: every intent, every rule set
        if (f.unbreakable()) return Verdict.floor("unbreakable");
        if (f.inCell() != Fluid.NONE) return Verdict.floor("a liquid is not broken");
        if (f.above() == Fluid.LAVA || f.sides() == Fluid.LAVA) return Verdict.floor("next to lava");
        // ---- the rule set (ores only until phase 3 adds logs and crops)
        if (f.ore() && !r.useResources() && a.intent() == Intent.MINE) return Verdict.deny("no resources taken in " + r.name(), "resources");
        // the owner's rule (0.36.9, aa367: drowned digging down under a pool): water over the cell while I am in the water
        // aa-costs: a dive is denied only while one more block down leaves no air to come back (DrownRisk); else it costs
        double diveRisk = f.above() == Fluid.WATER && me.inWater() ? me.diveRisk(a.y()) : 0;
        boolean dive = DrownRisk.fatal(diveRisk);
        double diveMult = 1 + diveRisk * diveRisk * Tuning.i("breakPolicy.diveRiskK");
        boolean wet = f.above() == Fluid.WATER || f.sides() == Fluid.WATER;
        // ---- the intent model (3.3)
        double mult;
        String rule = a.intent().name().toLowerCase(Locale.ROOT);
        switch (a.intent()) {
            case TRAVEL, BUILD -> {
                mult = r.travelMultiplier();
                if (r.breakNatural() == Natural.TRAVEL_EXPENSIVE) mult = Math.max(mult, Costs.travelExpensive());
                if (dive) return Verdict.deny("digging down from in the water", rule);
                if (f.fallingAbove()) return Verdict.deny("a falling block above", rule);
                if (wet) { mult *= Costs.travelWaterSide(); rule += "+water"; }
                if (a.intent() == Intent.TRAVEL) {              // a build's own cells are not travel breaks: no cap
                    int cap = s.travelCap() > 0 ? s.travelCap() : Costs.travelCapV();
                    if (me.travelBreaks() >= cap) return Verdict.deny("travel break cap " + cap + " reached on this walk", rule);
                    if (me.travelBreaks() > 0) mult *= 1 + 2.0 * me.travelBreaks() / cap;
                }
            }
            case MINE -> {
                mult = r.mineMultiplier();
                if (dive) return Verdict.deny("digging down from in the water", rule);
                if (f.ore()) { mult *= Costs.mineOre(); rule += "+ore"; }
                // a falling block above: allowed; Baritone adds the stack's cost (includeFalling)
            }
            case DIG_OUT -> {
                mult = 1;
                if (dive) return Verdict.deny("digging down from in the water", rule);
                if (wet && me.inWater()) return Verdict.deny("a block by water while I am in the water", rule);
                if (f.fallingAbove()) return Verdict.deny("a falling block above", rule);
            }
            case RESTORE -> { return Verdict.deny("a restore only places", rule); }
            default -> { return Verdict.deny("walking only", rule); }
        }
        if (diveRisk > 0) { mult *= diveMult; rule += "+drown"; }       // aa-costs: x(1 + 4 risk^2)
        return Verdict.ok(mult, rule);
    }

    private static Verdict place(Ask a, BlockFacts f, RuleSet r) {
        if (a.intent() == Intent.NONE) return Verdict.deny("walking only", "intent");
        if (f.inCell() == Fluid.LAVA || f.below() == Fluid.LAVA || f.above() == Fluid.LAVA || f.sides() == Fluid.LAVA) return Verdict.floor("next to lava");
        if (a.intent() == Intent.RESTORE) {
            // the ledger fills only what it broke: air or water (the restore lease says the same)
            return Verdict.ok(1, "restore");
        }
        // the owner's rule (aa372): a cell I broke myself is refilled for about nothing, wherever it is: the break was
        // allowed there, so putting the block back is too (the rule set's place rule is for new blocks)
        if (f.dugByMe()) return Verdict.ok(Costs.refill(), a.intent().name().toLowerCase(Locale.ROOT) + "+refill");
        if (r.place() == PlaceRule.NEVER) return Verdict.deny("no placing in " + r.name(), "area");
        if (r.place() == PlaceRule.TORCHES && !f.torch()) return Verdict.deny("nothing but torches placed in " + r.name(), "area");
        double mult = a.intent() == Intent.TRAVEL ? Costs.travelPlace() : 1;
        return Verdict.ok(mult, a.intent().name().toLowerCase(Locale.ROOT));
    }

    static String fmt(double d) {
        if (d == Math.rint(d)) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "");
    }
}
