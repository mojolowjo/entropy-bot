package io.github.mojolowjo.entropybot.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperatorViewTest {
    @Test
    void shapesAFailureWithOptions() {
        ReplyShaper s = new ReplyShaper();
        String r = s.shape(null, "house: stopped at step 5 (build roof cobblestone house_main): 4 cells left, e.g. -69 61 -12 (nowhere to stand)"
                + " - next: dig a step next to -69 61 -12 or place it by hand", "-68 60 -11, in house_main (main)", List.of("zombie 8 W"));
        assertTrue(r.startsWith("RESULT  house: stopped at step 5"), r);
        assertTrue(r.contains("\nHERE    -68 60 -11, in house_main"), r);
        assertTrue(r.contains("\nNEW     zombie 8 W"), r);
        assertTrue(r.contains("OPTIONS 1) dig a step next to -69 61 -12  2) place it by hand  3) why"), r);
        assertTrue(s.why(System.currentTimeMillis()).contains("place it by hand"));
    }

    @Test
    void foldsRepeatsAndNeverHidesAFailure() {
        ReplyShaper s = new ReplyShaper();
        String a = "error: couldn't get there (5 blocks away, stopped at 1 2 3)";
        s.shape("goto", a, null, null);
        String b = s.shape("goto", a, null, null);
        assertTrue(b.contains("same as last time (x2): error: couldn't get there"), b);
        assertTrue(b.contains("1) why"), b);
        assertTrue(ReplyShaper.failure("stopped: low health"));
        assertTrue(ReplyShaper.failure("house: stopped at step 3"));
        assertFalse(ReplyShaper.failure("ok: going to 1 2 3"));
    }

    @Test
    void budgetCutsWithAPointer() {
        ReplyShaper s = new ReplyShaper();
        String r = s.shape(null, "ok: " + "x".repeat(900), "here", null);
        assertTrue(r.length() <= ReplyShaper.BUDGET + 20, String.valueOf(r.length()));
        assertTrue(r.contains("(more: why cut)"), r);
        assertTrue(s.why(0, "cut").contains("x".repeat(900)));
        assertEquals("nothing has gone wrong yet", s.why(0).startsWith("no failure yet") ? "nothing has gone wrong yet" : s.why(0));   // an ok cut is no failure
    }

    @Test
    void passesListsAndInfoVerbs() {
        ReplyShaper s = new ReplyShaper();
        assertEquals("a\nb", s.shape(null, "a\nb", "h", null));
        assertEquals("areas: none", s.shape("area", "areas: none", "h", null));
        assertEquals("nothing has gone wrong yet", new ReplyShaper().why(0));
    }

    @Test
    void parenthesisedNext() {
        java.util.List<String> o = new java.util.ArrayList<>();
        String r = ReplyShaper.split("I can't see the ground (not loaded - next: goto 3 ~ 4, then house h) here", o)[0];
        assertEquals(List.of("goto 3 ~ 4, then house h"), o);
        assertEquals("I can't see the ground (not loaded here", r);
    }

    @Test
    void outerNextWins() {
        java.util.List<String> o = new java.util.ArrayList<>();
        ReplyShaper.split("error: gather: 0/4 (last: chop: no trees; next: give me an axe, or put a cr...) - next: \"area here 48 woods neutral\" where trees grow, or put some oak_log in the base chests", o);
        assertEquals(List.of("\"area here 48 woods neutral\" where trees grow", "put some oak_log in the base chests"), o);
    }

    @Test
    void reviewFixes() {
        ReplyShaper s = new ReplyShaper();
        java.util.concurrent.atomic.AtomicInteger taken = new java.util.concurrent.atomic.AtomicInteger();
        s.shapeLazy("look", "map", null, () -> { taken.incrementAndGet(); return List.of("x"); });
        assertEquals(0, taken.get());                                // a PASS reply leaves the news
        assertTrue(ReplyShaper.failure("blocked by water at 1 2 3"));
        assertTrue(ReplyShaper.failure("usage: goto x y z"));
        assertTrue(ReplyShaper.failure("dig: blocked by water at 1 2 3 - next: dig ... water"));
        s.shape("goto", "ok: going to 1 2 3", null, null);
        assertFalse(s.shape("come", "ok: going to 1 2 3", null, null).contains("same as last time"));   // other verb
        s.shape("status", "x", null, null);
        assertFalse(s.shape("come", "ok: going to 1 2 3", null, null).contains("same as last time"));   // reset by another reply
        assertEquals("where: 1 2 3", s.shape("where", "where: 1 2 3", "h", null));
    }

    @Test
    void p1Shapes() {
        ReplyShaper s = new ReplyShaper();
        assertEquals("that is now cut <n>", s.shape("chop", "that is now cut <n>", "h", null));
        assertEquals("usage: goto x y z", s.shape("goto", "usage: goto x y z", "h", null));
        String r = s.shape("chop", "error: chopped nothing - next: \"area here 48 woods neutral\" where trees grow, or put some oak_log in the base chests", "1 2 3", null);
        assertFalse(r.contains("<r>"), r);
        assertTrue(r.contains("1) \"area here 48 woods neutral\" where trees grow  2) put some oak_log in the base chests"), r);     // 0.35.2: a real hint is an option now
        assertEquals("error: x | at 1 2 3 | next: 1) why", ReplyShaper.oneLine(new ReplyShaper().shape(null, "error: x", "1 2 3", null)));
    }

    @Test
    void newsHighestFirstOnce() {
        News n = new News();
        n.push(News.TIME, "night fell");
        n.push(News.DEATH, "I died at 1 2 3");
        n.push(News.TIME, "night fell");
        assertEquals(List.of("I died at 1 2 3", "night fell"), n.take());
        assertTrue(n.take().isEmpty());
    }

    private static LookMap.Cells flat(int groundY) {
        return new LookMap.Cells() {
            @Override public char top(int x, int z) { return x > 2 ? '?' : x == -2 ? '~' : '.'; }
            @Override public int topY(int x, int z) { return z == -3 ? groundY + 3 : groundY; }
            @Override public char at(int x, int y, int z) { return y < groundY ? '#' : x == 1 && y == groundY ? 'o' : '.'; }
        };
    }

    @Test
    void overheadMap() {
        String m = LookMap.overhead(flat(63), 0, 0, 64, 3, new int[]{0, 64, 0}, List.of(new int[]{1, 64, 1}), "around me");
        String[] l = m.split("\n");
        assertEquals("around me: x -3..3, z -3..3 (north up, me at y 64)", l[0]);
        assertEquals("^~^^^^?", l[1]);                     // z -3: 3 higher
        assertEquals(".~.@..?", l[4]);
        assertEquals(".~..z.?", l[5]);
        assertTrue(l[l.length - 1].contains("? not loaded"));
    }

    @Test
    void undergroundSlices() {
        String m = LookMap.slices(flat(64), 0, 64, 0, 2, 1, List.of());
        assertTrue(m.contains("side north-south"), m);
        assertTrue(m.contains("..@.."), m);
        assertTrue(m.contains("#####"), m);
        assertTrue(m.contains(". air  # solid  o ore"), m);
    }
}
