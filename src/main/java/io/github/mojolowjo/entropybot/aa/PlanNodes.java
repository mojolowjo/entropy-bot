package io.github.mojolowjo.entropybot.aa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 0.39.z78 D6 (design docs/designs/D6-planner-incremental.md, "the spec, one level down"): the planner's nodes keep their
 * sums. {@link NeedChain}'s resolve is a tree of nodes - the request (the root), each need ({@code obtain} /
 * {@code needTag}: a word, a count, the state it was asked in) and each way of a need (priced from the need's one
 * snapshot in {@code choose}) - and each node keeps what it computed (its phrase and the planner's state after it) with
 * every fact it read on the way: the World's answers by key ({@link Fact}), the bag words it read before it wrote them (a
 * scan by a predicate keeps its matching words, a whole-bag read the whole bag), the walk memo's entries, the trial bound.
 * A node is reused when its key and entry state are equal and every one of those facts is still the same; else it is
 * resolved again by the same code, and its unchanged children (kept by key) are reused in turn - so a fact change re-sums
 * only the nodes that read it and their ancestors (rule 1/22: the same facts, the same chain, by construction). A "not
 * known" answer is a fact like any other: the node that read it is dirty the moment the answer lands (rule 15). The writes
 * a resolve makes outside its state (the strip's yield facts, the ranked-out walks) are {@link Effect}s, kept with the node
 * and run again when it is reused (on the worker: on the client tick, when the result is applied).
 * <p>One cache per resolving thread (the client tick's, the planner worker's): per request (at most {@link #ROOTS}) the
 * tree its last resolve made. The game data (items, recipes, tiers, Tuning, the loot book) is one {@link #epoch}: another
 * one forgets every node. {@link #old} runs the resolve as before D6 (nothing read or kept): the replay test's "old
 * resolve". {@link #verify} (tests): every reuse is computed again and compared, a difference throws. Pure (JUnit:
 * D6NodesTest, D6ReplayTest). Loader notes: none.
 */
public final class PlanNodes {
    PlanNodes() {}

    // ---- the switches ----

    private static final ThreadLocal<Boolean> OLD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** {@code run} with the resolve as before D6: no node read or kept (the replay test's old resolve). */
    public static <T> T old(Supplier<T> run) {
        boolean was = OLD.get();
        OLD.set(Boolean.TRUE);
        try {
            return run.get();
        } finally {
            OLD.set(was);
        }
    }

    /** D6 order of work: the replay test (D6ReplayTest, runs DJ / DK / DL) is green - the live path resolves by the nodes; {@link #old} the old resolve. */
    static final boolean DEFAULT_ON = true;
    private static volatile boolean on = DEFAULT_ON;

    /** Tests: the nodes on or off for every thread ({@link #DEFAULT_ON} after). */
    public static void on(boolean v) { on = v; }

    /** The default back (tests). */
    public static void defaultOn() { on = DEFAULT_ON; }

    static boolean isOld() { return OLD.get() || !on; }

    private static volatile boolean verify;

    /** Tests: every reuse is computed again and compared with what was kept (a difference throws). */
    public static void verify(boolean on) { verify = on; }

    static boolean verifying() { return verify; }

    private static final ThreadLocal<PlanNodes> MINE = ThreadLocal.withInitial(PlanNodes::new);

    /** The calling thread's cache. */
    public static PlanNodes mine() { return MINE.get(); }

    /** Tests and a new world: the calling thread's cache forgets every node. */
    public static void forgetMine() { MINE.get().clear(); }

    // ---- facts ----

    /** One fact a node read: the World's field and the key it was asked by ("seen", "minecraft:oak_log"). */
    public record Fact(String field, Object key) {}

    /** A null answer, as the maps keep it. */
    static final Object NULL = new Object() { @Override public String toString() { return "null"; } };

    static Object box(Object v) { return v == null ? NULL : v; }

    static Object unbox(Object v) { return v == NULL ? null : v; }

    /** A write a resolve makes outside the planner's state, kept with its node and run again on a reuse. */
    public interface Effect {
        /** Runs it; {@code path}: the World's path provider of the resolve (the live one when applied on the client tick). */
        void run(PathPrice path);
    }

    /** A predicate scan of the bag: its test, and the words present at the node's entry that matched, in the bag's order. */
    record Scan(Predicate<String> test, List<String> words) {}

    // ---- nodes ----

    /** One node: its key, the state it was entered in, what it read, what it left, its children by key. */
    static final class Node {
        final String kind;
        final Object key;
        final Object entry;
        final int entryHash;
        /** Facts its own code read (its children keep theirs: a check walks them, {@link Run#hold}), in the order first read. */
        Map<Fact, Object> reads = Map.of();
        /** A way node: what its need had read when the ways were made (the way's closure was built from it). */
        Object prefix;
        Map<String, Integer> bagReads = Map.of();
        Map<Object, Scan> scans = Map.of();
        /** Non-null: the node read the whole bag - this, its entry bag. */
        Map<String, Integer> wholeBag;
        Map<String, Object> walkReads = Map.of();
        int entryTrials, maxOff = -1;
        boolean bound;
        /** A way node: its rank budget at entry (null: none). */
        Object extra;
        List<Effect> effects = List.of();
        /** NeedChain's exit record. */
        Object out;
        Map<Object, List<Node>> children = Map.of();
        int resolves, reused, size = 1;

        Node(String kind, Object key, Object entry, int entryHash) {
            this.kind = kind;
            this.key = key;
            this.entry = entry;
            this.entryHash = entryHash;
        }
    }

    /** Debug and tests: one node as counted. */
    public record NodeInfo(String kind, String key, int resolves, int reused) {}

    /** One computation in progress: the reads and writes of the node being computed. */
    static final class Frame {
        final Node node;
        final Frame up;
        /** The kept node of the same key and entry that was dirty (its children are this one's candidates); null: none. */
        Node old;
        final LinkedHashMap<Fact, Object> reads = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> bagReads = new LinkedHashMap<>();
        Set<String> written = new HashSet<>();
        LinkedHashSet<String> created = new LinkedHashSet<>();
        final LinkedHashMap<Object, Scan> scans = new LinkedHashMap<>();
        Map<String, Integer> entryBag;
        boolean whole;
        final LinkedHashMap<String, Object> walkReads = new LinkedHashMap<>();
        final Set<String> walkWrites = new LinkedHashSet<>();
        final List<Effect> effects = new ArrayList<>();
        final List<Object[]> unreach = new ArrayList<>();
        final Map<Object, Object> uses = new IdentityHashMap<>();
        int entryTrials, maxOff = -1, chooses, waitedAt, size = 1;
        boolean bound, keep = true;
        /** False: its bag record is not consistent (a snapshot of another node's restored): not even the root is kept. */
        boolean sound = true;
        final Map<Object, List<Node>> children = new LinkedHashMap<>();

        Frame(Node node, Frame up) {
            this.node = node;
            this.up = up;
        }

        /** The kept candidates for the child {@code key} (the dirty node's children). */
        List<Node> candidates(Object key) { return old == null ? List.of() : old.children.getOrDefault(key, List.of()); }

        /** {@code n} is this node's child {@code key} (computed or reused here). */
        void child(Object key, Node n) {
            children.computeIfAbsent(key, k -> new ArrayList<>(1)).add(n);
            size += n.size;
        }
    }

    /** One resolve: its frames, the facts asked now (each once), its counts, and where its effects go. */
    final class Run {
        final Function<Fact, Object> asker;
        final Map<Fact, Object> now = new HashMap<>();
        final boolean defer;
        final List<Effect> deferred = new ArrayList<>();
        final PathPrice path;
        Frame top;
        int computed, reusedN, dirty;

        Run(Function<Fact, Object> asker, PathPrice path, boolean defer) {
            this.asker = asker;
            this.path = path;
            this.defer = defer;
        }

        PlanNodes cache() { return PlanNodes.this; }

        private final Map<Node, Boolean> held = new IdentityHashMap<>();

        /** Every fact {@code n} and the children it used read has the same answer now (each node checked once a resolve). */
        boolean hold(Node n) {
            Boolean v = held.get(n);
            if (v != null) return v;
            boolean ok = same(n.reads);
            if (ok) for (List<Node> l : n.children.values()) {
                for (Node k : l) if (!hold(k)) { ok = false; break; }
                if (!ok) break;
            }
            held.put(n, ok);
            return ok;
        }

        /** The fact's answer now (asked once a resolve). */
        Object current(Fact f) {
            Object v = now.get(f);
            if (v == null) {
                v = box(asker.apply(f));
                now.put(f, v);
            }
            return unbox(v);
        }

        /** Every fact of {@code reads} has the same answer now. */
        boolean same(Map<Fact, Object> reads) {
            for (var e : reads.entrySet()) if (!Deep.equal(unbox(e.getValue()), current(e.getKey()))) return false;
            return true;
        }

        /** A fact read in this run (its first answer kept): told to the open node. */
        void read(Fact f, Object v) {
            if (top == null) return;
            Object b = box(v);
            now.putIfAbsent(f, b);
            top.reads.putIfAbsent(f, b);
        }

        /** An effect: kept in the open node, run now (the client tick) or kept for the apply (the worker). */
        void effect(Effect e) {
            if (top != null) top.effects.add(e);
            if (defer) deferred.add(e);
            else e.run(path);
        }

        Frame open(Node n) {
            top = new Frame(n, top);
            return top;
        }

        /** Closes the open frame into its parent (reads, bag, walk memo, effects, writes merged). */
        void close() {
            Frame f = top;
            top = f.up;
            if (top != null) mergeUp(f, top);
        }
    }

    /**
     * {@code f}'s bag, walk-memo and trial reads and its writes as its parent {@code p}'s (the parent's own earlier writes
     * come first); its facts stay its own (the parent keeps it as a child).
     */
    static void mergeUp(Frame f, Frame p) {
        for (var e : f.bagReads.entrySet()) if (!p.written.contains(e.getKey()) && !p.bagReads.containsKey(e.getKey())) p.bagReads.put(e.getKey(), e.getValue());
        for (var e : f.scans.entrySet()) scanUp(e.getKey(), e.getValue(), p);
        p.whole |= f.whole;
        p.written.addAll(f.written);
        p.created.addAll(f.created);
        for (var e : f.walkReads.entrySet()) if (!p.walkWrites.contains(e.getKey()) && !p.walkReads.containsKey(e.getKey())) p.walkReads.put(e.getKey(), e.getValue());
        p.walkWrites.addAll(f.walkWrites);
        p.effects.addAll(f.effects);
        p.unreach.addAll(f.unreach);
        p.uses.putAll(f.uses);
        if (f.maxOff >= 0) p.maxOff = Math.max(p.maxOff, f.entryTrials - p.entryTrials + f.maxOff);
        p.bound |= f.bound;
        p.keep &= f.keep;
        p.sound &= f.sound;
    }

    private static void scanUp(Object key, Scan s, Frame p) {
        if (p.scans.containsKey(key)) return;
        List<String> w = new ArrayList<>();
        for (String k : s.words()) if (!p.created.contains(k)) w.add(k);
        p.scans.put(key, new Scan(s.test(), w));
    }

    /**
     * A node reused inside {@code p}: its reads as if {@code p} had made them (before its writes {@code written} /
     * {@code created} / {@code walkWrites}, which then count as {@code p}'s); {@code trials}: the trial count it was
     * reused at.
     */
    static void reusedInto(Node n, Frame p, int trials, List<String> written, List<String> created, Set<String> walkWrites) {
        for (var e : n.bagReads.entrySet()) if (!p.written.contains(e.getKey()) && !p.bagReads.containsKey(e.getKey())) p.bagReads.put(e.getKey(), e.getValue());
        for (var e : n.scans.entrySet()) scanUp(e.getKey(), e.getValue(), p);
        if (n.wholeBag != null) p.whole = true;
        p.written.addAll(written);
        p.created.addAll(created);
        for (var e : n.walkReads.entrySet()) if (!p.walkWrites.contains(e.getKey()) && !p.walkReads.containsKey(e.getKey())) p.walkReads.put(e.getKey(), e.getValue());
        p.walkWrites.addAll(walkWrites);
        if (n.maxOff >= 0) p.maxOff = Math.max(p.maxOff, trials - p.entryTrials + n.maxOff);
        p.bound |= n.bound;
    }

    // ---- storage ----

    /** structural: a memory bound - the requests whose trees are kept (the open orders' trips and chains, the food, ...) */
    static final int ROOTS = 24;

    private final Map<Object, Node> roots = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Object, Node> e) { return size() > ROOTS; }
    };
    private Object epoch;

    /** Every node forgotten. */
    void clear() { roots.clear(); }

    /** The game data's epoch now (NeedChain: items, tiers, Tuning, the loot book): another one forgets every node. */
    void epoch(Object e) {
        if (!Deep.equal(e, epoch)) {
            clear();
            epoch = e;
        }
    }

    Node root(Object key) { return roots.get(key); }

    /**
     * structural: a memory bound - the inner nodes kept across every request (each holds the planner's state as it entered
     * and left it, some KB); past it the least recently used requests keep their root only (its facts and bag words merged
     * into it: still checked whole, never stale), the newest tree is trimmed last.
     */
    static final int INNER = 3000;     // structural: a memory bound (the inner nodes kept)

    void root(Object key, Node n) {
        roots.put(key, n);
        int inner = 0;
        for (Node r : roots.values()) inner += r.size - 1;
        for (var it = roots.values().iterator(); inner > INNER && it.hasNext(); ) {
            Node r = it.next();                                // least recently used first (access order)
            if (r.children.isEmpty()) continue;
            inner -= r.size - 1;
            trim(r);
        }
    }

    /** The node keeps no children: every fact its tree read becomes its own. */
    static void trim(Node r) {
        r.reads = allReads(r);
        r.children = Map.of();
        r.size = 1;
    }

    /** Every fact a tree of nodes read, the first answer each (the request's record: the worker's result is checked by it). */
    static Map<Fact, Object> allReads(Node root) {
        Map<Fact, Object> out = new LinkedHashMap<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> todo = new ArrayList<>(List.of(root));
        while (!todo.isEmpty()) {
            Node n = todo.remove(todo.size() - 1);
            if (!seen.add(n)) continue;
            for (var e : n.reads.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
            for (List<Node> l : n.children.values()) todo.addAll(l);
        }
        return out;
    }

    // ---- counts ----

    /** One resolve's counts: the nodes of its tree, those resolved (again), those reused (whole subtrees), those found dirty. */
    public record Stats(int nodes, int resolved, int reused, int dirty) {
        public static final Stats NONE = new Stats(0, 0, 0, 0);

        public String text() { return "nodes " + nodes + ", re-resolved " + resolved + ", reused " + reused + ", dirty " + dirty; }
    }

    private Stats last = Stats.NONE;

    /** The calling thread's last resolve's counts. */
    public static Stats last() { return MINE.get().last; }

    void last(Stats s) { last = s; }

    /** Debug and tests: every kept node with its counts. */
    public static List<NodeInfo> nodes() {
        Set<Node> all = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> todo = new ArrayList<>(MINE.get().roots.values());
        while (!todo.isEmpty()) {
            Node n = todo.remove(todo.size() - 1);
            if (!all.add(n)) continue;
            for (List<Node> l : n.children.values()) todo.addAll(l);
        }
        List<NodeInfo> out = new ArrayList<>();
        for (Node n : all) out.add(new NodeInfo(n.kind, String.valueOf(n.key), n.resolves, n.reused));
        return out;
    }

    /** Tests: the resolves of the kept nodes of {@code kind} whose key text contains {@code part}, summed. */
    public static int resolves(String kind, String part) {
        int n = 0;
        for (NodeInfo i : nodes()) if (i.kind().equals(kind) && i.key().contains(part)) n += i.resolves();
        return n;
    }
}
