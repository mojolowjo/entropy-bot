package io.github.mojolowjo.entropybot.guard;

/**
 * V1a (0.22.0): what the guard allows per area type (BRAIN_PLAN 1.2, VOCABULARY 7). Pure; {@code null} = outside every
 * area. The floor (block entities, the Nether and End, liquids next door) is not in here: it holds in every type.
 *
 * <pre>
 * type        walk                      dig/place natural   break built blocks            restore ledger  brain may start jobs
 * outside     owner order, explore/find no                  no                            on              no
 * neutral     yes                       yes, with a lease   no                            on              yes
 * destroy     yes                       yes, with a lease   only the owner's dig &lt;area&gt;  off             yes
 * main        yes                       yes, with a lease   no                            on              yes, no digging unless asked
 * safe        yes                       no                  no                            -               no
 * </pre>
 */
public final class AreaTypeRules {
    private AreaTypeRules() {}

    /** Who asks for a walk outside every area. */
    public enum Walker { OWNER_ORDER, EXPLORE, FIND, OTHER }

    /** A walk goal there is fine (outside: only the owner's own goto/go, explore and find). */
    public static boolean walk(AreaType t, Walker who) {
        if (t != null) return true;
        return who == Walker.OWNER_ORDER || who == Walker.EXPLORE || who == Walker.FIND;
    }

    /** Natural blocks may be broken or placed there (with a lease). */
    public static boolean dig(AreaType t) {
        return t != null && t != AreaType.SAFE;
    }

    /** Built blocks may be broken there: only in a destroy area, only by the owner's own "dig &lt;area&gt;". */
    public static boolean breakBuilt(AreaType t, boolean ownerDigArea) {
        return t == AreaType.DESTROY && ownerDigArea;
    }

    /** The restore ledger records the bot's incidental breaks there (off in destroy; safe never breaks). */
    public static boolean restore(AreaType t) {
        return t != AreaType.DESTROY && t != AreaType.SAFE;
    }

    /** The brain may start a job of its own there (main: but no digging unless asked, a scorer matter). */
    public static boolean mayStartJob(AreaType t) {
        return t != null && t != AreaType.SAFE;
    }
}
