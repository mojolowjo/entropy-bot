package io.github.mojolowjo.entropybot.guard;

/**
 * V1a (0.22.0), AREAS (0.39.0, AREAS_PLAN 2 and 4): what the guard allows per meaning. Pure; {@code null} = no area
 * there, and the world is open by default: a walk, a dig (with a lease) and a job are fine. The floor (block entities,
 * built blocks, the Nether and End, liquids next door) is not in here: it holds everywhere.
 *
 * <pre>
 * meaning     walk        dig/place natural   break built blocks            restore ledger  jobs may start
 * none        yes         yes, with a lease   no                            on              yes
 * keep-out    no          no                  no                            -               no
 * no-break    yes         no                  no                            -               no
 * destroy     yes         yes, with a lease   only the owner's dig &lt;area&gt;  off             yes
 * base        yes         yes, with a lease   no                            on              yes (digging is a scorer matter)
 * work        yes         yes, with a lease   no                            on              yes
 * storage     yes         yes, with a lease   no                            on              yes
 * danger      yes         yes, with a lease   no                            on              yes (a cost, AA's matter)
 * </pre>
 */
public final class AreaTypeRules {
    private AreaTypeRules() {}

    /** Who asks for a walk. Kept for the callers; since 0.39.0 only keep-out vetoes a walk, whoever asks. */
    public enum Walker { OWNER_ORDER, EXPLORE, FIND, OTHER }

    /** A walk goal there is fine: everywhere but a keep-out area. */
    public static boolean walk(AreaType t, Walker who) {
        return t != AreaType.KEEP_OUT;
    }

    /** Natural blocks may be broken or placed there (with a lease): everywhere but keep-out and no-break. */
    public static boolean dig(AreaType t) {
        return t != AreaType.KEEP_OUT && t != AreaType.NO_BREAK;
    }

    /** Built blocks may be broken there: only in a destroy area, only by the owner's own "dig &lt;area&gt;". */
    public static boolean breakBuilt(AreaType t, boolean ownerDigArea) {
        return t == AreaType.DESTROY && ownerDigArea;
    }

    /** The restore ledger records the bot's incidental breaks there (off in destroy; keep-out and no-break never break). */
    public static boolean restore(AreaType t) {
        return t != AreaType.DESTROY && t != AreaType.KEEP_OUT && t != AreaType.NO_BREAK;
    }

    /** AA may start a job of its own there: everywhere but keep-out and no-break (base digging is a scorer matter). */
    public static boolean mayStartJob(AreaType t) {
        return t != AreaType.KEEP_OUT && t != AreaType.NO_BREAK;
    }

    /** The guard keeps every break and placement out (the "protect" list of the policy): keep-out and no-break. */
    public static boolean protects(AreaType t) {
        return t == AreaType.KEEP_OUT || t == AreaType.NO_BREAK;
    }
}
