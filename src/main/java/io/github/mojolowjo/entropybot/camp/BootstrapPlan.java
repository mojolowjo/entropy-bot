package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * C4 (docs/COMPANION_PLAN.md, 2026-10-05): {@code bootstrap}, from an empty bag in a fresh spot to a working camp. Pure
 * Java (JUnit drives it with a fake inventory): what the bot carries and what stands near it go in, the chain of
 * existing commands comes out ({@code chop}, {@code craft}, {@code place}, {@code dig}, {@code gather}, {@code setbase},
 * {@code scan base}, {@code mark camp}). Each step is a normal command, so each reports on its own and a failed one
 * stops the chain with its own "next:" hint. Idempotent: a table/furnace/chest within 8 blocks, tools in the bag
 * (that tier or better), torches and the camp mark are skipped.
 *
 * <p>Cobblestone: {@code mine} only takes ores (MineRules.EXTRA), so the camp digs a walkable staircase quarry of
 * {@link #QUARRY_LEN} 3-wide columns next to it (one {@code dig} box per column, each one deeper: the bot walks in and
 * out, no pit). Coal: {@code gather charcoal} (logs smelted in the camp's furnace) when the bag has no coal.
 *
 * <p>Loader notes: none (no game classes). The game side is {@code commands.CampCommands} (NeoForge client: block
 * lookups via {@code ClientLevel}); a Fabric port reuses this class unchanged.
 */
public final class BootstrapPlan {
    private BootstrapPlan() {}

    /** Columns of the staircase quarry (3 wide; column i is i deep): 3 * (1+..+8) = 108 blocks, about 45 of them stone. */
    public static final int QUARRY_LEN = 8;
    public static final int TORCHES = 16;
    public static final int NEAR = 8;

    /**
     * What the planner looks at. inv: id -> count (namespaced ids). tableNear/furnaceNear/chestNear: one within
     * {@link #NEAR} blocks. campMarked: a place "camp" exists. feet: where the bot stands (the camp's centre); table,
     * furnace, chest: free cells next to it for those blocks (null: none found); dir: the quarry's direction {dx, dz}.
     */
    public record Facts(Map<String, Integer> inv, boolean tableNear, boolean furnaceNear, boolean chestNear, boolean campMarked,
                        int[] feet, int[] table, int[] furnace, int[] chest, int[] dir) {}

    /** The chain (commands, in order) and a one-line summary; err: why it can't start (with the next command). */
    public record Plan(List<String> steps, String summary, String err) {}

    /** `bootstrap status`: a read-only report of what the camp has. Never plans, places or starts anything. */
    public static String statusText(Map<String, Integer> inv, boolean table, boolean furnace, boolean chest, boolean campMarked,
                                    String running, String lastRun) {
        StringBuilder b = new StringBuilder("bootstrap: ");
        b.append("table ").append(table ? "yes" : "no").append(", furnace ").append(furnace ? "yes" : "no")
                .append(", chest ").append(chest ? "yes" : "no").append(" within ").append(NEAR);
        b.append("; tools");
        for (String k : KINDS) b.append(' ').append(k).append(' ').append(best(inv, k) > 0 ? "yes" : "no");
        int torches = inv.getOrDefault("minecraft:torch", 0);
        b.append("; torches ").append(torches).append("; camp mark ").append(campMarked ? "set" : "not set");
        if (running != null) b.append("; running: ").append(running);
        b.append("; last run: ").append(lastRun == null ? "none this session" : lastRun);
        return b.toString();
    }

    static final String[] KINDS = {"pickaxe", "axe", "shovel", "sword"};

    /** Material for one tool of that kind (stone): pickaxe 3, axe 3, shovel 1, sword 2. */
    static int headCost(String kind) {
        return switch (kind) {
            case "pickaxe", "axe" -> 3;
            case "sword" -> 2;
            default -> 1;
        };
    }

    /** Sticks for one tool: sword 1, the rest 2. */
    static int stickCost(String kind) {
        return kind.equals("sword") ? 1 : 2;
    }

    /** Tool tier: wooden/golden 1, stone 2, copper 2, iron 3, diamond 4, netherite 5; 0 = not that kind of tool. */
    public static int tier(String id, String kind) {
        String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (!p.endsWith("_" + kind)) return 0;
        if (kind.equals("axe") && p.endsWith("_pickaxe")) return 0;
        String m = p.substring(0, p.length() - kind.length() - 1);
        return switch (m) {
            case "wooden", "golden" -> 1;
            case "stone", "copper" -> 2;
            case "iron" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> 2;           // a modded material: count it as good enough
        };
    }

    /** The best tier of that tool kind in the bag (0: none). */
    public static int best(Map<String, Integer> inv, String kind) {
        int b = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) if (e.getValue() > 0) b = Math.max(b, tier(e.getKey(), kind));
        return b;
    }

    static int count(Map<String, Integer> inv, String... ids) {
        int n = 0;
        for (String id : ids) n += inv.getOrDefault(id, 0);
        return n;
    }

    static int sumSuffix(Map<String, Integer> inv, String... suffixes) {
        int n = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) for (String s : suffixes) if (e.getKey().endsWith(s)) n += e.getValue();
        return n;
    }

    static String fmt(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }

    public static Plan plan(Facts f) {
        Map<String, Integer> inv = f.inv();
        List<String> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean needTable = !f.tableNear();
        boolean needFurnace = !f.furnaceNear();
        boolean needChest = !f.chestNear();
        if (needTable && f.table() == null) return new Plan(List.of(), null, "error: no free spot next to me for the crafting table - next: goto a flat, open spot, then bootstrap");
        // what is missing
        List<String> tools = new ArrayList<>();
        for (String k : KINDS) if (best(inv, k) < 2) tools.add(k);
        boolean needWoodPick = best(inv, "pickaxe") == 0;
        int torches = count(inv, "minecraft:torch");
        int torchMissing = Math.max(0, TORCHES - torches);
        int coalHave = count(inv, "minecraft:coal", "minecraft:charcoal");
        int coalNeed = Math.max(0, (torchMissing + 3) / 4 - coalHave);
        // cobblestone (or cobbled deepslate: both are stone tool and furnace material)
        int cobbleNeed = 0;
        for (String k : tools) cobbleNeed += headCost(k);
        if (needFurnace && count(inv, "minecraft:furnace") == 0) cobbleNeed += 8;
        int cobbleHave = count(inv, "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone");
        boolean quarry = cobbleNeed > cobbleHave;
        // planks: table 4, chest 8, the wooden pickaxe 3, sticks (2 per plank), charcoal fuel (1 plank per 1.5 items)
        int sticks = 0;
        for (String k : tools) sticks += stickCost(k);
        if (needWoodPick && quarry) sticks += 2;
        sticks += (torchMissing + 3) / 4;
        sticks = Math.max(0, sticks - count(inv, "minecraft:stick"));
        int planks = (sticks + 3) / 4 * 2;
        if (needTable && count(inv, "minecraft:crafting_table") == 0) planks += 4;
        if (needChest && count(inv, "minecraft:chest") == 0) planks += 8;
        if (needWoodPick && quarry) planks += 3;
        if (coalNeed > 0) planks += (coalNeed * 2 + 2) / 3;
        int plankHave = sumSuffix(inv, "_planks") + 4 * sumSuffix(inv, "_log", "_wood", "_stem", "_hyphae");
        int logs = (Math.max(0, planks + 4 * coalNeed - plankHave) + 3) / 4;          // a charcoal takes a whole log
        // the marks first: the furnace steps (gather charcoal) use a furnace near the base
        if (!f.campMarked()) {
            out.add("setbase " + fmt(f.feet()));
            out.add("mark camp " + fmt(f.feet()));
        } else skipped.add("camp mark");
        if (logs > 0) out.add("chop " + Math.max(logs + 2, 6));
        else skipped.add("wood");
        if (needTable) {
            if (count(inv, "minecraft:crafting_table") == 0) out.add("craft crafting_table 1");
            out.add("goto " + fmt(f.feet()));          // back to the camp centre: the spot is 2 away, in reach
            out.add("place crafting_table " + fmt(f.table()));
        } else skipped.add("table");
        if (quarry) {
            if (needWoodPick) out.add("craft wooden_pickaxe 1");
            out.addAll(quarrySteps(f.feet(), f.dir()));
        }
        for (String k : tools) out.add("craft stone_" + k + " 1");
        if (tools.isEmpty()) skipped.add("stone tools");
        if (needFurnace) {
            if (f.furnace() == null) return new Plan(List.of(), null, "error: no free spot next to me for the furnace - next: goto a flat, open spot, then bootstrap");
            if (count(inv, "minecraft:furnace") == 0) out.add("craft furnace 1");
            out.add("goto " + fmt(f.feet()));          // back to the camp centre: the spot is 2 away, in reach
            out.add("place furnace " + fmt(f.furnace()));
        } else skipped.add("furnace");
        if (needChest) {
            if (f.chest() == null) return new Plan(List.of(), null, "error: no free spot next to me for the chest - next: goto a flat, open spot, then bootstrap");
            if (count(inv, "minecraft:chest") == 0) out.add("craft chest 1");
            out.add("goto " + fmt(f.feet()));          // back to the camp centre: the spot is 2 away, in reach
            out.add("place chest " + fmt(f.chest()));
        } else skipped.add("chest");
        if (coalNeed > 0) out.add("gather charcoal " + coalNeed);
        if (torchMissing > 0) out.add("craft torch " + torchMissing);
        else skipped.add("torches");
        if (!out.isEmpty() && (needChest || !f.campMarked())) out.add("scan base");
        String summary = out.isEmpty() ? "ok: the camp is already set up (table, furnace, chest, stone tools, torches, camp marked)"
                : out.size() + " steps" + (skipped.isEmpty() ? "" : "; already have: " + String.join(", ", skipped));
        return new Plan(out, summary, null);
    }

    /**
     * The staircase quarry: column i (1..{@link #QUARRY_LEN}) starts 2 blocks from the bot in direction dir, 3 wide,
     * and is dug from the ground (feet y - 1) down to feet y - i, so each column is one step lower than the last.
     */
    public static List<String> quarrySteps(int[] feet, int[] dir) {
        List<String> out = new ArrayList<>();
        int dx = dir[0], dz = dir[1];
        int px = dz != 0 ? 1 : 0, pz = dx != 0 ? 1 : 0;          // across the stairs
        for (int i = 1; i <= QUARRY_LEN; i++) {
            int cx = feet[0] + dx * (i + 1), cz = feet[2] + dz * (i + 1);
            int x1 = cx - px, z1 = cz - pz, x2 = cx + px, z2 = cz + pz;
            out.add("dig " + x1 + " " + (feet[1] - i) + " " + z1 + " " + x2 + " " + (feet[1] - 1) + " " + z2);
        }
        return out;
    }
}
