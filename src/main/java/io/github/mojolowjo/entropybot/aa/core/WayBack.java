package io.github.mojolowjo.entropybot.aa.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * aa385 (wave 2): the way back into the areas. Live, "goto -77 92 -71 then hunt 8 pig out" stopped at step 2 (no pig) and
 * left the bot 40 blocks outside; from then every plan named its own spot ("goto -75 96 -96 ...") and was infeasible, so it
 * idled on wait 60. Now: (1) the last spot inside the areas is kept every tick ({@link #observe}); an option that takes the
 * bot outside (a hunt "out", explore) notes it as its way back at its start; (2) while the bot stands outside, every
 * other task gets a costed return leg first ({@link #leg}): "goto" the way back, cost = the walk x {@link #OUT_FACTOR}
 * (travel outside the areas is dear: no placing, no mining), logged with the terms; the desire then re-plans from inside.
 * Pure Java (JUnit: WayBackTest). Loader notes: none. Errors: no way back known (it never stood inside) = no leg, as before.
 */
public final class WayBack {
    private WayBack() {}

    public static final String FACT = "aa.wayBack", OWNER = "aa.wayBackFor";
    /** Walking outside the areas counts this much per block (the hunt out's travel uses the same idea, 2x there and back). */
    public static final double OUT_FACTOR = 2;
    /** Sources a return leg may come before (never danger/owner work). */
    static final Pattern SOURCES = Pattern.compile("needs|night|idle|want");
    /** Steps that go outside on purpose: no leg before them. */
    static final Pattern OUTSIDE_STEP = Pattern.compile("(?i)(^|\\bthen\\s+)(hunt\\s+.*\\bout\\b|explore\\b|go\\s+out\\b)");

    /** Keeps the last spot inside the areas (call once per tick after the inputs are filled). */
    public static void observe(Blackboard bb) {
        if (bb == null || bb.in.pos == null || !bb.in.inArea || !bb.in.areaKnown) return;
        bb.fact(FACT, bb.in.pos.clone());
        if (bb.task() == null || bb.task().spec() == null || !goesOut(bb.task().spec().chain())) bb.fact(OWNER, null);      // review: back inside, the old owner is stale
    }

    /** True for a chain that goes outside on purpose (a hunt out, explore). */
    public static boolean goesOut(String chain) { return chain != null && OUTSIDE_STEP.matcher(chain.trim()).find(); }

    static int[] at(Blackboard bb) { return bb != null && bb.fact(FACT) instanceof int[] p ? p : null; }

    /** The return leg's cost in walk-blocks (straight line x OUT_FACTOR). */
    public static double cost(int[] me, int[] back) {
        double dx = me[0] - back[0], dy = me[1] - back[1], dz = me[2] - back[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) * OUT_FACTOR;
    }

    public record Leg(String chain, double cost, String terms) {}

    /**
     * The return leg owed before {@code chain} (null: none; the bot is inside, the chain goes out on purpose, the source is
     * not the brain's, or no way back is known). Never throws.
     */
    public static Leg leg(Blackboard bb, String chain, String source) {
        if (bb == null || bb.in.inArea || bb.in.pos == null || !bb.in.areaKnown) return null;
        if (source != null && !SOURCES.matcher(source).matches()) return null;
        if (goesOut(chain)) return null;
        int[] back = at(bb);
        if (back == null) return null;
        String g = "goto " + back[0] + " " + back[1] + " " + back[2];
        if (g.equals(chain)) return null;
        double c = cost(bb.in.pos, back);
        String terms = String.format(Locale.ROOT, "return to my areas %d (I am outside at %d %d %d; back to %d %d %d, %d blocks x %.0f outside)%s",
                Math.round(c), bb.in.pos[0], bb.in.pos[1], bb.in.pos[2], back[0], back[1], back[2], Math.round(c / OUT_FACTOR), OUT_FACTOR,
                bb.fact(OWNER) instanceof String o ? "; way back stored by \"" + o + "\"" : "");
        return new Leg(g, c, terms);
    }

    /** An outside option starts: its way back is the spot it starts from (when inside). */
    public static void started(Blackboard bb, String chain) {
        if (bb == null || !goesOut(chain)) return;
        if (bb.in.inArea && bb.in.pos != null) bb.fact(FACT, bb.in.pos.clone());
        bb.fact(OWNER, chain.length() > 40 ? chain.substring(0, 40) + "..." : chain);
    }
}
