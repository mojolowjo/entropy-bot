package io.github.mojolowjo.entropybot.ai.provider;

import java.util.List;

/**
 * 0.35.0: a provider with no network: it answers the lines of a script in turn (then "wait" for ever). For the
 * screen's test button without a key, for screenshots and for the loop's tests. Costs nothing.
 */
public final class DemoProvider implements Provider {
    public static final List<String> DEFAULT_SCRIPT = List.of("status", "inv", "look", "finished");

    private final List<String> script;
    private int next;

    public DemoProvider(List<String> script) {
        this.script = script == null || script.isEmpty() ? DEFAULT_SCRIPT : List.copyOf(script);
    }

    @Override
    public synchronized Completion complete(Request r) {
        String last = r.messages().isEmpty() ? "" : r.messages().get(r.messages().size() - 1).content();
        if (last.contains("reply with the word ok")) return new Completion("ok", 0, 1, 0.0, "stop", 0);
        String t = next < script.size() ? script.get(next++) : "wait";
        return new Completion(t, 0, 0, 0.0, "stop", 0);
    }

    @Override
    public String describe() { return "demo (no network, " + script.size() + " scripted replies)"; }
}
