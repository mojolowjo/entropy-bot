package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z34 (owner 2026-10-23: "when it is doing a job we need more checks on the inventory during it, to make sure it can
 * still complete the job"; S39): what a step needs from the bag to run, from the step's own data - never a name list. A
 * tool: the tool role the game's tier tags speak of ({@code pickaxe}: MiningTiers' scale, the needs_*_tool tags) and the
 * least tier among the blocks the step mines (the dig takes any of them it can); the items it uses up: a craft's / smelt's
 * single recipe inputs (the game's recipe data), a place's blocks. {@link Steps} checks it before the step starts, when it
 * ends short and while a tool step runs; a shortfall is a {@link FactChange#bagNeed} and the order re-plans (z30). Rule 15:
 * no mining data is {@link #unknown} (said in the line, nothing checked).
 * <p>0.39.z61 (design D1 J2, counts not keep lists; run P z56 23:48: every stone member kept whole with 103 cobblestone in
 * the bag, no pillar priced): {@code tagUses} - a recipe slot that takes alternatives (a tag, an "a|b" word) with its count,
 * where z55's {@code keeps} listed every member with none; {@code guess} - the recipe of an item with several recipes was
 * guessed (none of them is covered by the bag; the dry run does not check the step); {@code countsUnknown} - rule 15, the
 * counts cannot be known (no recipe data, no fuel table): {@link Reservations} says it and keeps what it can name whole.
 * The plan's own counts replace the recipe's through {@link #withUses}. Pure (JUnit: Z61ReservationsTest, BagChecksTest).
 * Loader notes: none (the game's data through ItemWorth / MiningTiers).
 */
public record StepNeeds(String toolKind, int minTier, Map<String, Integer> consumes, String unknown, int fuelTicks,
                        Map<String, Integer> tagUses, String guess, String countsUnknown) {
    /** The tool role the game's tier data is about (MiningTiers' scale: the needs_*_tool tags rank pickaxes). */
    public static final String TIER_TOOL = "pickaxe";

    public static final StepNeeds NONE = new StepNeeds(null, 0, Map.of(), null, 0);

    /** The record before 0.39.z37 (no fuel declared). */
    public StepNeeds(String toolKind, int minTier, Map<String, Integer> consumes, String unknown) { this(toolKind, minTier, consumes, unknown, 0); }

    /** The record before 0.39.z61 (no counted tag slot, no guess, counts known). */
    public StepNeeds(String toolKind, int minTier, Map<String, Integer> consumes, String unknown, int fuelTicks) { this(toolKind, minTier, consumes, unknown, fuelTicks, Map.of(), null, null); }

    public StepNeeds {
        consumes = consumes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(consumes));
        tagUses = tagUses == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tagUses));
    }

    /**
     * 0.39.z61 (D1 J2 3.1 item 1): the same needs with the plan's own counts for what the step uses up ({@code uses}: need
     * word -> count, NeedChain's simulated takes for this step): a plain item to {@link #consumes}, a tag or "a|b" word to
     * {@link #tagUses}; the recipe guess and the rule-15 "no recipe data" go (the plan knows its recipe). The tool and the
     * fuel stay the step's own. {@code uses} null: this, unchanged.
     */
    public StepNeeds withUses(Map<String, Integer> uses) {
        if (uses == null) return this;
        Map<String, Integer> plain = new LinkedHashMap<>(), tags = new LinkedHashMap<>();
        int fuel = fuelTicks;
        for (var e : uses.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            // 0.39.z75 item 2 (run AF 18:21:46): the fuel the plan named for a smelt is a counted need of the step - one item:
            // a consume like any (reserved by its count, checked by the dry run; the generic burn ticks give way to it); a
            // class of items: the burn ticks stay (one kind that covers them, the furnace job's rule: DryRun.oneKindTicks)
            if (e.getKey().startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.FUEL_USE)) {
                String fw = io.github.mojolowjo.entropybot.aa.NeedChain.word(e.getKey().substring(io.github.mojolowjo.entropybot.aa.NeedChain.FUEL_USE.length()));
                if (fuelTicks > 0 && io.github.mojolowjo.entropybot.aa.NeedChain.plain(fw)) { plain.merge(fw, e.getValue(), Integer::sum); fuel = 0; }
                continue;
            }
            String w = io.github.mojolowjo.entropybot.aa.NeedChain.word(e.getKey());
            if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(w)) plain.merge(w, e.getValue(), Integer::sum);
            else tags.merge(w, e.getValue(), Integer::sum);
        }
        String unk = countsUnknown != null && countsUnknown.equals(unknown) ? null : unknown;
        String still = countsUnknown != null && countsUnknown.startsWith(NO_FUEL) ? countsUnknown : null;   // the fuel table is not the plan's
        return new StepNeeds(toolKind, minTier, plain, still != null ? unknown : unk, fuel, tags, null, still);
    }

    /** The rule-15 words of a smelt with no fuel table. */
    static final String NO_FUEL = "no fuel table";

    /** What the bag holds now (the game's inventory; tests a map). */
    public interface Bag {
        /** The best tier of a tool of {@code kind} in the bag (0: none). */
        int bestTier(String kind);

        /** The bag's best tool of {@code kind} (its id), null: none. */
        String bestTool(String kind);

        /** The uses left of the bag's best stack of {@code id} (ToolUses' scale: UNKNOWN / UNLIMITED). */
        int usesLeft(String id);

        /** How many of {@code id} the bag holds. */
        int count(String id);

        /** 0.39.z61: every stack of the bag (item id -> count), for the dry run; null: these hands cannot list it (no dry run). */
        default Map<String, Integer> items() { return null; }
    }

    public boolean hasTool() { return toolKind != null && minTier >= 1; }

    public boolean none() { return !hasTool() && consumes.isEmpty() && tagUses.isEmpty() && unknown == null && fuelTicks <= 0; }

    /** "[needs: pickaxe tier>=1, oak_planks 3]"; "" when none. */
    public String text() {
        if (none()) return "";
        List<String> p = new ArrayList<>();
        if (hasTool()) p.add(toolKind + " tier>=" + minTier);
        for (var e : consumes.entrySet()) p.add(sh(e.getKey()) + " " + e.getValue());
        for (var e : tagUses.entrySet()) p.add(sh(e.getKey()) + " " + e.getValue());
        if (fuelTicks > 0) p.add("fuel " + fuelTicks + " burn ticks");
        if (guess != null) p.add(guess);
        if (unknown != null) p.add("not loaded: " + unknown);
        return "[needs: " + String.join(", ", p) + "]";
    }

    /**
     * The tool the step needs is missing from {@code bag}: the fact's words ("stone_pickaxe gone (broke), the collect needs a
     * pickaxe of tier >= 1"); null: it is there. {@code had}: the tool seen at the step's start (null: none then),
     * {@code hadUses}: its uses left at the last look ("broke" only when its durability was known and finite: the game
     * removes a tool whose uses run out).
     */
    public String toolMissing(Bag bag, String verb, String had, int hadUses) {
        if (!hasTool() || bag == null) return null;
        if (bag.bestTier(toolKind) >= minTier) return null;
        String need = "the " + verb + " needs a " + toolKind + " of tier >= " + minTier;
        if (had == null) return "no " + toolKind + " of tier >= " + minTier + " in the bag, " + need;
        boolean finite = hadUses != io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNKNOWN && hadUses != io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED;
        return sh(had) + " gone" + (finite && bag.count(had) <= 0 ? " (broke)" : "") + ", " + need;
    }

    /** An item the step uses up short in {@code bag}: "oak_planks: 1 of 3 in the bag, the craft uses 3"; null: all there. */
    public String itemShort(Bag bag, String verb) {
        if (bag == null) return null;
        for (var e : consumes.entrySet()) {
            int have = bag.count(e.getKey());
            if (have < e.getValue()) return sh(e.getKey()) + ": " + have + " of " + e.getValue() + " in the bag, the " + verb + " uses " + e.getValue();
        }
        if (fuelTicks > 0) {
            // the game's fuel table (any item with a burn time), less what the step itself uses up; 0.39.z75 item 2 (run AF
            // 18:21:46: a mix "in the bag" the furnace job could not burn): ONE kind that covers it, never a tool - the job's
            // rule (CraftPlanner.pickFuel)
            long burn = 0;
            for (var f : ItemWorth.fuels().entrySet()) {
                if (f.getValue() == null || f.getValue() <= 0 || ItemWorth.damageable(f.getKey())) continue;
                burn = Math.max(burn, (long) Math.max(0, bag.count(f.getKey()) - consumes.getOrDefault(f.getKey(), 0)) * f.getValue());
            }
            if (burn < fuelTicks) return "fuel: " + burn + " of " + fuelTicks + " burn ticks in the bag of one kind, the " + verb + " burns " + fuelTicks;
        }
        return null;
    }

    /** The fact's subject for the missing tool ("pickaxe tier>=1"). */
    public String toolKey() { return toolKind + " tier>=" + minTier; }

    // ---- the declarations, from the step's own data ----

    /** The step's needs from the game's data; {@link #NONE} for a step that takes nothing from the bag. */
    public static StepNeeds of(Step s) { return of(s, null); }

    /**
     * 0.39.z61: as {@link #of(Step)}; {@code bag} (null: none) picks the recipe of an item with several recipes - the first
     * in the game's order the bag covers (D1 3.1 item 2).
     */
    public static StepNeeds of(Step s, Bag bag) {
        return switch (s) {
            case Step.Collect k -> io.github.mojolowjo.entropybot.aa.NeedChain.plain(k.item()) ? forItem(full(k.item())) : forMembers(k.item());
            case Step.Mine m -> m.how() == Step.Mine.How.STRIP ? NONE : forFamily(m.family());
            case Step.Strip st -> forBlocks(st.ores());
            case Step.ChopTree t -> t.log() == null ? NONE : forBlocks(List.of(t.log()));
            case Step.TakeStation t -> forBlocks(List.of(full(t.item())));     // 0.39.z48: the station block's own tier
            case Step.Craft k -> recipeInputs(k.item(), k.n(), ItemWorth.CRAFT, bag);
            case Step.Smelt k -> withFuel(recipeInputs(k.item(), k.n(), ItemWorth.SMELT, bag), k.item(), k.n());
            case Step.Build b -> {
                Map<String, Integer> m = new LinkedHashMap<>();
                for (String i : b.items()) m.merge(full(i), 1, Integer::sum);
                yield new StepNeeds(null, 0, m, null);
            }
            case Step.PlaceNear n -> new StepNeeds(null, 0, Map.of(full(n.item()), 1), null);
            default -> NONE;
        };
    }

    /** A dig of any of {@code blocks} (block / item ids): the least tier among them (the dig takes those it can). */
    public static StepNeeds forBlocks(List<String> blocks) {
        if (!MiningTiers.ready()) return blocks.isEmpty() ? NONE : new StepNeeds(null, 0, Map.of(), "no mining data");
        int least = Integer.MAX_VALUE;
        for (String b : blocks) {
            int t = MiningTiers.source().blockTier(full(b));
            if (t >= 0) least = Math.min(least, t);
        }
        return least == Integer.MAX_VALUE || least == MiningTiers.UNREACHABLE || least < 1 ? NONE : new StepNeeds(TIER_TOOL, least, Map.of(), null);
    }

    /** A mine of a family (KNOWN / DIG / CAVE): the least tier among the family's ore blocks (the game's block registry and mining tags). */
    static StepNeeds forFamily(String family) {
        if (!MiningTiers.ready()) return new StepNeeds(null, 0, Map.of(), "no mining data");
        int t = MiningTiers.source().familyTier(family);
        if (t < 0) return new StepNeeds(null, 0, Map.of(), "no block of the family " + family + " in the mining data");
        return t < 1 || t == MiningTiers.UNREACHABLE ? NONE : new StepNeeds(TIER_TOOL, t, Map.of(), null);
    }

    /** A collect of a tag (or an "a|b" list): the least tier over its members (the dig takes any it can), as for one item. */
    static StepNeeds forMembers(String word) {
        if (!MiningTiers.ready()) return new StepNeeds(null, 0, Map.of(), "no mining data");
        int least = Integer.MAX_VALUE;
        for (String item : io.github.mojolowjo.entropybot.aa.NeedChain.membersOf(word)) {
            int t = MiningTiers.source().blockTier(full(item));
            if (t < 0) t = MiningTiers.source().dropTier(full(item));
            if (t >= 0 && t != MiningTiers.UNREACHABLE) least = Math.min(least, t);
        }
        return least == Integer.MAX_VALUE || least < 1 ? NONE : new StepNeeds(TIER_TOOL, least, Map.of(), null);
    }

    /** A smelt's burn: {@code n} items x Tuning craftJob.smeltTicks burn ticks, from any item the game's fuel table lists. */
    static StepNeeds withFuel(StepNeeds in, String item, int n) {
        if (ItemWorth.fuels().isEmpty())
            return new StepNeeds(in.toolKind, in.minTier, in.consumes, in.unknown != null ? in.unknown : NO_FUEL, 0, in.tagUses, in.guess, in.countsUnknown != null ? in.countsUnknown : NO_FUEL);
        int ticks = (int) Math.round(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("craftJob.smeltTicks")) * Math.max(1, n);
        return new StepNeeds(in.toolKind, in.minTier, in.consumes, in.unknown, ticks, in.tagUses, in.guess, in.countsUnknown);
    }

    /** A collect of {@code item}: its own block's tier, else the least tier of the blocks that drop it (the loot data). */
    static StepNeeds forItem(String item) {
        if (!MiningTiers.ready()) return new StepNeeds(null, 0, Map.of(), "no mining data");
        int t = MiningTiers.source().blockTier(item);
        if (t < 0) t = MiningTiers.source().dropTier(item);
        return t < 1 || t == MiningTiers.UNREACHABLE ? NONE : new StepNeeds(TIER_TOOL, t, Map.of(), null);
    }

    /**
     * The inputs of the item's one recipe of {@code kind}, times the crafts {@code n} takes. Several recipes or a slot that
     * takes alternatives: which one the job uses is its choice, so those inputs are not declared as counts (the job's own
     * check stays). 0.39.z55 item 1 (run N 21:24:13: "torch: none of cobblestone, blackstone, cobbled_deepslate left in the
     * bag (ingredients ran out (cobblestone))" after the walk to the table placed the cobblestone to climb; the same on
     * 2026-10-23): such slots were skipped here, so the furnace's one slot {@code #minecraft:stone_crafting_materials} put
     * nothing in the keep list and Baritone used the cobblestone as throwaway. Every member of such a slot, and every input of
     * every recipe when there are several, is now in {@link #keeps}: none of them is throwaway while the job still needs it.
     * <p>0.39.z61 (D1 J2 3.1 item 2, the fallback for a step the plan did not count): a slot that takes alternatives is one
     * counted need ({@link #tagUses}: its word x the crafts), never a whole-stack keep. Several recipes: when each has one
     * input slot (smelting), one need over the union of their inputs; otherwise the first recipe in the game's order that
     * {@code bag} covers, else the first recipe with {@link #guess} "recipe guessed" (said in the line).
     */
    static StepNeeds recipeInputs(String item, int n, String kind, Bag bag) {
        // rule 15: no recipe data is said in the step's line (nothing checked, nothing counted), never read as "no inputs"
        if (!ItemWorth.hasData()) {
            String why = "no recipe data (its inputs are not counted)";
            return new StepNeeds(null, 0, Map.of(), why, 0, Map.of(), null, why);
        }
        List<ItemWorth.Recipe> rs = ItemWorth.recipes(full(item), kind);
        if (rs.isEmpty()) return NONE;
        Map<String, Integer> plain = new LinkedHashMap<>(), tags = new LinkedHashMap<>();
        if (rs.size() == 1) {
            countsOf(rs.get(0), n, plain, tags);
            return new StepNeeds(null, 0, plain, null, 0, tags, null, null);
        }
        boolean oneSlotEach = true;
        for (ItemWorth.Recipe r : rs) oneSlotEach &= r.options().size() == 1;
        if (oneSlotEach) {                       // smelting: any of the inputs, one per batch
            java.util.Set<String> members = new java.util.TreeSet<>();
            int times = 0;
            for (ItemWorth.Recipe r : rs) {
                for (String i : r.options().get(0)) members.add(full(i));
                times = Math.max(times, times(n, r));
            }
            String w = io.github.mojolowjo.entropybot.aa.NeedChain.word(String.join("|", members));
            if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(w)) plain.put(w, times); else tags.put(w, times);
            return new StepNeeds(null, 0, plain, null, 0, tags, null, null);
        }
        for (ItemWorth.Recipe r : rs) {
            Map<String, Integer> p = new LinkedHashMap<>(), t = new LinkedHashMap<>();
            countsOf(r, n, p, t);
            if (covers(bag, p) && covers(bag, t)) return new StepNeeds(null, 0, p, null, 0, t, null, null);
        }
        countsOf(rs.get(0), n, plain, tags);
        return new StepNeeds(null, 0, plain, null, 0, tags, "recipe guessed (first of " + rs.size() + ", the bag covers none)", null);
    }

    /** The recipe's batches for {@code n} items. */
    private static int times(int n, ItemWorth.Recipe r) { return (int) Math.ceil(Math.max(1, n) / (double) Math.max(1, r.outCount())); }

    /** One recipe's inputs x its batches: plain items into {@code plain}, a tag / "a|b" slot (its word) into {@code tags}. */
    private static void countsOf(ItemWorth.Recipe r, int n, Map<String, Integer> plain, Map<String, Integer> tags) {
        int times = times(n, r);
        for (String raw : r.needs()) {
            String w = io.github.mojolowjo.entropybot.aa.NeedChain.word(raw);
            if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(w)) plain.merge(w, times, Integer::sum);
            else tags.merge(w, times, Integer::sum);
        }
    }

    /** The bag holds every need word's count (a tag: its members summed); no bag: not covered. */
    private static boolean covers(Bag bag, Map<String, Integer> need) {
        if (bag == null) return false;
        for (var e : need.entrySet()) {
            int have = 0;
            if (io.github.mojolowjo.entropybot.aa.NeedChain.plain(e.getKey())) have = bag.count(e.getKey());
            else for (String m : io.github.mojolowjo.entropybot.aa.NeedChain.membersOf(e.getKey())) have += bag.count(full(m));
            if (have < e.getValue()) return false;
        }
        return true;
    }

    static String full(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    /** "minecraft:stick" -> "stick", "#minecraft:planks" -> "#planks", "minecraft:a|minecraft:b" -> "a|b". */
    static String sh(String id) { return id.replaceAll("(^|[|#])minecraft:", "$1"); }
}
