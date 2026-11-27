package io.github.mojolowjo.entropybot.nav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** nav step 2: every NavConfig default equals the same-named setting's default in the fork jar's baritone.api.Settings. */
class NavConfigTest {
    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @Test
    void everyDefaultIsBaritones() throws Exception {
        Constructor<?> k = Class.forName("baritone.api.Settings").getDeclaredConstructor();
        k.setAccessible(true);
        Object settings = k.newInstance();
        NavConfig cfg = NavConfig.defaults();
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (RecordComponent group : NavConfig.class.getRecordComponents()) {
            Object g = group.getAccessor().invoke(cfg);
            for (RecordComponent c : g.getClass().getRecordComponents()) {
                n++;
                Field f;
                try {
                    f = settings.getClass().getField(c.getName());
                } catch (NoSuchFieldException e) {
                    bad.add(c.getName() + ": no such Baritone setting");
                    continue;
                }
                Object setting = f.get(settings);
                Object def = setting.getClass().getField("defaultValue").get(setting);
                Object ours = c.getAccessor().invoke(g);
                if (def instanceof List<?> l) def = List.copyOf(l);
                if (ours instanceof List<?> l) ours = List.copyOf(l);
                if (!def.equals(ours)) bad.add(c.getName() + ": Baritone " + def + ", ours " + ours);
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        assertEquals(62, n, "the planning half reads 62 settings (to-do/nav-steps-1-6.md step 2)");
    }
}
