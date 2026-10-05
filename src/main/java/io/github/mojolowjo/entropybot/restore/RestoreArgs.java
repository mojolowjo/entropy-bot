package io.github.mojolowjo.entropybot.restore;

/**
 * P1: the "restore" verb's words. {@code restore [status]}, {@code restore now [r]}, {@code restore forget <n>|all
 * confirm}, {@code restore ignore x y z}, {@code restore mode auto|manual|off}. Loader-neutral.
 */
public final class RestoreArgs {
    private RestoreArgs() {}

    public enum Kind { STATUS, NOW, FORGET, FORGET_ALL, IGNORE, MODE, USAGE }

    public record Cmd(Kind kind, int n, int[] pos, String mode, String error) {}

    public static final String USAGE = "usage: restore [status] | restore now [radius] | restore forget <n>|all confirm | restore ignore x y z | restore mode auto|manual|off";

    public static final int MAX_RADIUS = 64;

    public static Cmd parse(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.equals("status")) return new Cmd(Kind.STATUS, 0, null, null, null);
        String[] w = t.split(" ");
        switch (w[0]) {
            case "now" -> {
                if (w.length == 1) return new Cmd(Kind.NOW, RestorePlan.NOW_DEFAULT, null, null, null);
                if (w.length == 2 && w[1].matches("\\d{1,3}")) {
                    int r = Integer.parseInt(w[1]);
                    if (r < 1 || r > MAX_RADIUS) return usage("the radius is 1 to " + MAX_RADIUS);
                    return new Cmd(Kind.NOW, r, null, null, null);
                }
                return usage(null);
            }
            case "forget" -> {
                if (w.length >= 2 && w[1].equals("all")) {
                    if (w.length == 3 && w[2].equals("confirm")) return new Cmd(Kind.FORGET_ALL, 0, null, null, null);
                    return usage("forgetting all of them needs: restore forget all confirm");
                }
                if (w.length == 2 && w[1].matches("\\d{1,4}") && Integer.parseInt(w[1]) > 0) return new Cmd(Kind.FORGET, Integer.parseInt(w[1]), null, null, null);
                return usage(null);
            }
            case "ignore" -> {
                if (w.length == 4 && w[1].matches("-?\\d+") && w[2].matches("-?\\d+") && w[3].matches("-?\\d+")) {
                    return new Cmd(Kind.IGNORE, 0, new int[]{Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])}, null, null);
                }
                return usage(null);
            }
            case "mode" -> {
                if (w.length == 2 && w[1].matches("auto|manual|off")) return new Cmd(Kind.MODE, 0, null, w[1], null);
                return usage(null);
            }
            default -> { return usage(null); }
        }
    }

    /** "restore now" is a job (it walks and places); everything else answers at once. */
    public static boolean isJob(String rest) {
        return parse(rest).kind() == Kind.NOW;
    }

    private static Cmd usage(String why) {
        return new Cmd(Kind.USAGE, 0, null, null, why == null ? USAGE : "error: " + why);
    }
}
