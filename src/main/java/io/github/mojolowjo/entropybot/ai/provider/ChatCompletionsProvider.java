package io.github.mojolowjo.entropybot.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.ai.Redact;
import io.github.mojolowjo.entropybot.ai.Secret;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.35.0: the OpenAI-compatible chat completions wire format: OpenRouter, any OpenAI-compatible service, and local
 * servers (Ollama, LM Studio). Plain text (no tools in v1). Retries 429 / 5xx / network errors twice (2 s, then 8 s,
 * Retry-After honoured up to 60 s); other 4xx never retry. Loader-neutral: Gson + an injected {@link HttpTransport}.
 */
public final class ChatCompletionsProvider implements Provider {
    /** Sleeps between retries (a fake in tests). */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    public static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";
    public static final String LOCAL_BASE = "http://127.0.0.1:11434/v1";
    static final int MAX_BODY = 64 * 1024;
    static final long[] BACKOFF_MS = {2000, 8000};
    static final long RETRY_AFTER_CAP_MS = 60_000;

    private final String kind, base, model;
    private final Secret key;
    private final HttpTransport http;
    private final Sleeper sleeper;

    public ChatCompletionsProvider(String kind, String baseUrl, String model, Secret key, HttpTransport http, Sleeper sleeper) throws ProviderException {
        this.kind = kind;
        String b = baseUrl == null || baseUrl.isBlank() ? (kind.equals("local") ? LOCAL_BASE : kind.equals("openrouter") ? OPENROUTER_BASE : "") : baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        if (b.isEmpty()) throw new ProviderException(ProviderException.Kind.CONFIG, 0, "set the base URL for an OpenAI-compatible service (e.g. https://api.openai.com/v1)");
        String why = urlProblem(b);
        if (why != null) throw new ProviderException(ProviderException.Kind.CONFIG, 0, why);
        if (model == null || model.isBlank()) throw new ProviderException(ProviderException.Kind.CONFIG, 0, "set a model id");
        if (key.isEmpty() && !kind.equals("local") && !isLoopback(b))
            throw new ProviderException(ProviderException.Kind.CONFIG, 0, "no API key (type one in the AI screen, or set the environment variable)");
        this.base = b;
        this.model = model.trim();
        this.key = key;
        this.http = http;
        this.sleeper = sleeper;
    }

