package io.github.mojolowjo.entropybot.aa.door;

/**
 * AA_CLEANUP step 2 ("one door"): who asks to start or stop a job or chain. While AA is on only {@link #AA},
 * {@link #OWNER} and {@link #REFLEX} pass; the rest are refused at the door (see {@link Door}).
 * Loader-neutral: no game imports.
 */
public enum Driver {
    /** AA's own tick and the chains it started. */
    AA,
    /** The owner's own command (chat, PM, bridge, /ent), and the chains and jobs it started. */
    OWNER,
    /** A true reflex: air, lava/fire, fall, creeper flee, the hit response. */
    REFLEX,
    /** A hand-rule danger decision in the reflexes (fight, retreat home, cover, food run, survival): step 4 makes them AA options. */
    DANGER,
    /** The owner's rules and routines firing on a timer. */
    RULE,
    /** The AI operator (AiRuntime); under AA it may only say "aa want" / "aa drop". */
    AI,
    /** The old brain. */
    OLD_BRAIN,
    /** Anything else: timers (stairs, kept table, furnace pickup, defend hold), side machines, guests, unknown callers. */
    OTHER;

    /** True when this driver may start or stop a job while AA is on. */
    public boolean passesUnderAa() { return this == AA || this == OWNER || this == REFLEX; }

    /** Short lower-case name for status lines ("old brain"). */
    public String label() { return name().toLowerCase().replace('_', ' '); }
}
