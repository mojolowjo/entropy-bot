package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.Step;

/**
 * rm-execution2: the owner verb each typed step runs, built from the step's fields here on the game side (never from its
 * text(), which is for logs only). Pure (no game classes): JUnit checks every step AA emits has a verb.
 */
public final class StepCommands {
    private StepCommands() {}

    // ---- rm-execution2: the owner verb each step runs (built from the step's fields here, the game side; never its text()) ----

    /** The command a step runs through its job; the walks, trees, collects, hunts, builds, the climb, fishing and kelp cuts have hands of their own. */
    public static String command(Step s) {
        return switch (s) {
            case Step.Walk w -> "goto " + w.x() + " " + w.y() + " " + w.z();
            case Step.MineAt m -> "mine " + m.family() + " 1 at " + m.x() + " " + m.y() + " " + m.z() + (m.dig() ? " dig" : " walk");
            case Step.Mine m -> switch (m.how()) {
                case KNOWN -> "mine " + m.family() + " " + m.n();
                case DIG -> "mine " + m.family() + " " + m.n() + " dig";
                case CAVE -> "mine cave " + m.family() + " " + m.n();
                case STRIP -> "mine strip " + m.family() + " " + m.n();
            };
            case Step.ChopTree t -> "cut " + t.logs() + (t.log() == null ? "" : " " + t.log());
            case Step.HuntAt h -> "hunt " + h.n() + " " + h.type();
            case Step.Build b -> "place " + b.items().get(0);
            case Step.Craft k -> "craft " + k.item() + " " + k.n();
            case Step.Smelt k -> "smelt " + k.item() + " " + k.n() + (k.here() ? " here" : "");
            case Step.Cook k -> "cook " + k.item() + " " + k.n();
            case Step.Collect k -> "gather " + k.item() + " " + k.n();
            case Step.Fetch k -> "get " + k.item() + " " + k.n();
            case Step.SmeltCollect k -> "smelt collect all";
            case Step.Shelter k -> "shelter";
            case Step.Wait k -> "wait " + k.seconds();
            case Step.Climb k -> "climb";
            case Step.Scan k -> k.base() ? "scan base" : "scan";
            case Step.FindCave k -> "find cave " + k.minutes();
            case Step.Explore k -> "explore " + k.minutes() + (k.until() == null ? "" : " until " + k.until());
            case Step.Dig d -> "dig " + d.from()[0] + " " + d.from()[1] + " " + d.from()[2] + " " + d.to()[0] + " " + d.to()[1] + " " + d.to()[2]
                    + (d.ores() ? " ores" : "") + (d.viaEngine() ? " via engine" : "");
            case Step.MarkMine k -> "place mine " + (k.at() == null ? "" : k.at()[0] + " " + k.at()[1] + " " + k.at()[2] + " ") + k.dir();
            case Step.Restore k -> k.keep() ? "restore keep pillar" : "restore placed " + k.radius();
            case Step.Deposit k -> "deposit";
            case Step.DropJunk k -> "drop " + k.item() + " " + k.n();
            case Step.PlaceNear k -> "place " + k.item();
            case Step.MarkBase k -> "place base " + k.x() + " " + k.y() + " " + k.z();
            case Step.Sleep k -> "sleep";
            case Step.Burrow k -> "burrow";
            case Step.Flee k -> "flee";
            case Step.Attack k -> "attack nearest";
            case Step.Pillar k -> "pillar";
            case Step.GoBase k -> "go base";
            case Step.House k -> "house " + k.area();
            case Step.Come k -> "come";
            case Step.Give k -> "give me " + k.item() + " " + k.n();
            case Step.Light k -> "light here " + k.radius();
            case Step.Fish k -> throw new IllegalStateException("a fish step has hands of its own (cast and reel on Baritone's look), no verb");
            case Step.KelpCut k -> throw new IllegalStateException("a kelp cut has hands of its own (Baritone's mine process), no verb");
            case Step.Kelp k -> throw new IllegalStateException("a compound step is expanded by Steps, never run by the hands");
            case Step.Eat k -> "eat";
            case Step.DigArea k -> "dig " + k.box()[0] + " " + k.box()[1] + " " + k.box()[2] + " " + k.box()[3] + " +" + k.up();
            case Step.FarmPlant k -> "farm plant " + k.crop() + " here " + k.n();
            case Step.MarkFarm k -> "place farm" + (k.at() == null ? "" : " " + k.at()[0] + " " + k.at()[1] + " " + k.at()[2]);
            case Step.BuildSchematic k -> "build schematic " + k.name() + " " + k.x() + " " + k.y() + " " + k.z();
            case Step.Plan k -> throw new IllegalStateException("a plan step is expanded by Steps, never run by the hands");
            case Step.StripLeg k -> throw new IllegalStateException("a compound step is expanded by Steps, never run by the hands");
            case Step.ClearChunk k -> throw new IllegalStateException("a compound step is expanded by Steps, never run by the hands");
        };
    }

}
