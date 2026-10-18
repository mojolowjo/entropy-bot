package io.github.mojolowjo.entropybot.ai.live;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.ai.AiLog;
import io.github.mojolowjo.entropybot.ai.AiLoop;
import io.github.mojolowjo.entropybot.ai.AiSettings;
import io.github.mojolowjo.entropybot.ai.Budget;
import io.github.mojolowjo.entropybot.ai.CommandGate;
import io.github.mojolowjo.entropybot.ai.KeyStore;
import io.github.mojolowjo.entropybot.ai.PromptBuilder;
import io.github.mojolowjo.entropybot.ai.Redact;
import io.github.mojolowjo.entropybot.ai.Secret;
import io.github.mojolowjo.entropybot.ai.UsageLedger;
import io.github.mojolowjo.entropybot.ai.provider.JdkTransport;
import io.github.mojolowjo.entropybot.ai.provider.Provider;
import io.github.mojolowjo.entropybot.ai.provider.ProviderException;
import io.github.mojolowjo.entropybot.ai.provider.Providers;
import io.github.mojolowjo.entropybot.commands.Chains;
import io.github.mojolowjo.entropybot.commands.Commands;
import io.github.mojolowjo.entropybot.commands.JobRequests;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import io.github.mojolowjo.entropybot.commands.Texts;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 0.35.0: the AI operator's thin game adapter. On the game tick (from {@code Commands.tick}, so no new event
 * listener): hands answers from the worker to {@link AiLoop}, runs the chosen command through
 * {@code Commands.handle} as the owner (every guard, the fence and the confirm gate stay on), and sends the next
 * request on its own single daemon thread {@code entropybot-ai} (never the game or render thread). The owner's chat
 * reaches it through {@code Commands.processPms} and the {@code ai} verb.
 *
 * <p>Loader API: none of its own; it uses {@code Minecraft.getInstance()} (vanilla) for the game folder, the player's
 * name and death. Everything that decides is in the loader-neutral {@code ai} package.
 */
public final class AiRuntime {
    public static final AiRuntime INSTANCE = new AiRuntime();
    private static final Logger LOG = LogUtils.getLogger();
    private static final JdkTransport HTTP = JdkTransport.missing() == null ? new JdkTransport() : null;

    private record Result(long gen, Provider.Completion completion, ProviderException error) {}

    private Path dir;
    private AiSettings settings = new AiSettings();
    private String settingsError, startError;
    private KeyStore keys;
    private UsageLedger ledger;
    private AiLog log;
    private AiLoop loop;
    private Provider provider;
    private Commands cmds;
    private boolean started, calling;
    private long generation;
    private int errors;
    private ExecutorService worker;
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private volatile String testResult;
    private volatile boolean testing;

    private AiRuntime() {}

    // ---- loading ----

    private static Path botDir() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("entropybot");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void ensureLoaded() {
        if (dir != null) return;
        dir = botDir();
        if (dir == null) return;
        reload();
    }

    /** Re-reads ai.json and the files; the loop starts again from OFF (then on if ai.json says enabled). */
    public void reload() {
        if (dir == null) dir = botDir();
        if (dir == null) return;
        AiSettings.Loaded l = AiSettings.load(dir.resolve("ai.json"));
        settings = l.settings();
        settingsError = l.error();
        keys = new KeyStore(dir.resolve("ai-keys.json"), System::getenv);
        ledger = new UsageLedger(dir.resolve("ai-usage.json"), ZoneId.systemDefault());
        log = new AiLog(dir.resolve("ai"), ZoneId.systemDefault(), this::secrets);
        loop = null;
        started = false;
        LOG.info("[entropybot] ai: {}", readyLine());
        if (settingsError != null) LOG.warn("[entropybot] ai: {}", settingsError);
    }

    private Collection<Secret> secrets() {
        List<Secret> l = new ArrayList<>();
        if (keys != null) for (String p : List.of("openrouter", "openai", "anthropic", "local")) {
            Secret k = keys.key(p);
            if (!k.isEmpty()) l.add(k);
        }
        return l;
    }

    private String scrub(String s) { return Redact.scrub(s, secrets()); }

