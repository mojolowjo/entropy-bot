package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z61 (design D1 J2, owner 2026-10-25 "counts, not keep lists"; run P z56 23:48:55: the keep list held every stone member
 * whole with 103 cobblestone in the bag, so no pillar was priced and every climb answered "no progress"; run N z54 21:24:42:
 * the opposite, nothing kept and the furnace's cobblestone pillared): the running chain's reserves for the fork's lease -
 * what each remaining step uses up, with its count - built at every step start from the steps' needs (the plan's own takes
 * through {@link StepNeeds#withUses}, else the step's recipe) plus the orders' targets.
 * <ul>
 * <li>a plain item: one reserve of that item; a tag or "a|b" slot: one reserve over its members (the game's tag index);</li>
 * <li>a smelt's burn: one weighted reserve "fuel" over the game's fuel table, weight = burn ticks per item;</li>
 * <li>the orders (the running order's target, a finished order's items: owner's ruling 1 option c, safe from pillaring but
 * usable as ingredients) are ORDER reserves the caller hands in ({@link #order});</li>
 * <li>rule 15: a step whose counts are not known ({@link StepNeeds#countsUnknown}, a tag with no members in the index) is
 * {@link #unknown}, said in the step's start line; what it names by item is reserved whole, nothing else is guessed;</li>
 * <li>an Obtain not yet planned reserves nothing until its chain lands (the bounded window): said in {@link #note}.</li>
 * </ul>
 * A tool is never a reserve (a tool is never a full block, so never throwaway). The math (spare, spare throwaway) is the
 * fork's {@link Reserves}, the one place of it. Pure (JUnit: Z61ReservationsTest). Loader notes: none (the game's data
 * through ItemWorth: the tag index, the fuel table; the fork's Reserves is plain Java).
 */
public record Reservations(List<Reserves.Reserve<String>> list, String unknown, String note) {
    /** The weighted reserve's word for a smelt's burn. */
    public static final String FUEL = "fuel";

    public static final Reservations NONE = new Reservations(List.of(), null, null);

    public Reservations {
        list = list == null ? List.of() : List.copyOf(list);
    }

    /**
     * The reserves of {@code steps} from {@code from} on: {@code needs} parallel to {@code steps.subList(from, ...)} (each
     * step's needs with the plan's uses applied), then {@code orders} (ORDER reserves).
     */
    public static Reservations build(List<Step> steps, int from, List<StepNeeds> needs, List<Reserves.Reserve<String>> orders) {
        List<Reserves.Reserve<String>> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>(), notes = new ArrayList<>();
        for (int k = 0; k < needs.size() && from + k < steps.size(); k++) {
            int i = from + k;
            Step s = steps.get(i);
            StepNeeds n = needs.get(k);
            String owner = s.text();
            if (s instanceof Step.Obtain) notes.add(owner + " reserves nothing until its chain is planned");
            if (n == null) continue;
            if (n.countsUnknown() != null) {
                List<String> whole = new ArrayList<>();
                for (String item : n.consumes().keySet()) { out.add(Reserves.Reserve.whole(item, item, owner + " - not loaded: kept whole")); whole.add(StepNeeds.sh(item)); }
                unknown.add("step " + (i + 1) + " (" + owner + "): " + n.countsUnknown() + " - kept whole: " + (whole.isEmpty() ? "nothing it names" : String.join(", ", whole)));
                continue;
            }
            for (var e : n.consumes().entrySet())
                out.add(Reserves.Reserve.of(e.getKey(), List.of(e.getKey()), e.getValue(), Reserves.Kind.CONSUME, i, owner));
            for (var e : n.tagUses().entrySet()) {
                List<String> members = new ArrayList<>();
                for (String m : NeedChain.membersOf(e.getKey())) members.add(StepNeeds.full(m));
                if (members.isEmpty()) {             // rule 15: the tag index is not loaded (or names no member): said, not "none"
                    unknown.add("step " + (i + 1) + " (" + owner + "): no member of " + StepNeeds.sh(e.getKey()) + " in the tag index - not counted");
                    continue;
                }
                out.add(Reserves.Reserve.of(e.getKey(), members, e.getValue(), Reserves.Kind.CONSUME, i, owner));
            }
            if (n.fuelTicks() > 0) {
                Map<String, Integer> fuels = new LinkedHashMap<>();
                for (var f : ItemWorth.fuels().entrySet()) if (f.getValue() != null && f.getValue() >= 1) fuels.put(f.getKey(), f.getValue());
                if (fuels.isEmpty()) unknown.add("step " + (i + 1) + " (" + owner + "): " + StepNeeds.NO_FUEL + " - fuel not counted");
                else out.add(new Reserves.Reserve<>(FUEL, new ArrayList<>(fuels.keySet()), fuels, n.fuelTicks(), Reserves.Kind.CONSUME, i, owner));
            }
        }
        if (orders != null) out.addAll(orders);
        return new Reservations(out, unknown.isEmpty() ? null : String.join("; ", unknown), notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** Only the orders' reserves: the table while the run re-plans (release before repair, D1 3.4). */
    public static Reservations ordersOnly(List<Reserves.Reserve<String>> orders) {
        return new Reservations(orders == null ? List.of() : orders, null, null);
    }

    /** An order's target as an ORDER reserve: {@code units} of any of {@code members}, owned by "order {@code key}". */
    public static Reserves.Reserve<String> order(String key, List<String> members, int units) {
        List<String> m = new ArrayList<>();
        for (String id : members) m.add(StepNeeds.full(id));
        return Reserves.Reserve.of(m.size() == 1 ? m.get(0) : key, m, Math.max(0, units), Reserves.Kind.ORDER, -1, "order " + key);
    }

    /** The reserves of the chain's steps (not the orders'): what a release lets go. */
    public int chainCount() {
        int n = 0;
        for (var r : list) if (r.kind != Reserves.Kind.ORDER) n++;
        return n;
    }

    /** The orders' reserves in this table. */
    public List<Reserves.Reserve<String>> orders() {
        List<Reserves.Reserve<String>> out = new ArrayList<>();
        for (var r : list) if (r.kind == Reserves.Kind.ORDER) out.add(r);
        return out;
    }

    /**
     * "stone_crafting_materials 8 (craft furnace), stick 2 (craft stone_pickaxe), fuel 600 ticks (smelt iron_ingot 3),
     * cobblestone 64 (order cobblestone)"; "nothing" when empty; the rule-15 unknown and the note after it.
     */
    public String text() {
        List<String> p = new ArrayList<>();
        for (var r : list) p.add(text(r));
        return (p.isEmpty() ? "nothing" : String.join(", ", p)) + (unknown == null ? "" : " | not loaded: " + unknown) + (note == null ? "" : " | " + note);
    }

    /** The chain's reserves only (not the orders'): what a deposit and a sub-chain must leave in the bag. */
    public List<Reserves.Reserve<String>> chain() {
        List<Reserves.Reserve<String>> out = new ArrayList<>();
        for (var r : list) if (r.kind != Reserves.Kind.ORDER) out.add(r);
        return out;
    }

    /**
     * D1 ledger (job-d1): the probe's words, "reserved: stick 2 for craft stone_pickaxe 1, fuel 600 ticks for smelt iron_ingot 3
     * here"; "reserved: nothing"; the rule-15 unknown after it.
     */
    public String ledgerText() {
        List<String> p = new ArrayList<>();
        for (var r : list) p.add(forText(r));
        return "reserved: " + (p.isEmpty() ? "nothing" : String.join(", ", p)) + (unknown == null ? "" : " | not loaded: " + unknown);
    }

    /** One reserve as the ledger line writes it: "stick 2 for craft stone_pickaxe 1". */
    public static String forText(Reserves.Reserve<String> r) {
        String units = r.whole() ? "all" : r.word.equals(FUEL) ? r.units + " ticks" : String.valueOf(r.units);
        return StepNeeds.sh(r.word) + " " + units + (r.owner.isEmpty() ? "" : " for " + r.owner);
    }

    /**
     * D1 ledger (job-d1, pure): the bag with the reserved counts taken out - what another use (a sub-chain's plan) may take
     * without eating into a later step's claim. Keys stay as given (item paths or ids; both read the same). Jointly safe: each
     * item gives only what the reserves can spare after the items before it gave theirs (the fork's {@link Reserves#spare}),
     * so shared tag members are never counted free twice. Whole reserves keep the whole stack.
     */
    public static Map<String, Integer> free(List<Reserves.Reserve<String>> reserves, Map<String, Integer> bag) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (bag == null) return out;
        if (reserves == null || reserves.isEmpty()) { out.putAll(bag); return out; }
        Map<String, Integer> byId = new LinkedHashMap<>();
        for (var e : bag.entrySet()) if (e.getValue() != null && e.getValue() > 0) byId.merge(StepNeeds.full(e.getKey()), e.getValue(), Integer::sum);
        for (var e : bag.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n <= 0) { out.put(e.getKey(), n); continue; }
            String id = StepNeeds.full(e.getKey());
            int spare = Math.max(0, Math.min(n, Reserves.spare(reserves, byId, id)));
            out.put(e.getKey(), spare);
            byId.merge(id, -spare, Integer::sum);                 // what is now promised to this use leaves the pool
        }
        return out;
    }

    /** How many of each item {@link #free} holds back for the reserves ("stone_crafting_materials" members by item), none: empty. */
    public static Map<String, Integer> held(List<Reserves.Reserve<String>> reserves, Map<String, Integer> bag) {
        Map<String, Integer> f = free(reserves, bag), out = new LinkedHashMap<>();
        if (bag != null) for (var e : bag.entrySet()) {
            int h = (e.getValue() == null ? 0 : e.getValue()) - f.getOrDefault(e.getKey(), 0);
            if (h > 0) out.put(e.getKey(), h);
        }
        return out;
    }

    /** One reserve as the lines write it. */
    public static String text(Reserves.Reserve<String> r) {
        String units = r.whole() ? "all" : r.word.equals(FUEL) ? r.units + " ticks" : String.valueOf(r.units);
        return StepNeeds.sh(r.word) + " " + units + (r.owner.isEmpty() ? "" : " (" + r.owner + ")");
    }
}
