package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * B7e package N (item 9): state.json's {@code settings.rules} for the dashboard's rules card:
 * {@code [{n, kind, arg, text, last, next, refused?}]} from commands.json's "rules" (Chains' list). {@code next} is
 * when an every/at rule is due next (epoch ms; due now when it is in the past), null for "when full" and "when idle"
 * (they wait for the bag or the idle time). Pure, the same sums as {@code Chains.rulesTick}.
 */
public final class RulesBlock {
    private RulesBlock() {}

    public static JsonArray build(JsonObject brain, long now, ZoneId zone) {
        JsonArray out = new JsonArray();
        if (brain == null || !brain.has("rules") || !brain.get("rules").isJsonArray()) return out;
        JsonArray list = brain.getAsJsonArray("rules");
        for (int i = 0; i < list.size(); i++) {
            JsonElement el = list.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject(), o = new JsonObject();
            String kind = str(r, "kind"), arg = str(r, "arg");
            long last = num(r, "last");
            o.addProperty("n", i + 1);
            o.addProperty("kind", kind);
            o.addProperty("arg", arg);
            o.addProperty("text", str(r, "text"));
            o.addProperty("last", last);
            Long next = next(kind, arg, last, now, zone);
            if (next == null) o.add("next", JsonNull.INSTANCE);
            else o.addProperty("next", next);
            if (r.has("refused")) o.addProperty("refused", str(r, "refused"));
            out.add(o);
        }
        return out;
    }

    /** When the rule is due next (epoch ms), or null when it waits for something other than the clock. */
    public static Long next(String kind, String arg, long last, long now, ZoneId zone) {
        try {
            if ("every".equals(kind)) {
                long n = Long.parseLong(arg.replaceAll("[^0-9]", "")) * (arg.endsWith("h") ? 3600000L : 60000L);
                return last + n;
            }
            if ("at".equals(kind)) {
                String[] hm = arg.split(":");
                ZonedDateTime day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).withHour(0).withMinute(0).withSecond(0).withNano(0);
                long today = day.plusHours(Integer.parseInt(hm[0])).plusMinutes(Integer.parseInt(hm[1])).toInstant().toEpochMilli();
                if (last < today) return today;                    // due today (or now, when it is past and hasn't run)
                return day.plusDays(1).plusHours(Integer.parseInt(hm[0])).plusMinutes(Integer.parseInt(hm[1])).toInstant().toEpochMilli();
            }
        } catch (RuntimeException ignored) {
            // a hand-edited rule: no time
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
