package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider;

import java.util.Locale;

/**
 * 0.35.0: the caps, checked before every request. Free models (the default): token and request caps only, and a model
 * that is not free is refused unless paid models are switched on; paid: USD per hour and per day too (default
 * $0.50/day). Plain Java.
 */
public final class Budget {
    /** A cap that was hit: day caps stop the operator, hour caps wait (the window rolls). */
    public record Cap(String reason, boolean hourly) {}

    /** A prompt estimated over this many tokens is refused (a runaway prompt). */
    public static final long MAX_INPUT_TOKENS = 30_000;

    private final AiSettings s;
    private final UsageLedger ledger;

    public Budget(AiSettings s, UsageLedger ledger) {
        this.s = s;
        this.ledger = ledger;
    }

    public UsageLedger ledger() { return ledger; }

    /** Whether the provider is free by its nature (local, demo, a loopback URL). */
    public static boolean freeService(AiSettings s) {
        return s.provider.equals("local") || s.provider.equals("demo") || (!s.baseUrl.isEmpty() && ChatCompletionsProvider.isLoopback(s.baseUrl));
    }

    /** null: the settings may run; else why not (checked when the operator starts and before each call). */
    public String startProblem() {
        if (freeService(s)) return null;
        if (!s.paid) {
            if (s.provider.equals("openrouter") && !s.model.toLowerCase(Locale.ROOT).endsWith(":free"))
                return "model " + s.model + " is not a free model (no :free at the end): pick a :free model, or switch paid models on";
            if (s.provider.equals("openai")) return "an OpenAI-compatible service costs money: switch paid models on (USD cap), or use local";
            return null;
        }
        if (s.usdPerDay <= 0 && s.usdPerHour <= 0) return "paid models are on but there is no USD cap: set USD per day";
        if (!s.provider.equals("openrouter") && price(s.model) == null)
            return "no price for " + s.model + " (this service doesn't send the cost): add it to ai.json prices, or use OpenRouter";
        return null;
    }

    double[] price(String model) {
        double[] p = s.prices.get(model);
        return p != null && p.length >= 2 ? p : null;
    }

    /** USD for one call: the service's cost when given, else the price table, else 0. */
    public double cost(Double reported, long in, long out) {
        if (reported != null) return reported;
        double[] p = price(s.model);
        return p == null ? 0 : (in * p[0] + out * p[1]) / 1_000_000.0;
    }

    /** null: the next call may go; else the cap that was hit. estimate: input + output tokens. */
    public Cap refuse(long now, long estimate) {
        if (estimate > MAX_INPUT_TOKENS) return new Cap("the prompt is too big (about " + estimate + " tokens)", false);
        if (s.requestsPerHour > 0 && ledger.hourRequests(now) >= s.requestsPerHour) return new Cap("cap: requests/hour " + s.requestsPerHour + " reached", true);
        if (s.tokensPerHour > 0 && ledger.hourTokens(now) + estimate > s.tokensPerHour) return new Cap("cap: tokens/hour " + s.tokensPerHour + " reached", true);
        if (s.tokensPerDay > 0 && ledger.dayTokens(now) + estimate > s.tokensPerDay) return new Cap("cap: tokens/day " + s.tokensPerDay + " reached", false);
        if (s.paid && !freeService(s)) {
            if (s.usdPerHour > 0 && ledger.hourUsd(now) >= s.usdPerHour) return new Cap(String.format(Locale.ROOT, "cap: usd/hour %.2f reached", s.usdPerHour), true);
            if (s.usdPerDay > 0 && ledger.dayUsd(now) >= s.usdPerDay) return new Cap(String.format(Locale.ROOT, "cap: usd/day %.2f reached", s.usdPerDay), false);
        }
        return null;
    }

    /** "today 12,345 of 200,000 tokens, 31 requests, $0.0000" */
    public String spentLine(long now) {
        String tok = s.tokensPerDay > 0 ? ledger.dayTokens(now) + " of " + s.tokensPerDay + " tokens" : ledger.dayTokens(now) + " tokens";
        String usd = s.paid ? String.format(Locale.ROOT, ", $%.4f of $%.2f", ledger.dayUsd(now), s.usdPerDay) : String.format(Locale.ROOT, ", $%.4f", ledger.dayUsd(now));
        return "today " + tok + ", " + ledger.dayRequests(now) + " requests" + usd + "; this hour " + ledger.hourRequests(now) + " requests, "
                + ledger.hourTokens(now) + " tokens";
    }
}
