package io.github.mojolowjo.entropybot.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.35.0: the AI operator's settings, {@code <gameDir>/entropybot/ai.json}. Safe to paste in a bug report: the key is
 * never here (see {@link KeyStore}). Off by default in every install. A file that can't be read leaves the operator
 * OFF and says why ("fail closed"). Plain Java + Gson.
 */
public final class AiSettings {
    public static final Set<String> PROVIDERS = Set.of("openrouter", "openai", "local", "demo");
    public static final List<String> PROVIDER_ORDER = List.of("openrouter", "openai", "local", "demo");
    public static final String DEFAULT_MODEL = "nvidia/nemotron-3-super-120b-a12b:free";

    public boolean enabled = false;
    public String provider = "openrouter";
    public String model = DEFAULT_MODEL;
    /** For openai/local ("" = the default for local, required for openai). */
    public String baseUrl = "";
    /** Paid models allowed (off: only free models, token caps). */
    public boolean paid = false;
    public double usdPerDay = 0.50;
    public double usdPerHour = 0.15;
    /** 0 = no cap. */
    public long tokensPerDay = 200_000;
    public long tokensPerHour = 0;
    public int requestsPerHour = 120;
    public int maxOutputTokens = 120;
    public double temperature = 0.2;
    /** compact (name + usage, for small models) or full. */
    public String promptSize = "compact";
    /** After a finished goal: pick its own next goal (off: quiet until the owner speaks). */
    public boolean autonomous = false;
    public long minGapMs = 1500;
    /** 0 = auto (90 s remote, 180 s local). */
    public int timeoutSec = 0;
    public int maxFailures = 3;
    public boolean resumeAfterCap = false;
    public boolean resumeAfterOutage = true;
    public boolean trialNotes = true;
    public boolean logPrompts = false;
    public List<String> extraAllow = new ArrayList<>();
    public List<String> extraDeny = new ArrayList<>();
    /** USD per million tokens [in, out] by model id (only used when the service doesn't send the cost). */
    public Map<String, double[]> prices = new LinkedHashMap<>();
    /** The demo provider's replies. */
    public List<String> demoScript = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A loaded file: the settings, and why it could not be read (null: fine). */
    public record Loaded(AiSettings settings, String error) {}

    public int timeoutSeconds() {
        if (timeoutSec > 0) return timeoutSec;
        return provider.equals("local") ? 180 : 90;
    }

    /** Fills nulls (a hand-edited file) and clamps nothing silently: see {@link #problems}. */
    AiSettings normalise() {
        if (provider == null) provider = "openrouter";
        provider = provider.trim().toLowerCase(Locale.ROOT);
        if (model == null) model = "";
        model = model.trim();
        if (baseUrl == null) baseUrl = "";
        baseUrl = baseUrl.trim();
        if (promptSize == null) promptSize = "compact";
        if (extraAllow == null) extraAllow = new ArrayList<>();
        if (extraDeny == null) extraDeny = new ArrayList<>();
        if (prices == null) prices = new LinkedHashMap<>();
        if (demoScript == null) demoScript = new ArrayList<>();
        return this;
    }

    /** What is wrong (empty: fine). Each line is a plain reason shown in red on the screen. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (!PROVIDERS.contains(provider)) p.add("unknown provider " + provider + " (openrouter, openai, local, demo)");
        if (!provider.equals("demo") && model.isEmpty()) p.add("set a model id");
        if (usdPerDay < 0 || usdPerHour < 0) p.add("a USD cap can't be negative");
        if (tokensPerDay < 0 || tokensPerHour < 0) p.add("a token cap can't be negative (0 = no cap)");
        if (requestsPerHour < 0) p.add("requests per hour can't be negative");
        if (maxOutputTokens < 16 || maxOutputTokens > 4000) p.add("max output tokens must be 16..4000");
        if (minGapMs < 0) p.add("the gap between commands can't be negative");
        if (maxFailures < 1) p.add("max failures must be at least 1");
        if (!promptSize.equals("compact") && !promptSize.equals("full")) p.add("promptSize is compact or full");
        if (!baseUrl.isEmpty()) {
            String why = io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider.urlProblem(baseUrl);
            if (why != null) p.add(why);
        }
        if (provider.equals("openai") && baseUrl.isEmpty()) p.add("an OpenAI-compatible service needs its base URL");
        return p;
    }

    public static Loaded load(Path file) {
        if (file == null || !Files.exists(file)) return new Loaded(new AiSettings(), null);
        try {
            AiSettings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AiSettings.class);
            if (s == null) s = new AiSettings();
            return new Loaded(s.normalise(), null);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            AiSettings off = new AiSettings();
            off.enabled = false;
            return new Loaded(off, file.getFileName() + " can't be read, so the AI operator stays off: " + e.getMessage());
        }
    }

    /** Written to a temp file first, then moved over the old one. */
    public void save(Path file) throws IOException {
        normalise();
        writeAtomic(file, GSON.toJson(this));
    }

    public AiSettings copy() { return GSON.fromJson(GSON.toJson(this), AiSettings.class).normalise(); }

    static void writeAtomic(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
