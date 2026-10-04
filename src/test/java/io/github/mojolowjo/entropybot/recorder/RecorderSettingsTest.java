package io.github.mojolowjo.entropybot.recorder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.recorder.RecorderCommand.Cmd;
import io.github.mojolowjo.entropybot.recorder.RecorderCommand.Kind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecorderSettingsTest {

    @Test
    void normalIsTheDefault() {
        RecorderSettings s = new RecorderSettings();
        assertEquals("normal", s.preset);
        assertEquals(4, s.range);
        assertEquals(20, s.trailTicks);
        assertEquals(8, s.snapshot);
        assertFalse(s.states);
        assertEquals(48, s.keepHours);
        assertTrue(s.on());
        assertEquals("", s.changedFromPreset());
    }

    @Test
    void presetsSetTheirValuesAndOffRecordsNothing() {
        RecorderSettings s = new RecorderSettings();
        assertTrue(s.applyPreset("max"));
        assertEquals(1, s.trailTicks);
        assertTrue(s.states);
        assertTrue(s.applyPreset("light"));
        assertEquals(2, s.range);
        assertFalse(s.states);
        assertTrue(s.applyPreset("off"));
        assertFalse(s.on());
        assertFalse(s.applyPreset("loud"));
        assertEquals("off", s.preset);
        s.keepHours = 10;
        s.applyPreset("detailed");
        assertEquals(10, s.keepHours, "keep is not part of a preset");
    }

    @Test
    void singleValuesShowAsChangedFromThePreset() {
        RecorderSettings s = new RecorderSettings();
        assertTrue(s.apply(new Cmd(Kind.RANGE, null, 6), 0).startsWith("ok"));
        assertTrue(s.apply(new Cmd(Kind.STATES, null, 1), 0).startsWith("ok"));
        assertEquals("range 6, states on", s.changedFromPreset());
        s.apply(new Cmd(Kind.PRESET, "normal", 0), 0);
        assertEquals("", s.changedFromPreset());
    }

    @Test
    void aTimedBoostFallsBackByItselfAtItsTime() {
        RecorderSettings s = new RecorderSettings();
        s.apply(new Cmd(Kind.RANGE, null, 3), 0);
        long t0 = 1_000_000;
        String r = s.apply(new Cmd(Kind.BOOST, "detailed", 30), t0);
        assertTrue(r.startsWith("ok: recorder detailed until"), r);
        assertTrue(r.endsWith("then back to normal"), r);
        assertEquals("detailed", s.preset);
        assertTrue(s.states);
        assertEquals(t0 + 30 * 60_000L, s.boost.untilMs);
        assertNull(s.tick(t0 + 30 * 60_000L - 1), "not yet");
        assertEquals("detailed", s.preset);
        String note = s.tick(t0 + 30 * 60_000L);
        assertEquals("boost detailed ended: back to normal", note);
        assertNull(s.boost);
        assertEquals("normal", s.preset);
        assertEquals(3, s.range, "the owner's own value comes back too");
        assertFalse(s.states);
        assertNull(s.tick(t0 + 99 * 60_000L));
    }

    @Test
    void aSecondBoostKeepsTheFirstFallbackAndAPresetCancelsIt() {
        RecorderSettings s = new RecorderSettings();
        s.apply(new Cmd(Kind.PRESET, "light", 0), 0);
        s.apply(new Cmd(Kind.BOOST, "detailed", 10), 0);
        s.apply(new Cmd(Kind.BOOST, "max", 10), 60_000);
        assertEquals("max", s.preset);
        assertEquals("light", s.boost.then.preset, "falls back to what ran before the first boost");
        s.tick(60_000 + 10 * 60_000L);
        assertEquals("light", s.preset);

        s.apply(new Cmd(Kind.BOOST, "max", 10), 0);
        s.apply(new Cmd(Kind.PRESET, "normal", 0), 1000);
        assertNull(s.boost);
        assertNull(s.tick(10 * 60_000L));
        assertEquals("normal", s.preset);
    }

    @Test
    void jsonRoundTripKeepsTheBoostAndItsFallback() {
        RecorderSettings s = new RecorderSettings();
        s.apply(new Cmd(Kind.KEEP, null, 12), 0);
        s.apply(new Cmd(Kind.SNAPSHOT, null, 5), 0);
        s.apply(new Cmd(Kind.BOOST, "max", 60), 5000);
        JsonObject j = s.toJson();
        assertEquals("max", j.getAsJsonObject("boost").get("preset").getAsString());
        assertEquals("normal", j.getAsJsonObject("boost").get("then").getAsString());
        RecorderSettings back = RecorderSettings.fromJson(j.toString());
        assertEquals("max", back.preset);
        assertEquals(12, back.keepHours);
        assertEquals(5000 + 3_600_000L, back.boost.untilMs);
        back.tick(5000 + 3_600_000L);
        assertEquals("normal", back.preset);
        assertEquals(5, back.snapshot, "the fallback had the owner's snapshot 5");
    }

    @Test
    void badSettingsFilesGiveTheDefaults() {
        assertEquals("normal", RecorderSettings.fromJson(null).preset);
        assertEquals("normal", RecorderSettings.fromJson("{oops").preset);
        RecorderSettings s = RecorderSettings.fromJson("{\"preset\":\"loud\",\"range\":999,\"trailTicks\":0,\"keepHours\":\"x\"}");
        assertEquals("normal", s.preset);
        assertEquals(RecorderSettings.RANGE_MAX, s.range);
        assertEquals(1, s.trailTicks);
        assertEquals(48, s.keepHours);
    }

    @Test
    void stateJsonHasExactlyTheContractsShape() {
        RecorderSettings s = new RecorderSettings();
        JsonObject o = RecorderStatus.build(s, 123456, 3, "12:41 stuck at 260 -48 852");
        assertEquals(Set.of("preset", "range", "trailTicks", "snapshot", "states", "keepHours", "boost", "diskBytes", "incidents", "lastIncident"), o.keySet());
        assertTrue(o.get("boost").isJsonNull());
        assertEquals(123456, o.get("diskBytes").getAsLong());
        s.apply(new Cmd(Kind.BOOST, "detailed", 30), 1_759_600_000_000L - 30 * 60_000L);
        o = RecorderStatus.build(s, -1, 0, null);
        assertEquals(Set.of("preset", "untilMs", "then"), o.getAsJsonObject("boost").keySet());
        assertEquals(1_759_600_000_000L, o.getAsJsonObject("boost").get("untilMs").getAsLong());
        assertEquals("normal", o.getAsJsonObject("boost").get("then").getAsString());
        assertEquals(0, o.get("diskBytes").getAsLong());
        assertTrue(o.get("lastIncident").isJsonNull());
        assertNotNull(JsonParser.parseString(o.toString()));
        assertTrue(RecorderStatus.json() != null);
    }
}
