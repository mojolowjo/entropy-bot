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
 * no mining data is {@link #unknown} (said in the line, nothing checked). Pure.
 */
public record StepNeeds(String toolKind, int minTier, Map<String, Integer> consumes, String unknown, int fuelTicks) {
    /** The tool role the game's tier data is about (MiningTiers' scale: the needs_*_tool tags rank pickaxes). */
    public static final String TIER_TOOL = "pickaxe";

    public static final StepNeeds NONE = new StepNeeds(null, 0, Map.of(), null, 0);

    /** The record before 0.39.z37 (no fuel declared). */
    public StepNeeds(String toolKind, int minTier, Map<String, Integer> consumes, String unknown) { this(toolKind, minTier, consumes, unknown, 0); }

    public StepNeeds {
        consumes = consumes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(consumes));
    }

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
    }

    public boolean hasTool() { return toolKind != null && minTier >= 1; }

    public boolean none() { return !hasTool() && consumes.isEmpty() && unknown == null && fuelTicks <= 0; }

    /** "[needs: pickaxe tier>=1, oak_planks 3]"; "" when none. */
    public String text() {
        if (none()) return "";
        List<String> p = new ArrayList<>();
        if (hasTool()) p.add(toolKind + " tier>=" + minTier);
        for (var e : consumes.entrySet()) p.add(sh(e.getKey()) + " " + e.getValue());
        if (fuelTicks > 0) p.add("fuel " + fuelTicks + " burn ticks");
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
            // the game's fuel table (any item with a burn time), less what the step itself uses up
            long burn = 0;
            for (var f : ItemWorth.fuels().entrySet()) burn += (long) Math.max(0, bag.count(f.getKey()) - consumes.getOrDefault(f.getKey(), 0)) * f.getValue();
            if (burn < fuelTicks) return "fuel: " + burn + " of " + fuelTicks + " burn ticks in the bag, the " + verb + " burns " + fuelTicks;
        }
        return null;
    }

    /**
     * 0.39.z38 (a pending step's ingredients are never throwaway): the items the job's remaining steps use up, each once
     * in step order - Baritone's run lease keeps them out of {@code acceptableThrowawayItems} while the job runs. A tool is
     * named by its role and tier only (no item), and a tool is never a full block, so no tool can be throwaway.
     */
    public static List<String> keepOf(List<StepNeeds> remaining) {
        List<String> out = new ArrayList<>();
        if (remaining == null) return out;
        for (StepNeeds n : remaining) {
            if (n == null) continue;
            for (String id : n.consumes().keySet()) if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** The fact's subject for the missing tool ("pickaxe tier>=1"). */
    public String toolKey() { return toolKind + " tier>=" + minTier; }

    // ---- the declarations, from the step's own data ----

    /** The step's needs from the game's data; {@link #NONE} for a step that takes nothing from the bag. */
    public static StepNeeds of(Step s) {
        return switch (s) {
            case Step.Collect k -> io.github.mojolowjo.entropybot.aa.NeedChain.plain(k.item()) ? forItem(full(k.item())) : forMembers(k.item());
            case Step.Mine m -> m.how() == Step.Mine.How.STRIP ? NONE : forFamily(m.family());
            case Step.Strip st -> forBlocks(st.ores());
            case Step.ChopTree t -> t.log() == null ? NONE : forBlocks(List.of(t.log()));
            case Step.TakeStation t -> forBlocks(List.of(full(t.item())));     // 0.39.z48: the station block's own tier
            case Step.Craft k -> recipeInputs(k.item(), k.n(), ItemWorth.CRAFT);
            case Step.Smelt k -> withFuel(recipeInputs(k.item(), k.n(), ItemWorth.SMELT), k.item(), k.n());
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
        if (ItemWorth.fuels().isEmpty()) return new StepNeeds(in.toolKind, in.minTier, in.consumes, in.unknown != null ? in.unknown : "no fuel table", 0);
        int ticks = (int) Math.round(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("craftJob.smeltTicks")) * Math.max(1, n);
        return new StepNeeds(in.toolKind, in.minTier, in.consumes, in.unknown, ticks);
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
     * takes alternatives: which one the job uses is its choice, so those inputs are not declared (the job's own check stays).
     */
    static StepNeeds recipeInputs(String item, int n, String kind) {
        List<ItemWorth.Recipe> rs = ItemWorth.recipes(full(item), kind);
        if (rs.size() != 1) return NONE;
        ItemWorth.Recipe r = rs.get(0);
        int times = (int) Math.ceil(Math.max(1, n) / (double) Math.max(1, r.outCount()));
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String w : r.needs()) {
            if (w.startsWith("#") || w.indexOf('|') >= 0) continue;
            m.merge(full(w), times, Integer::sum);
        }
        return new StepNeeds(null, 0, m, null);
    }

    static String full(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    static String sh(String id) { return id.replaceFirst("^minecraft:", ""); }
}