    /** The ready line's ai part: "off" or "on provider=... model=... key=set (…ab12)". Never the key. */
    public String readyLine() {
        if (settingsError != null) return "off (" + settingsError + ")";
        if (!settings.enabled) return "off";
        return "on provider=" + settings.provider + " model=" + settings.model + " key=" + (keys == null ? "?" : keys.source(settings.provider))
                + (HTTP == null ? " http client MISSING" : "");
    }

    /** Builds the loop and the provider and switches it on (in a world: the prompt names the bot). */
    private void start(long now) {
        startError = null;
        List<String> p = settings.problems();
        CommandGate gate = new CommandGate(settings.extraAllow, settings.extraDeny);
        String bot = Minecraft.getInstance().player == null ? "the bot" : Minecraft.getInstance().player.getGameProfile().getName();
        PromptBuilder prompt = new PromptBuilder(PromptBuilder.bundledRules(), gate, settings.promptSize.equals("full"), bot, cmds == null ? null : cmds.owner());
        loop = new AiLoop(settings, gate, prompt, new Budget(settings, ledger), sink());
        if (settingsError != null) startError = settingsError;
        else if (!p.isEmpty()) startError = "settings: " + String.join("; ", p);
        else if (HTTP == null && !settings.provider.equals("demo")) startError = JdkTransport.missing();
        else {
            try {
                provider = Providers.create(settings, keys.key(settings.provider), HTTP);
                final UsageLedger lg = ledger;
                if (provider instanceof io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider cp)
                    cp.onRetry(() -> lg.record(System.currentTimeMillis(), 0, 0, 1));     // 0.35.1: each retry is a request
            } catch (ProviderException e) {
                startError = scrub(e.reason());
            }
        }
        if (startError != null) {
            log.line(now, "can't start: " + startError);
            return;                                     // the loop stays OFF; ai status says why
        }
        loop.enable(now);
        if (worker == null) worker = newWorker();
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "entropybot-ai");
            t.setDaemon(true);
            return t;
        });
    }

    private AiLoop.Sink sink() {
        return new AiLoop.Sink() {
            @Override
            public void notice(String text) {
                if (cmds != null) cmds.aiNotice(scrub(text));
            }

            @Override
            public void log(long now, String line) { log.line(now, line); }

            @Override
            public void trial(long now, String wanted, String command, String answer) {
                if (settings.trialNotes) log.trial(now, wanted, command, answer);
            }
        };
    }

    public boolean on() { return loop != null && loop.on(); }

    // ---- the tick (game thread) ----

    /** From Commands.tick, in a world. Never throws. */
    public void tick(Commands c) {
        try {
            cmds = c;
            ensureLoaded();
            if (dir == null) return;
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                if (settings.enabled) start(now);
            }
            if (loop == null || !loop.on()) return;
            Result r;
            while ((r = results.poll()) != null) {
                if (r.gen() != generation) continue;               // from a thread the watchdog dropped
                if (r.error() != null) loop.onFailure(r.error(), now);
                else {
                    String cmd = loop.onCompletion(r.completion(), now);
                    if (cmd != null) run(c, cmd, now);
                }
            }
            if (loop.watchdog(now)) {
                generation++;
                worker.shutdownNow();
                worker = newWorker();
            }
            Minecraft mc = Minecraft.getInstance();
            boolean dead = mc.player == null || mc.player.isDeadOrDying();
            Provider.Request req = loop.poll(now, new AiLoop.World(mc.player != null, c.aiBusy(), dead));
            if (req != null) send(req, now);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] ai tick error #{}: {}", errors, scrub(t.toString()));
        }
    }

    private void send(Provider.Request req, long now) {
        final long gen = generation;
        final Provider p = provider;
        if (settings.logPrompts) log.line(now, "prompt: " + PromptBuilder.estimate(req) + " tokens (system " + req.system().length() + " chars, "
                + req.messages().size() + " messages): " + req.messages().get(req.messages().size() - 1).content());
        try {
            worker.submit(() -> {
                try {
                    results.add(new Result(gen, p.complete(req), null));
                } catch (ProviderException e) {
                    results.add(new Result(gen, null, e));
                } catch (Throwable t) {
                    results.add(new Result(gen, null, new ProviderException(ProviderException.Kind.BAD_RESPONSE, 0, "the AI worker failed: " + scrub(t.toString()))));
                }
            });
        } catch (RejectedExecutionException e) {
            worker = newWorker();
            loop.onFailure(new ProviderException(ProviderException.Kind.NETWORK, 0, "the AI worker was not running"), now);
        }
    }

    /** Runs the model's command as the owner, exactly like the bridge (internal = false). */
    private void run(Commands c, String cmd, long now) {
        calling = true;
        try {
            final String line = cmd;
            JobRequests.Listener l = req -> {
                try {
                    if (!JobRequests.quiet(req.doneMsg) && loop != null) loop.onJobEnd(c.aiShape(line, req.doneMsg), System.currentTimeMillis());
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] ai job end: {}", e.toString());
                }
            };
            Chains.Reply rep = c.handle(c.owner(), cmd, false, l);
            boolean job = rep != null && rep.pending() != null;
            String text = rep == null ? null : rep.text();
            String shaped = text == null || text.isBlank() ? (job ? "started" : "ok") : c.aiShape(cmd, text);
            loop.onCommandResult(cmd, shaped, job, now);
        } catch (RuntimeException e) {
            loop.onCommandResult(cmd, "error: " + e, false, now);
        } finally {
            calling = false;
        }
    }

    // ---- the owner's chat ----

    /** An owner's PM: true when the AI took it as a request (on, and the first word is no command). */
    public boolean ownerText(Commands c, String body, boolean force) {
        try {
            cmds = c;
            if (!on() || calling) return false;
            String w = Texts.verbAndRest(body)[0];
            if (!force && c.aiKnownWord(w)) return false;
            long now = System.currentTimeMillis();
            if (!loop.ownerRequest(body, now)) return false;
            c.aiNotice("on it" + (force ? " (I read that as a request for the AI; ai stop stops me)" : ""));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] ai owner text: {}", e.toString());
            return false;
        }
    }

    /** The owner's plain stop: the AI pauses until the next request. Returns the note for the stop reply ("" when not on). */
    public String ownerStopped() {
        if (calling || !on()) return "";
        loop.ownerStop(System.currentTimeMillis());
        return " (AI operator paused until your next request)";
    }

    // ---- the ai verb ----

    public String command(Commands c, String rest) {
        cmds = c;
        ensureLoaded();
        if (dir == null) return "error: no game folder";
        long now = System.currentTimeMillis();
        String r = rest == null ? "" : rest.trim();
        String[] vr = Texts.verbAndRest(r);
        String sub = vr[0], arg = vr[1].trim();
        switch (sub) {
            case "", "status" -> { return status(now); }
            case "on" -> {
                if (settingsError != null) return "error: " + settingsError;
                settings.enabled = true;
                String se = saveSettings();
                start(now);
                if (startError != null) return "error: the AI operator can't start: " + startError + (se == null ? "" : "; " + se);
                return "ok: AI operator on (" + provider.describe() + ", key " + keys.source(settings.provider) + "). Tell me what to do: ai <request>"
                        + (se == null ? "" : "; " + se);
            }
            case "off" -> {
                settings.enabled = false;
                String se = saveSettings();
                if (loop != null) loop.disable(now);
                return "ok: AI operator off" + (se == null ? "" : "; " + se);
            }
            case "stop" -> {
                if (!on()) return "the AI operator is off";
                loop.stopByOwner(now);
                if (arg.equalsIgnoreCase("all")) {
                    calling = true;
                    try {
                        c.handle(c.owner(), "stop", false, null);
                    } finally {
                        calling = false;
                    }
                    return "ok: AI stopped and the bot stopped (ai on or your next request starts the AI again)";
                }
                return "ok: AI stopped (the bot's job goes on; ai stop all stops both; ai on or your next request starts it again)";
            }
            case "log" -> {
                int n = 5;
                try {
                    if (!arg.isEmpty()) n = Math.max(1, Math.min(20, Integer.parseInt(arg)));
                } catch (NumberFormatException e) {
                    return "usage: ai log [n]";
                }
                List<String> l = log.last(n);
                return l.isEmpty() ? "ai log: nothing yet (file " + dir.resolve("ai").resolve("ai.log") + ")" : String.join("\n", l);
            }
            case "reload" -> {
                if (loop != null) loop.disable(now);
                reload();
                started = true;
                if (settings.enabled) start(now);
                return "ok: ai.json read again - " + (on() ? "on" : "off" + (startError != null ? ": " + startError : ""));
            }
            case "test" -> {
                testConnection(settings.copy(), null);
                return "testing the connection... (ai test result in a moment: ai status)";
            }
            case "goal", "say" -> {
                if (arg.isEmpty()) return "usage: ai " + sub + " <what to do>";
                return request(c, arg, now);
            }
            case "help" -> { return "usage: ai on|off|status|stop [all]|log [n]|test|reload|<request>"; }
            default -> { return request(c, r, now); }
        }
    }

    private String request(Commands c, String text, long now) {
        if (!on()) return "the AI operator is off - ai on first (or Mods > Entropy Bot > Config > AI operator)" + (startError != null ? "; last error: " + startError : "");
        loop.ownerRequest(text, now);
        return "ok: on it: " + (text.length() > 80 ? text.substring(0, 80) + "..." : text) + " (ai status shows how it goes; ai stop stops it)";
    }

    private String saveSettings() {
        try {
            settings.save(dir.resolve("ai.json"));
            return null;
        } catch (IOException e) {
            return "couldn't save ai.json: " + e.getMessage();
        }
    }

    public String status(long now) {
        List<String> l = new ArrayList<>();
        if (loop != null) l.addAll(loop.status(now));
        else l.add("AI operator: off" + (settingsError != null ? " - " + settingsError : ""));
        if (startError != null) l.add("can't start: " + startError);
        l.add("provider: " + settings.provider + " " + settings.model + (settings.baseUrl.isEmpty() ? "" : " at " + settings.baseUrl) + "; paid models "
                + (settings.paid ? String.format(Locale.ROOT, "on ($%.2f/day)", settings.usdPerDay) : "off") + "; autonomous " + (settings.autonomous ? "on" : "off"));
        l.add("key: " + (keys == null ? "?" : keys.source(settings.provider)) + (keys != null && keys.permsNote() != null ? "; key file permissions: " + keys.permsNote() : "")
                + "; http client " + (HTTP == null ? "MISSING" : "ok"));
        if (ledger != null && ledger.saveError() != null) l.add(ledger.saveError());
        if (ledger != null && ledger.corrupt() != null) l.add("caps closed: " + ledger.corrupt());
        if (log != null && log.error() != null) l.add(log.error());
        String t = testResult;
        if (t != null) l.add("last test: " + t);
        return scrub(String.join("\n", l));
    }

    // ---- check ----

    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> f = new ArrayList<>();
        try {
            if (settingsError != null) f.add(new SelfCheck.Finding("ai-settings", settingsError, "fix or delete entropybot/ai.json, then ai reload"));
            if (!settings.enabled) return f;
            if (HTTP == null && !settings.provider.equals("demo")) f.add(new SelfCheck.Finding("ai-http", JdkTransport.missing(), "use a full Java 21 runtime"));
            if (keys != null && keys.source(settings.provider).equals("missing")) f.add(new SelfCheck.Finding("ai-key", "the AI operator is on but has no API key",
                    "Mods > Entropy Bot > Config > AI operator: type the key"));
            if (startError != null) f.add(new SelfCheck.Finding("ai-start", "the AI operator can't start: " + scrub(startError), "ai status, fix the setting, ai on"));
            if (loop != null && loop.state() == AiLoop.State.STOPPED && loop.stopReason() != null) {
                String why = scrub(loop.stopReason());
                f.add(new SelfCheck.Finding(why.startsWith("cap:") ? "ai-cap" : why.contains("invalid") || why.contains("idle") ? "ai-model" : "ai-stopped",
                        "the AI operator stopped: " + why, "ai log 10, then ai on (or a new request)"));
            }
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("ai-check", "couldn't check the AI operator: " + e, "ai status"));
        }
        return f;
    }

    // ---- the screen ----

    public AiSettings settingsCopy() {
        ensureLoaded();
        return settings.copy();
    }

    public String settingsError() { return settingsError; }

    public String keySource(String provider) {
        ensureLoaded();
        return keys == null ? "?" : keys.source(provider);
    }

    public String shortStatus() {
        if (loop == null) return startError != null ? "off: " + scrub(startError) : settings.enabled ? "on (starts in a world)" : "off";
        return scrub(loop.shortLine()) + " | " + (ledger == null ? "" : new Budget(settings, ledger).spentLine(System.currentTimeMillis()));
    }

    /** Saves the screen's settings (and a new key when typed); null when fine, else the reason. */
    public String saveFromScreen(AiSettings s, String newKey) {
        ensureLoaded();
        if (dir == null) return "no game folder";
        List<String> p = s.problems();
        if (!p.isEmpty()) return String.join("; ", p);
        try {
            if (newKey != null && !newKey.isBlank()) {
                String note = keys.set(s.provider, newKey);
                if (note != null) LOG.warn("[entropybot] ai: key file permissions {}", note);
            }
            s.save(dir.resolve("ai.json"));
        } catch (IOException e) {
            return "couldn't save: " + e.getMessage();
        }
        long now = System.currentTimeMillis();
        if (loop != null) loop.disable(now);
        settings = s;
        settingsError = null;
        loop = null;
        started = true;
        if (settings.enabled && Minecraft.getInstance().player != null) start(now);
        else started = false;                              // starts on the first tick in a world
        log.line(now, "settings saved from the screen: " + readyLine());
        return null;
    }

    public boolean testing() { return testing; }

    public String testResult() { return testResult; }

    /** One tiny request off the game thread ("reply with the word ok"); the result shows on the screen and in ai.log. */
    public void testConnection(AiSettings s, String typedKey) {
        ensureLoaded();
        if (testing) return;
        testing = true;
        testResult = "testing...";
        Secret key = typedKey != null && !typedKey.isBlank() ? Secret.of(typedKey) : keys.key(s.provider);
        final UsageLedger lg = ledger;
        Thread t = new Thread(() -> {
            String res;
            try {
                if (HTTP == null && !s.provider.equals("demo")) throw new ProviderException(ProviderException.Kind.CONFIG, 0, JdkTransport.missing());
                // 0.35.1 review: the test obeys the free/paid rule and the caps, and is counted
                Budget b = new Budget(s, lg);
                String sp = b.startProblem();
                if (sp != null) throw new ProviderException(ProviderException.Kind.CONFIG, 0, sp);
                Provider.Request rq = new Provider.Request("Answer with one word.", List.of(new Provider.Message("user", "reply with the word ok")),
                        16, 0, Duration.ofSeconds(s.timeoutSeconds()));
                long est = PromptBuilder.estimate(rq);
                Budget.Cap cap = b.refuse(System.currentTimeMillis(), est);
                if (cap != null) throw new ProviderException(ProviderException.Kind.CONFIG, 0, cap.reason());
                Provider p = Providers.create(s, key, HTTP);
                lg.record(System.currentTimeMillis(), est, 0, 1);
                Provider.Completion c = p.complete(rq);
                lg.record(System.currentTimeMillis(), c.inTokens() + c.outTokens() - est, b.cost(c.costUsd(), c.inTokens(), c.outTokens()), 0);
                String text = c.text() == null ? "" : c.text().trim();
                res = String.format(Locale.ROOT, "ok in %.1f s, %d tokens, $%.4f: \"%s\"", c.millis() / 1000.0, c.inTokens() + c.outTokens(),
                        c.costUsd() == null ? 0 : c.costUsd(), text.length() > 20 ? text.substring(0, 20) + "..." : text);
            } catch (ProviderException e) {
                res = "failed: " + e.reason();
            } catch (Throwable e) {
                res = "failed: " + e;
            }
            res = Redact.scrub(res, List.of(key));
            testResult = res;
            testing = false;
            if (log != null) log.line(System.currentTimeMillis(), "test connection (" + s.provider + " " + s.model + "): " + res);
        }, "entropybot-ai-test");
        t.setDaemon(true);
        t.start();
    }

    public Path folder() { return dir; }
}
