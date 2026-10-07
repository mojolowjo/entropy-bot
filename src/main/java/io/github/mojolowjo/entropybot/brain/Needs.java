package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * B1 (BRAIN_PLAN 4.3): the needs, each scored 0..100 from the sensed state. A need rises while unmet (its curve, plus
 * an age bonus for the owner's needs and goals) and drops to 0 when met. The game stage gates the tool need (stage
 * {@code nothing}: no tool need at all, so never "I need a sword") and lowers the food bar. Not released and the owner
 * online (rule 2): an option whose place is more than {@code nearbyR} from the owner is skipped, with a note. Pure.
 */
public final class Needs {
    private Needs() {}

    /** One scored option. chain null: nothing the brain can start for it (the reflexes handle safety). asked: when the owner asked (ties). */
    public record Option(String need, int score, String chain, String reason, int[] where, long asked) {}

    /** The options (highest first) and the ones skipped with why. */
    public record Scored(List<Option> options, List<String> skipped) {
        public Option best(int floor) {
            for (Option o : options) if (o.chain() != null && o.score() > floor) return o;
            return null;
        }

        public int scoreOf(String need) {
            for (Option o : options) if (o.need().equals(need)) return o.score();
            return 0;
        }

        public boolean has(String need) {
            for (Option o : options) if (o.need().equals(need)) return true;
            return false;
        }

        public String line() {
            List<String> w = new ArrayList<>();
            for (Option o : options) w.add(o.need() + " " + o.score());
            return w.isEmpty() ? "none" : String.join(", ", w);
        }
    }

    static int clamp(double v) { return (int) Math.round(Math.max(0, Math.min(100, v))); }

    static int age(long now, long since, int perMinutes, int max) {
        if (since <= 0 || now <= since) return 0;
        return (int) Math.min(max, (now - since) / 60000 / perMinutes);
    }

    static boolean early(String stage) { return "nothing".equals(stage) || "wood".equals(stage); }

    /**
     * Scores every need. parked: need -> why, left out (a need that failed three times). The upkeep option is the first
     * runnable idle item that passes the nearby rule.
     */
    public static Scored score(BrainState s, BrainConfig c, List<String> idle, Map<String, String> parked) {
        return score(s, c, idle, parked, null);
    }

    /**
     * 0.22.3 deadband for the owner's item needs ({@code need <item> <n>}): a need fires when have &lt; refire(n), half
     * of n rounded up (at least 1); once its gather runs (runningNeed is it) it stays on until have reaches n. So torches
     * the strip mine burns (32 -&gt; 31) don't flip the brain back to gathering; it gathers again below 16.
     */
    public static int refire(int want) { return refire(want, 50); }

    /** B4: the deadband share is a setting (deadbandPct): gathers again below pct % of want, rounded up, at least 1. */
    public static int refire(int want, int pct) { return Math.max(1, (int) Math.ceil(want * pct / 100.0)); }

