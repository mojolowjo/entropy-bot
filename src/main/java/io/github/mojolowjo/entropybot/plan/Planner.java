package io.github.mojolowjo.entropybot.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * B3 (docs/BRAIN_PLAN.md 6, BRAIN_LOOP "owner's direction"): a bounded GOAP-style search that turns a goal (items to
 * have, facts to make true) into a chain of existing verbs when nobody wrote a chain for it. It searches backwards
 * from the goal: each open need is met from the bag (or what an earlier step left over), or by one of the actions of
 * {@link ActionTable} that gives it (fetch, cut, craft, place, smelt, mine, quarry, gather, build), whose own inputs
 * and preconditions become new needs. A* over the summed action costs (heuristic: the open needs), at most
 * {@link #MAX_NODES} nodes, {@link #MAX_STEPS} steps and {@link #BUDGET_MS} ms. Steps keep their dependencies (the step
 * that makes a thing runs before the step that uses it) and come out in that order. World facts no action gives
 * (trees, stone, a mine, an ore seen, a free spot) end a branch; when every branch ends, the cheapest one's missing
 * fact is the answer ("no plan for bed: missing white_wool ..."). Every step is a normal verb, so the guard still checks
 * each one when it runs. Pure (no game classes).
 */
public final class Planner {
    private Planner() {}

    public static final int MAX_NODES = 2000, MAX_STEPS = 24;
    public static final long BUDGET_MS = 200;
    /** What one quarry (bootstrap's 8 staircase columns) is counted to give. */
    public static final int QUARRY_GIVES = 40;
    public static final int SHELTER_BLOCKS = 32;

    /** One thing the goal wants: an item count or a fact (flag). */
    public record Need(String what, int n, boolean flag) {
        public static Need item(String what, int n) { return new Need(PlanFacts.canon(what), n, false); }
        public static Need fact(String what) { return new Need(what, 1, true); }
    }

    /** One step of a found plan: its command text (may hold "then"), its cost, the action id. */
    public record PlanStep(String text, int cost, String action) {}

    /** steps: in run order; missing: why no plan (null when found); error: the search's limit (null when it ran out normally). */
    public record Result(List<PlanStep> steps, int cost, int nodes, long ms, String missing, String error) {
        public boolean ok() { return missing == null && error == null; }

        public String chain() {
            List<String> t = new ArrayList<>();
            for (PlanStep s : steps) t.add(s.text());
            return String.join(" then ", t);
        }

        /** "cut 2 logs (7) > craft planks 4 (2) > ..." */
        public String costed() {
            List<String> t = new ArrayList<>();
            for (PlanStep s : steps) t.add(s.text() + " (" + s.cost() + ")");
            return String.join(" > ", t);
        }
    }

    // ---- search state ----

    static final class Step {
        final String action, item, key;
        int amount, cost;
        final Set<Integer> deps = new HashSet<>();
        String extra;           // a source word (mine strip|ore|cave) or the shelter block

        Step(String action, String item, String key) {
            this.action = action;
            this.item = item;
            this.key = key;
        }

        Step copy() {
            Step s = new Step(action, item, key);
            s.amount = amount;
            s.cost = cost;
            s.deps.addAll(deps);
            s.extra = extra;
            return s;
        }
    }

    record Req(String what, int n, boolean flag, int forStep) {}

    static final class Node {
        final List<Step> steps = new ArrayList<>();
        /** item -> {source step (-1 the bag), amount} */
        final Map<String, List<int[]>> pool = new HashMap<>();
        /** fact -> the step that makes it (-1 already true) */
        final Map<String, List<Integer>> flags = new HashMap<>();
        final ArrayDeque<Req> open = new ArrayDeque<>();
        int g;
        boolean dead;