    /** null when the URL is fine; a remote http:// is refused (the key would travel in clear). */
    public static String urlProblem(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "the base URL is not a URL: " + e.getMessage();
        }
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (u.getHost() == null) return "the base URL has no host";
        if (s.equals("https")) return null;
        if (s.equals("http")) return isLoopback(url) ? null : "http:// is only allowed for this computer (127.0.0.1/localhost); use https://";
        return "the base URL must start with https://";
    }

    public static boolean isLoopback(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1") || h.startsWith("127.");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String describe() { return kind + " " + model + (kind.equals("openrouter") ? "" : " at " + base); }

    String body(Request r) {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", r.system());
        msgs.add(sys);
        for (Message m : r.messages()) {
            JsonObject j = new JsonObject();
            j.addProperty("role", m.role());
            j.addProperty("content", m.content());
            msgs.add(j);
        }
        o.add("messages", msgs);
        o.addProperty("max_tokens", r.maxTokens());
        o.addProperty("temperature", r.temperature());
        o.addProperty("stream", false);
        if (kind.equals("openrouter")) {
            JsonObject u = new JsonObject();
            u.addProperty("include", true);
            o.add("usage", u);
        }
        return o.toString();
    }

    Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        if (!key.isEmpty()) h.put("Authorization", "Bearer " + key.reveal());
        if (kind.equals("openrouter")) {
            h.put("HTTP-Referer", "https://github.com/mojolowjo/entropy-bot");
            h.put("X-Title", "Entropy Bot");
        }
        return h;
    }

    @Override
    public Completion complete(Request r) throws ProviderException {
        String url = base + "/chat/completions";
        String body = body(r);
        long t0 = System.currentTimeMillis();
        ProviderException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            long wait = attempt == 0 ? 0 : BACKOFF_MS[attempt - 1];
            HttpTransport.Response resp;
            try {
                if (last != null && last.retryAfterMs > 0) wait = Math.max(wait, Math.min(RETRY_AFTER_CAP_MS, last.retryAfterMs));
                if (wait > 0) sleeper.sleep(wait);
                resp = http.post(url, headers(), body, r.timeout());
            } catch (HttpTimeoutException e) {
                last = new Retryable(ProviderException.Kind.TIMEOUT, 0, "no answer within " + r.timeout().toSeconds() + " s", 0);
                continue;
            } catch (IOException e) {
                last = new Retryable(ProviderException.Kind.NETWORK, 0, "the AI service is not answering (" + scrub(e.toString()) + ")", 0);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException(ProviderException.Kind.TIMEOUT, 0, "interrupted");
            }
            int st = resp.status();
            if (st >= 200 && st < 300) return parse(resp.body(), System.currentTimeMillis() - t0);
            String why = errorText(resp.body());
            if (st == 429 || st >= 500) {
                last = new Retryable(st == 429 ? ProviderException.Kind.RATE : ProviderException.Kind.SERVER, st,
                        (st == 429 ? "rate limited" : "the service failed") + (why.isEmpty() ? "" : ": " + why), retryAfter(resp.headers()));
                continue;
            }
            throw new ProviderException(kindOf(st), st, explain(st) + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        throw new ProviderException(last.kind, last.status, last.getMessage() + " (after " + BACKOFF_MS.length + " retries)");
    }

    /** A failure worth retrying, with its Retry-After. */
    private static final class Retryable extends ProviderException {
        Retryable(Kind k, int st, String m, long retryAfterMs) {
            super(k, st, m);
            this.retryAfterMs = retryAfterMs;
        }
    }

    static ProviderException.Kind kindOf(int st) {
        return switch (st) {
            case 401, 403 -> ProviderException.Kind.AUTH;
            case 402 -> ProviderException.Kind.NO_CREDIT;
            case 404 -> ProviderException.Kind.BAD_MODEL;
            default -> ProviderException.Kind.REFUSED;
        };
    }

    static String explain(int st) {
        return switch (st) {
            case 401, 403 -> "the key was refused";
            case 402 -> "no credit left on this account";
            case 404 -> "no such model or URL (check the model id)";
            case 400 -> "the service refused the request";
            default -> "the service refused the request";
        };
    }

    static long retryAfter(Map<String, String> h) {
        String v = h == null ? null : h.get("retry-after");
        if (v == null) return 0;
        try {
            return (long) (Double.parseDouble(v.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String scrub(String s) { return Redact.scrub(s, List.of(key)); }

    private String errorText(String body) {
        if (body == null || body.isBlank()) return "";
        String b = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        String msg;
        try {
            JsonElement e = JsonParser.parseString(b);
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : null;
            JsonElement err = o == null ? null : o.get("error");
            if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) msg = err.getAsJsonObject().get("message").getAsString();
            else if (err != null && err.isJsonPrimitive()) msg = err.getAsString();
            else msg = b;
        } catch (RuntimeException ex) {
            msg = b;
        }
        msg = scrub(msg.replaceAll("\\s+", " ").trim());
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    Completion parse(String body, long millis) throws ProviderException {
        if (body == null || body.isBlank()) throw new ProviderException(ProviderException.Kind.BAD_RESPONSE, 200, "an empty answer body");
        if (body.length() > MAX_BODY) throw new ProviderException(ProviderException.Kind.BAD_RESPONSE, 200, "the answer was over " + MAX_BODY / 1024 + " KB");
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error") && !o.has("choices")) throw new ProviderException(ProviderException.Kind.BAD_RESPONSE, 200, "error in the answer: " + errorText(body));
            JsonArray ch = o.has("choices") && o.get("choices").isJsonArray() ? o.getAsJsonArray("choices") : new JsonArray();
            String text = "", finish = null;
            if (!ch.isEmpty()) {
                JsonObject c0 = ch.get(0).getAsJsonObject();
                JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
                if (msg != null && msg.has("content") && !msg.get("content").isJsonNull()) text = msg.get("content").getAsString();
                if (c0.has("finish_reason") && !c0.get("finish_reason").isJsonNull()) finish = c0.get("finish_reason").getAsString();
            }
            long in = 0, out = 0;
            Double cost = null;
            if (o.has("usage") && o.get("usage").isJsonObject()) {
                JsonObject u = o.getAsJsonObject("usage");
                if (u.has("prompt_tokens") && !u.get("prompt_tokens").isJsonNull()) in = u.get("prompt_tokens").getAsLong();
                if (u.has("completion_tokens") && !u.get("completion_tokens").isJsonNull()) out = u.get("completion_tokens").getAsLong();
                if (u.has("cost") && !u.get("cost").isJsonNull()) cost = u.get("cost").getAsDouble();
            }
            return new Completion(text == null ? "" : text, in, out, cost, finish, millis);
        } catch (ProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderException(ProviderException.Kind.BAD_RESPONSE, 200, "the answer was not chat-completions JSON (" + e.getClass().getSimpleName() + ")");
        }
    }
}