    public static Scored score(BrainState s, BrainConfig c, List<String> idle, Map<String, String> parked, String runningNeed) {
        List<Option> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        // safety: the reflexes act; the score shows it
        double h = s.maxHealth <= 0 ? 1 : s.health / s.maxHealth;
        int safety = s.danger ? 100 : h < 0.5 ? clamp(100 * (1 - h)) : 0;
        out.add(new Option("safety", safety, null, s.danger ? s.dangerWhy : "health " + Math.round(s.health), null, 0));
        // food: edible items vs the bar (lower in the early stages)
        int want = early(s.stage) ? c.i("foodWantEarly") : c.i("foodWant");
        int food = s.foodItems >= want ? 0 : clamp(c.i("foodTop") * (1 - s.foodItems / (double) want));
        out.add(new Option("food", food, food > 0 ? "get food " + (want - s.foodItems) : null, s.foodItems + " food on me, I want " + want, null, 0));
        // tools: a pickaxe for the stage
        int tools = 0;
        String tier = SupplyCheck.tier(s.stage), toolWhy = "stage " + s.stage + ": no tool need yet";
        if (tier != null) {
            if (s.pickaxes == 0) { tools = c.i("toolsNone"); toolWhy = "no pickaxe on me"; }
            else if (s.pickPct <= c.i("toolsWornPct")) { tools = c.i("toolsWorn"); toolWhy = "my pickaxe is at " + s.pickPct + " %"; }
            else toolWhy = "pickaxe " + s.pickPct + " %";
        }
        out.add(new Option("tools", tools, tools > 0 ? "craft " + tier + "_pickaxe 1" : null, toolWhy, null, 0));
        // bag
        int bag = s.freeSlots <= 2 ? c.i("bagFull") : s.freeSlots <= 4 ? c.i("bagLow") : s.freeSlots <= 8 ? c.i("bagSome") : 0;
        out.add(new Option("bag", bag, bag > 0 ? "deposit" : null, s.freeSlots + " free slots", s.basePos, 0));
        // the owner's standing needs
        for (BrainState.NeedItem n : s.needs) {
            int missing = n.want() - n.have();
            if (missing <= 0 || n.want() <= 0) continue;
            String sid = n.id().substring(n.id().indexOf(':') + 1);
            int re = refire(n.want(), c.i("deadbandPct"));
            if (n.have() >= re && !("need:" + sid).equals(runningNeed)) {
                skipped.add("need:" + sid + ": have " + n.have() + " of " + n.want() + " (gathers again below " + re + ")");
                continue;
            }
            int sc = clamp(c.i("ownerNeedBase") + c.i("ownerNeedSpan") * missing / (double) n.want() + age(s.now, n.since(), 2, c.i("ageBonusMax")));
            out.add(new Option("need:" + sid, sc, "gather " + sid + " " + n.want(), "need " + sid + " " + n.want() + ": have " + n.have(), null, n.since()));
        }
        // goals, oldest first
        for (int i = 0; i < s.goals.size(); i++) {
            BrainState.Goal g = s.goals.get(i);
            int sc = clamp(c.i("goalBase") + age(s.now, g.at(), 10, c.i("ageBonusMax")));
            String chain = g.chain();
            if ((chain == null || chain.isBlank()) && s.planner != null) chain = s.planner.apply(g.text());      // B3: no ready chain -> the planner
            out.add(new Option(Brain.goalNeed(g.text()), sc, chain == null || chain.isBlank() ? null : chain, "goal " + g.text(), null, g.at()));
        }
        // copy
        if (s.copy != null) {
            boolean go = s.copy.chain() != null;
            out.add(new Option("copy", go ? c.i("copyScore") : 0, s.copy.chain(), s.copy.why(), go ? s.ownerPos : null, 0));
        }
        // upkeep: the first idle item that may run here
        Option up = null;
        for (IdleList.Pick p : IdleList.candidates(idle, s.upkeep)) {
            if (s.bound() && p.where() != null && BrainState.flat(p.where(), s.ownerPos) > c.i("nearbyR")) {
                skipped.add("upkeep " + p.item() + ": " + (int) BrainState.flat(p.where(), s.ownerPos) + " blocks from you (not released: done lets me go)");
                continue;
            }
            up = new Option("upkeep", c.i("upkeep"), p.chain(), p.why(), p.where(), 0);
            break;
        }
        if (up == null) up = new Option("upkeep", 0, null, "nothing on the idle list can run", null, 0);
        out.add(up);
        // the nearby rule and the parked needs
        List<Option> kept = new ArrayList<>();
        for (Option o : out) {
            if (o.chain() != null && parked.containsKey(o.need())) {
                skipped.add(o.need() + ": parked (" + parked.get(o.need()) + ")");
                continue;
            }
            if (o.chain() != null && s.bound() && o.where() != null && !o.need().equals("upkeep") && BrainState.flat(o.where(), s.ownerPos) > c.i("nearbyR")) {
                skipped.add(o.need() + ": " + (int) BrainState.flat(o.where(), s.ownerPos) + " blocks from you (not released)");
                continue;
            }
            double w = c.weight(o.need());       // B4: need.<name>.weight
            kept.add(w == 1 ? o : new Option(o.need(), clamp(o.score() * w), o.chain(), o.reason() + " (weight " + BrainConfig.num(w) + ")", o.where(), o.asked()));
        }
        kept.sort(Comparator.comparingInt(Option::score).reversed().thenComparing(Comparator.comparingLong(Option::asked).reversed()));
        return new Scored(kept, skipped);
    }
}
