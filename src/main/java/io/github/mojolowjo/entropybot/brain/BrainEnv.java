package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;

/**
 * B1: what the game gives the brain (the pattern of {@code commands.Chains.Env}, BRAIN_PLAN 1.3 / 4.6), so JUnit drives
 * {@link Brain} on fake states. The game side is {@code commands.BrainRuntime}.
 */
public interface BrainEnv {
    long now();

    long tick();

    /** The sensed snapshot (BRAIN_LOOP step 1); brainJob is filled by the brain itself. */
    BrainState sense();

    /** Starts a chain as the brain's job; the reply ("started: ..." on success). */
    String start(String chain);

    /** The brain's chain still runs. */
    boolean jobRunning();

    /** How the brain's last chain ended ("done ...", "stopped at step ..."), null when unknown. */
    String lastEnd();

    /** Stops the brain's chain only (the owner's jobs run on). */
    void stopJob(String why);

    void whisper(String text);

    void log(String line);

    /** commands.json's object (the brain keeps its settings under "brain"; needs, goals, released, sleepAuto beside it). */
    JsonObject store();

    void saved();

    /** brain.json for B4's page; failures are counted by the game side. */
    void writeState(JsonObject o);

    DecisionLog decisions();

    /** B3: the goal whose chain this is ended well (it leaves the goals list). */
    default void goalDone(String chain) {}

    /** B4: a file under entropybot/ (brain-config.json, brain-tree.override.json): its text, null when missing, "error: ..." when unreadable. */
    default String readFile(String name) { return null; }

    /** B4: writes a file under entropybot/ ("ok: ..." or "error: ..."). */
    default String writeFile(String name, String json) { return "error: no files"; }

    default String modVersion() { return "?"; }

    /**
     * 0.26.2: plans a way to get n of item with at most maxSteps steps, off the brain's tick (the planner's worker). then is
     * called later on the client thread with {chain, null} when found, {null, why} when not. False = no planner here (then
     * is never called).
     */
    default boolean planSubgoal(String item, int n, int maxSteps, java.util.function.Consumer<String[]> then) { return false; }

    /**
     * 0.26.3 the day plan: a dry run of {@code goal <form>} on the planner's worker. then gets {chain, null} (a plan; a
     * ready chain like camp or light at once), {"", why} (nothing to do: I have it) or {null, why} (no plan) on the client
     * thread. False = no planner here (then is never called).
     */
    default boolean planDay(String goal, java.util.function.Consumer<String[]> then) { return false; }

    /** 0.26.3: the brain's running chain's step ("3/7 craft iron_pickaxe 1"), null when none runs. */
    default String chainStep() { return null; }

    /** 0.26.3: a main area with dark cells (its name), null none or unknown. Called once per day plan selection (bounded sample). */
    default String darkMainArea() { return null; }
}
