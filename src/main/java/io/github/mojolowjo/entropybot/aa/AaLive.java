package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Costs;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dashboard's live AA tree (to-do vanilla "Dashboard: the live AA tree"): AA's state as JSON, generated from the running
 * tree itself (never a hand-drawn copy), so the panel follows any change to {@link AaTree#build}. Served by the fast
 * channel's {@code GET /aa} and built on the game thread.
 *
 * <ul>
 *   <li>{@code tree}: every node as {id, label, type, s (this tick's status or null), reason, kids} - the shape
 *       {@code aa tree} prints;</li>
 *   <li>{@code path}: the node ids from the root to the deepest RUNNING node this tick;</li>
 *   <li>{@code desires}: the brain's last scoring, highest rank first: score, own, parts (the cost terms), skip
 *       (parked / cooling / not wanted, with the reason), would (an interrupt held back), and the rung's option
 *       ranking when it has a picker;</li>
 *   <li>{@code costs}, {@code held}, {@code switches} (the last N decision and outcome lines), {@code counts}.</li>
 * </ul>
 * Pure (Gson only): JUnit AaLiveTest. Loader notes: none.
 */
public final class AaLive {
    private AaLive() {}

    public static final int SWITCHES = 12, DEPTH_MAX = 40;

    public static JsonObject json(AaTree tree, Blackboard bb, boolean on, int switches) {
        long now = bb.clock.now();
        Map<String, Status> st = bb.tracer.nodes();
        JsonObject o = new JsonObject();
        o.addProperty("t", now);
        o.addProperty("on", on);
        o.addProperty("driver", tree.driver());
        o.addProperty("danger", tree.dangerWhy());
        var rd = tree.brain.runningDesire();
        o.addProperty("running", rd == null ? null : rd.id);
        o.add("tree", node(tree.root, st, 0));
        JsonArray path = new JsonArray();
        Node n = tree.root;
        for (int d = 0; n != null && d < DEPTH_MAX; d++) {
            path.add(n.id);
            Node next = null;
            for (Node c : n.children()) if (st.get(c.id) == Status.RUNNING) { next = c; break; }
            n = next;
        }
        o.add("path", path);
        Brain b = tree.brain;
        List<Brain.Scored> sc = new ArrayList<>(b.lastScored());
        sc.sort((x, y) -> Double.compare(y.rank(), x.rank()));
        List<Rung> rungs = AaLadder.rungs(tree.ladder);
        JsonArray ds = new JsonArray();
        for (Brain.Scored s : sc) {
            JsonObject d = new JsonObject();
            d.addProperty("id", s.d().id);
            d.addProperty("kind", s.d().kind.name());
            d.addProperty("node", s.d().node.id);
            d.addProperty("score", r1(s.score()));
            d.addProperty("own", r1(s.own()));
            d.addProperty("rank", r1(s.rank()));
            if (s.parked() > 0) d.addProperty("parkedValue", r1(s.parked()));
            d.addProperty("parts", s.parts());
            d.addProperty("skip", s.skip());
            d.addProperty("would", b.wouldWhy(s.d().id));
            d.addProperty("exposure", s.d().exposure().name());
            d.addProperty("fails", b.streak(s.d().id));
            d.addProperty("running", s.d() == rd);
            if (s.d().node instanceof Brain set) {
                // director 2026-10-19: idle is one desire; its options (scored inside it) go under it
                JsonObject op = new JsonObject();
                op.addProperty("why", s.parts());
                var inner = set.runningDesire();
                op.addProperty("chosen", inner == null ? null : inner.id);
                List<Brain.Scored> os = new ArrayList<>(set.lastScored());
                os.sort((x, y) -> Double.compare(y.rank(), x.rank()));
                JsonArray rows = new JsonArray();
                for (Brain.Scored x : os) {
                    JsonObject row = new JsonObject();
                    row.addProperty("text", x.d().id + " " + Math.round(x.score()) + " [" + x.parts() + "]" + (x.skip() != null ? " - " + x.skip() : ""));
                    row.addProperty("command", x.d().node.id);
                    row.addProperty("exposure", x.d().exposure().name());
                    row.addProperty("fails", set.streak(x.d().id));
                    rows.add(row);
                }
                op.add("rows", rows);
                d.add("options", op);
            }
            for (Rung r : rungs) {
                if (r != s.d().node || r.picker() == null) continue;
                Rung.Picker p = r.picker();
                JsonObject op = new JsonObject();
                op.addProperty("why", p.why());
                op.addProperty("chosen", p.chosenId());
                double c = p.lastCostS();
                if (!Double.isNaN(c)) op.addProperty("costS", r1(c));
                JsonArray rows = new JsonArray();
                for (AaManual.Row row : p.rows()) {
                    JsonObject x = new JsonObject();
                    x.addProperty("text", row.label());
                    x.addProperty("command", row.command());
                    rows.add(x);
                }
                op.add("rows", rows);
                d.add("options", op);
            }
            ds.add(d);
        }
        o.add("desires", ds);
        o.add("costs", strings(Costs.lines(bb)));
        o.add("held", strings(b.heldLines(now)));
        List<String> dec = bb.tracer.decisions();
        JsonArray sw = new JsonArray();
        for (int i = Math.max(0, dec.size() - Math.max(0, switches)); i < dec.size(); i++) {
            try {
                sw.add(JsonParser.parseString(dec.get(i)));
            } catch (RuntimeException e) {
                sw.add(dec.get(i));
            }
        }
        o.add("switches", sw);
        o.addProperty("lastSwitch", bb.tracer.lastSwitchText(now));
        var k = bb.tracer.counts;
        JsonObject counts = new JsonObject();
        counts.addProperty("switches", k.switches);
        counts.addProperty("pointless", k.pointless);
        counts.addProperty("preempts", k.preempts);
        counts.addProperty("fails", k.fails);
        counts.addProperty("ticks", k.ticks);
        counts.addProperty("picks", b.picks);
        counts.addProperty("interrupts", b.interrupts);
        counts.addProperty("stuck", b.stuck);
        o.add("counts", counts);
        return o;
    }

    static JsonObject node(Node n, Map<String, Status> st, int depth) {
        JsonObject j = new JsonObject();
        j.addProperty("id", n.id);
        j.addProperty("label", n.label);
        String type = n.getClass().getSimpleName();
        j.addProperty("type", type.isEmpty() ? n.getClass().getSuperclass().getSimpleName() : type);
        Status s = st.get(n.id);
        j.addProperty("s", s == null ? null : s.name());
        if (s == Status.FAILURE && n.reason() != null) j.addProperty("reason", n.reason());
        List<Node> kids = n.children();
        if (!kids.isEmpty() && depth < DEPTH_MAX) {
            JsonArray a = new JsonArray();
            for (Node c : kids) a.add(node(c, st, depth + 1));
            j.add("kids", a);
        }
        return j;
    }

    private static JsonElement strings(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    private static double r1(double v) { return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : -1; }
}
