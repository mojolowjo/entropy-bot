package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.assist.AssistRules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The owner's companion report (owner.json, the companion mod 0.3.1) for AA's help desire and inputs. It only reads; the
 * old assist verb that acted on it is gone (AA is the only driver). Loader notes: plain file reads. Never throws.
 */
final class OwnerReport {
    private AssistRules.Report report;
    private long reportMtime = Long.MIN_VALUE;

    /** The last report read (null none); the file is read again when it changed. */
    AssistRules.Report report() {
        try {
            if (Core.INSTANCE.files() == null) return report;
            Path f = Core.INSTANCE.files().root().resolve("owner.json");
            if (!Files.exists(f)) return report;
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m != reportMtime) {
                reportMtime = m;
                AssistRules.Report r = AssistRules.parse(Files.readString(f, StandardCharsets.UTF_8));
                if (r != null) report = r;
            }
        } catch (Exception e) {
            Quiet.warn("OwnerReport.report failed, carrying on with the last report", e);
        }
        return report;
    }
}
