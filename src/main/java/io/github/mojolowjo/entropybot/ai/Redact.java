package io.github.mojolowjo.entropybot.ai;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 0.35.0: every text that reaches a log, a whisper or the screen goes through here (keys never leave). Plain Java. */
public final class Redact {
    private Redact() {}

    private static final Pattern SK = Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{6,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[^\\s\"',}]+");
    private static final Pattern NAMED = Pattern.compile("(?i)(x-api-key|api[_-]?key|authorization)([\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]+");

    /** Replaces every known key and anything shaped like one. Never throws; null stays null. */
    public static String scrub(String text, Collection<Secret> keys) {
        if (text == null) return null;
        String t = text;
        if (keys != null) for (Secret k : keys) {
            if (k == null || k.isEmpty() || k.reveal().length() < 4) continue;
            t = t.replace(k.reveal(), "****");
        }
        t = SK.matcher(t).replaceAll("sk-****");
        t = BEARER.matcher(t).replaceAll("Bearer ****");
        Matcher m = NAMED.matcher(t);
        t = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + r.group(2) + "****"));
        return t;
    }
}
