package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z78 J6c run EU (2; rule 16 and the DU-2 ruling for every own station; run EU 10:45:13: the helmet's smelt at the
 * bot's own furnace 8 61 3 ended "no stand within reach: no standable cell within 4.5 m of furnace at 8 61 3 sees a face of
 * it" and the order stopped on the same try): a use of the bot's OWN station asks the fork to dig to it
 * (IInteractProcess.use(block, veto, digTo), job-vein d705dec7: Baritone picks the stand and digs the way, rule 2); an own
 * station is never held for "no stand within reach". A dig that could not reach it ends with the fork's named end "no path
 * digging to ..." (job-vein e4b81987: only on an exhausted search) - that is the next plan's fact (rule 7): the station's hold, by its cell ("source station at x y z"),
 * with the fork's words. A budget stop ("path not known (<stop>)") is never a fact or a hold (rule 15): the step ends with the fork's words and the order is re-planned. Pure (JUnit: J6cEuTest). Loader notes: none (the fork's ends are its API's named words).
 */
public final class UseEnds {
    private UseEnds() {}

    /** The fork's named end of a dig to the station that found no way (IInteractProcess.use(.., digTo) javadoc). */
    public static final String NO_DIG_PATH = "no path digging to";

    /** The use digs to the station: only an own station (rule 16: its own placement). */
    public static boolean digTo(boolean own) { return own; }

    /**
     * The fact a failed use leaves: an own station ({@code own}) the dig could not reach ({@code why} the fork's end) - the
     * station's source fact at {@code cell}; null: any other end (its step's own short fact stands).
     */
    public static FactChange.Fact fact(int[] cell, boolean own, String why) {
        if (!own || cell == null || cell.length < 3 || why == null || !why.startsWith(NO_DIG_PATH)) return null;   // structural: x y z
        return new FactChange.Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.stationKey(cell[0], cell[1], cell[2]),
                "my station at " + cell[0] + " " + cell[1] + " " + cell[2] + ": " + why);
    }
}