        Node copy() {
            Node n = new Node();
            for (Step s : steps) n.steps.add(s.copy());
            for (Map.Entry<String, List<int[]>> e : pool.entrySet()) {
                List<int[]> l = new ArrayList<>();
                for (int[] a : e.getValue()) l.add(a.clone());
                n.pool.put(e.getKey(), l);
            }
            for (Map.Entry<String, List<Integer>> e : flags.entrySet()) n.flags.put(e.getKey(), new ArrayList<>(e.getValue()));
            n.open.addAll(open);
            n.g = g;
            return n;
        }

        int f() { return g + open.size(); }

        /** a depends on b (b runs first); false when that makes a loop. */
        boolean dep(int a, int b) {
            if (a < 0 || b < 0) return true;
            if (a == b || reaches(b, a, new HashSet<>())) return false;
            steps.get(a).deps.add(b);
            return true;
        }

        boolean reaches(int from, int to, Set<Integer> seen) {
            if (from == to) return true;
            if (!seen.add(from)) return false;
            for (int d : steps.get(from).deps) if (reaches(d, to, seen)) return true;
            return false;
        }

        int find(String key) {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).key.equals(key)) return i;
            return -1;
        }
    }

    /** World facts no action gives, with the hint for the owner. */
    static final Map<String, String> WORLD = new LinkedHashMap<>();
    static {
        WORLD.put("trees", "trees in my areas (area here <r> <name> where trees grow)");
        WORLD.put("stone", "stone under me to quarry (go to stony ground, or put cobblestone in a chest)");
        WORLD.put("quarry", "solid ground beside me to quarry");
        WORLD.put("spot:table", "a free spot next to me for the crafting table (goto a flat, open spot)");
        WORLD.put("spot:furnace", "a free spot next to me for the furnace (goto a flat, open spot)");
    }

    // ---- the search ----

    public static Result plan(PlanFacts facts, List<Need> goal) { return plan(facts, goal, MAX_NODES, BUDGET_MS); }

    public static Result plan(PlanFacts facts, List<Need> goal, int maxNodes, long budgetMs) {
        long t0 = System.nanoTime();
        Node root = new Node();
        for (Map.Entry<String, Integer> e : facts.bag.entrySet()) if (e.getValue() > 0) root.pool.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[]{-1, e.getValue()});
        for (String f : facts.flags) addFlag(root, f, -1);
        for (Need n : goal) root.open.add(new Req(n.what(), n.n(), n.flag(), -1));
        PriorityQueue<Node> q = new PriorityQueue<>((a, b) -> a.f() != b.f() ? Integer.compare(a.f(), b.f()) : Integer.compare(b.steps.size(), a.steps.size()));
        q.add(root);
        int nodes = 0;
        String bestMissing = null;
        int bestMissingG = Integer.MAX_VALUE;
        while (!q.isEmpty()) {
            if (nodes >= maxNodes) return new Result(List.of(), 0, nodes, ms(t0), null, "plan search hit its limit (" + maxNodes + " nodes)");
            if (ms(t0) > budgetMs) return new Result(List.of(), 0, nodes, ms(t0), null, "plan search hit its time limit (" + budgetMs + " ms)");
            Node n = q.poll();
            nodes++;
            Req r = settle(n);
            if (n.dead) continue;
            if (r == null) {
                List<Step> order = order(n);
                if (order == null) continue;
                return new Result(texts(order, facts), n.g, nodes, ms(t0), null, null);
            }
            List<String> why = new ArrayList<>();
            List<Node> kids = r.flag() ? flagProviders(n, r, facts, why) : itemProviders(n, r, facts, why);
            for (Node k : kids) if (!k.dead && k.steps.size() <= MAX_STEPS) q.add(k);
            if (kids.isEmpty() && n.g < bestMissingG) {
                bestMissingG = n.g;
                bestMissing = (r.flag() ? r.what() : r.n() + " " + word(r.what())) + (why.isEmpty() ? "" : " - " + String.join("; ", why));
            }
        }
        return new Result(List.of(), 0, nodes, ms(t0), bestMissing == null ? "no action gives it" : bestMissing, null);
    }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    /** Meets every need it can without choosing (true facts, the pool); returns the first need that needs a choice, or null. */
    static Req settle(Node n) {
        while (!n.open.isEmpty()) {
            Req r = n.open.peekFirst();
            if (r.flag()) {
                // any step that makes the fact and doesn't come after the requester (a loop) will do
                List<Integer> srcs = n.flags.get(r.what());
                boolean met = false;
                if (srcs != null) for (int src : srcs) {
                    if (src >= 0 && r.forStep() >= 0 && (src == r.forStep() || n.reaches(src, r.forStep(), new HashSet<>()))) continue;
                    n.dep(r.forStep(), src);
                    met = true;
                    break;
                }
                if (!met) return r;
                n.open.pollFirst();
                continue;
            }
            int left = r.n();
            List<int[]> srcs = n.pool.get(r.what());
            if (srcs != null) {
                for (int[] s : srcs) {
                    if (left == 0) break;
                    if (s[1] <= 0) continue;
                    if (s[0] >= 0 && r.forStep() >= 0 && (s[0] == r.forStep() || n.reaches(s[0], r.forStep(), new HashSet<>()))) continue;
                    int take = Math.min(left, s[1]);
                    s[1] -= take;
                    left -= take;
                    if (!n.dep(r.forStep(), s[0])) { n.dead = true; return r; }
                }
            }
            n.open.pollFirst();
            if (left > 0) {
                Req rest = new Req(r.what(), left, false, r.forStep());
                n.open.addFirst(rest);
                return rest;
            }
        }
        return null;
    }

    // ---- providers ----

    static Node child(Node n) {
        Node k = n.copy();
        k.open.pollFirst();
        return k;
    }

    /** A new step (or a leaf step of the same key grown); the requester depends on it. Returns its index, -1 on a loop. */
    static int addStep(Node k, Req r, String action, String item, String key, int amount, int base, int perUnit, boolean merge) {
        int i = merge ? k.find(key) : -1;
        if (i >= 0) {
            Step s = k.steps.get(i);
            s.amount += amount;
            s.cost += perUnit * amount;
            k.g += perUnit * amount;
        } else {
            Step s = new Step(action, item, key);
            s.amount = amount;
            s.cost = base + perUnit * amount;
            k.g += s.cost;
            k.steps.add(s);
            i = k.steps.size() - 1;
        }
        if (!k.dep(r.forStep(), i)) {
            k.dead = true;
            return -1;
        }
        return i;
    }

    static void addFlag(Node k, String f, int s) {
        List<Integer> l = k.flags.computeIfAbsent(f, x -> new ArrayList<>());
        if (!l.contains(s)) l.add(s);
    }

    /** An existing step whose key starts so and that the requester doesn't come before (no loop), or -1. */
    static int growable(Node n, String keyPrefix, int forStep) {
        for (int i = 0; i < n.steps.size(); i++) {
            if (!n.steps.get(i).key.startsWith(keyPrefix + ":")) continue;
            if (forStep >= 0 && (i == forStep || n.reaches(i, forStep, new HashSet<>()))) continue;
            return i;
        }
        return -1;
    }

    /** A pickaxe made or fetched by step s makes the pick facts of its tier and below true. */
    static void toolFacts(Node k, String item, int s) {
        if (item.endsWith("_pickaxe")) for (int t = 1; t <= PlanFacts.tier(item.replace("_pickaxe", "")); t++) addFlag(k, "pick" + t, s);
    }

    static void give(Node k, String item, int step, int n) {
        if (n > 0) k.pool.computeIfAbsent(item, x -> new ArrayList<>()).add(new int[]{step, n});
    }

    static List<Node> itemProviders(Node n, Req r, PlanFacts f, List<String> why) {
        List<Node> out = new ArrayList<>();
        String x = r.what();
        int d = r.n();
        // fetch from the chests / the RS network
        int stocked = f.stock.getOrDefault(x, 0);
        int fi = n.find("fetch:" + x);
        int avail = stocked - (fi >= 0 ? n.steps.get(fi).amount : 0);
        if (avail > 0) {
            Node k = child(n);
            int take = Math.min(d, avail);
            int s = addStep(k, r, "fetch", x, "fetch:" + x, take, 3, 0, true);
            if (s >= 0) toolFacts(k, x, s);
            if (take < d) k.open.addFirst(new Req(x, d - take, false, r.forStep()));
            out.add(k);
        } else why.add(stocked > 0 ? "all " + stocked + " in storage already planned" : "none in storage");
        // cut
        if (x.equals("log")) {
            if (n.flags.containsKey("trees")) {
                Node k = child(n);
                addStep(k, r, "cut", x, "cut", d, 3, 2, true);
                out.add(k);
            } else why.add("no " + WORLD.get("trees"));
        }
        // craft
        PlanRecipes.Recipe rec = PlanRecipes.of(x);
        int grow = rec == null ? -1 : growable(n, "craft:" + x, r.forStep());
        if (grow >= 0) {
            // the same item is crafted already: that step makes more (its extra inputs become needs of it)
            Node k = child(n);
            Step s = k.steps.get(grow);
            int oldB = s.amount / rec.out(), newB = (s.amount + d + rec.out() - 1) / rec.out();
            int extra = newB * rec.out() - s.amount - d;
            s.amount = newB * rec.out();
            if (k.dep(r.forStep(), grow)) {
                give(k, x, grow, extra);
                List<Req> in = new ArrayList<>();
                for (Map.Entry<String, Integer> e : rec.in().entrySet()) in.add(new Req(e.getKey(), e.getValue() * (newB - oldB), false, grow));
                for (int i = in.size() - 1; i >= 0; i--) k.open.addFirst(in.get(i));
                out.add(k);
            }
        } else if (rec != null) {
            Node k = child(n);
            int batches = (d + rec.out() - 1) / rec.out();
            int s = addStep(k, r, "craft", x, "craft:" + x + ":" + k.steps.size(), batches * rec.out(), 2, 0, false);
            if (s >= 0) {
                give(k, x, s, batches * rec.out() - d);
                List<Req> in = new ArrayList<>();
                if (rec.table()) in.add(new Req("table", 1, true, s));
                for (Map.Entry<String, Integer> e : rec.in().entrySet()) in.add(new Req(e.getKey(), e.getValue() * batches, false, s));
                for (int i = in.size() - 1; i >= 0; i--) k.open.addFirst(in.get(i));
                toolFacts(k, x, s);
                out.add(k);
            }
        }
        // smelt
        String raw = PlanRecipes.SMELT.get(x);
        int sgrow = raw == null ? -1 : growable(n, "smelt:" + x, r.forStep());
        if (sgrow >= 0) {
            // one smelt step makes more (more raw, maybe one more fuel)
            Node k = child(n);
            Step s = k.steps.get(sgrow);
            int fuel = (s.amount + d + 7) / 8 - (s.amount + 7) / 8;
            s.amount += d;
            s.cost += d;
            k.g += d;
            if (k.dep(r.forStep(), sgrow)) {
                if (fuel > 0) k.open.addFirst(new Req("fuel", fuel, false, sgrow));
                k.open.addFirst(new Req(raw, d, false, sgrow));
                out.add(k);
            }
        } else if (raw != null) {
            Node k = child(n);
            int s = addStep(k, r, "smelt", x, "smelt:" + x + ":" + k.steps.size(), d, 6, 1, false);
            if (s >= 0) {
                k.open.addFirst(new Req("fuel", (d + 7) / 8, false, s));
                k.open.addFirst(new Req(raw, d, false, s));
                k.open.addFirst(new Req("furnace", 1, true, s));
                out.add(k);
            }
        }
        // mine (ore drops; coal is "fuel")
        Object[] ore = PlanRecipes.ORES.get(x);
        if (ore != null) {
            String fam = (String) ore[0];
            String src = n.flags.containsKey("ore:" + fam) ? "ore" : n.flags.containsKey("mine") ? "strip" : n.flags.containsKey("cave") ? "cave" : null;
            if (src != null) {
                Node k = child(n);
                int s = addStep(k, r, "mine", x, "mine:" + x, d, 10, 3, true);
                if (s >= 0) {
                    k.steps.get(s).extra = src;
                    k.open.addFirst(new Req("pick" + ore[1], 1, true, s));
                    out.add(k);
                }
            } else why.add("nowhere to mine " + fam + " (place mine north, an ore in view, or a known cave)");
        }
        // quarry (cobblestone)
        if (x.equals("cobblestone")) {
            if (!n.flags.containsKey("stone")) why.add("no " + WORLD.get("stone"));
            else if (!n.flags.containsKey("quarry")) why.add("no " + WORLD.get("quarry"));
            else if (n.find("quarry") >= 0) why.add("one quarry per plan (" + QUARRY_GIVES + " cobblestone)");
            else {
                Node k = child(n);
                int s = addStep(k, r, "quarry", x, "quarry", QUARRY_GIVES, 25, 0, false);
                if (s >= 0) {
                    if (d > QUARRY_GIVES) k.open.addFirst(new Req(x, d - QUARRY_GIVES, false, r.forStep()));
                    else give(k, x, s, QUARRY_GIVES - d);
                    k.open.addFirst(new Req("pick1", 1, true, s));
                    out.add(k);
                }
            }
        }
        // gather (food; sand, gravel, clay: the gather verb's own sources)
        if (x.equals("food") || x.equals("sand") || x.equals("gravel") || x.equals("clay_ball")) {
            Node k = child(n);
            addStep(k, r, "gather", x, "gather:" + x, d, 20, 2, true);
            out.add(k);
        }
        if (rec == null && raw == null && ore == null && !x.equals("log") && !x.equals("cobblestone") && out.isEmpty()) why.add("no recipe or source I know");
        return out;
    }

    static List<Node> flagProviders(Node n, Req r, PlanFacts f, List<String> why) {
        List<Node> out = new ArrayList<>();
        String x = r.what();
        if (x.equals("table") || x.equals("furnace")) {
            String block = x.equals("table") ? "crafting_table" : "furnace";
            if (n.flags.containsKey("spot:" + x)) {
                Node k = child(n);
                int s = addStep(k, r, "place", block, "place:" + block, 1, 3, 0, false);
                if (s >= 0) {
                    addFlag(k, x, s);
                    k.open.addFirst(new Req(block, 1, false, s));
                    out.add(k);
                }
            } else why.add("no " + WORLD.get("spot:" + x));
            return out;
        }
        if (x.matches("^pick[1-4]$")) {
            int t = x.charAt(4) - '0';
            String mat = switch (t) { case 1 -> "wooden"; case 2 -> "stone"; case 3 -> "iron"; default -> "diamond"; };
            // the pickaxe is a tool: the step that makes (or fetches) it makes the fact true, then the fact is met through it
            Node k = child(n);
            k.open.addFirst(new Req(x, 1, true, r.forStep()));
            k.open.addFirst(new Req(mat + "_pickaxe", 1, false, -1));
            out.add(k);
            return out;
        }
        if (x.equals("shelter")) {
            for (String block : List.of("cobblestone", "planks")) {
                Node k = child(n);
                int s = addStep(k, r, "build", block, "build:shelter", SHELTER_BLOCKS, 10, 0, false);
                if (s >= 0) {
                    k.steps.get(s).extra = block;
                    addFlag(k, "shelter", s);
                    k.open.addFirst(new Req(block, SHELTER_BLOCKS, false, s));
                    out.add(k);
                }
            }
            return out;
        }
        why.add(WORLD.getOrDefault(x, "no action gives it"));
        return out;
    }

    // ---- the result ----

    /** Steps in run order (what a step needs runs before it); null on a loop. Among ready steps the newest first. */
    static List<Step> order(Node n) {
        int m = n.steps.size();
        boolean[] done = new boolean[m];
        List<Step> out = new ArrayList<>();
        for (int round = 0; round < m; round++) {
            int pick = -1;
            for (int i = m - 1; i >= 0; i--) {
                if (done[i]) continue;
                boolean ready = true;
                for (int d : n.steps.get(i).deps) if (!done[d]) { ready = false; break; }
                if (ready) { pick = i; break; }
            }
            if (pick < 0) return null;
            done[pick] = true;
            out.add(n.steps.get(pick));
        }
        return out;
    }

    /** The planner's word as a command word. */
    static String word(String item) {
        return switch (item) {
            case "log" -> "logs";
            case "fuel" -> "coal";
            default -> item;
        };
    }

    static List<PlanStep> texts(List<Step> order, PlanFacts f) {
        List<PlanStep> out = new ArrayList<>();
        for (Step s : order) {
            String t = switch (s.action) {
                case "fetch" -> "get " + word(s.item) + " " + s.amount;
                case "cut" -> "cut " + s.amount + " logs";
                case "craft" -> "craft " + s.item + " " + s.amount;
                case "place" -> {
                    int[] at = s.item.equals("crafting_table") ? f.table : f.furnace;
                    yield "goto " + xyz(f.feet) + " then place " + s.item + " " + xyz(at);
                }
                case "smelt" -> "smelt " + s.item + " " + s.amount + " then smelt collect all";
                case "mine" -> {
                    String fam = (String) PlanRecipes.ORES.get(s.item)[0];
                    yield switch (s.extra == null ? "strip" : s.extra) {
                        case "ore" -> "mine " + fam + "_ore " + s.amount;
                        case "cave" -> "mine cave " + fam + " " + s.amount;
                        default -> "mine strip " + fam + " " + s.amount;
                    };
                }
                case "quarry" -> String.join(" then ", io.github.mojolowjo.entropybot.camp.BootstrapPlan.quarrySteps(f.feet, f.dir));
                case "gather" -> "gather " + word(s.item) + " " + s.amount;
                case "build" -> "area here 1 shelter neutral 1 2 then build shell " + (s.extra.equals("planks") ? "oak_planks" : s.extra) + " shelter";
                default -> s.action + " " + s.item + " " + s.amount;
            };
            out.add(new PlanStep(t, s.cost, s.action));
        }
        // two crafts of the same item next to each other become one
        List<PlanStep> merged = new ArrayList<>();
        for (PlanStep p : out) {
            if (!merged.isEmpty()) {
                PlanStep last = merged.get(merged.size() - 1);
                String[] a = last.text().split(" "), b = p.text().split(" ");
                if (p.action().equals("craft") && last.action().equals("craft") && a.length == 3 && b.length == 3 && a[1].equals(b[1])) {
                    merged.set(merged.size() - 1, new PlanStep("craft " + a[1] + " " + (Integer.parseInt(a[2]) + Integer.parseInt(b[2])), last.cost() + p.cost(), "craft"));
                    continue;
                }
            }
            merged.add(p);
        }
        return merged;
    }

    static String xyz(int[] p) { return p == null ? "? ? ?" : p[0] + " " + p[1] + " " + p[2]; }

    /** Every action id the planner can choose (the document lists them). */
    public static final List<String> ACTIONS = List.of("fetch", "cut", "craft", "place", "smelt", "mine", "quarry", "gather", "build");

    /** For tests: the set of action ids a result used. */
    public static Set<String> used(Result r) {
        Set<String> s = new LinkedHashSet<>();
        for (PlanStep p : r.steps()) s.add(p.action());
        return s;
    }
}
