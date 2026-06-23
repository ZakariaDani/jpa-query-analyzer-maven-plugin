package com.zakdev.data.jpaanalyzer.model;

/**
 * A flat view of a single {@link Issue} together with where it was found, used by
 * the machine-readable reporters (JSON / SARIF) and the baseline. {@link #fingerprint()}
 * is a line-independent identity so a baselined issue stays suppressed even when
 * surrounding code shifts its line number.
 */
public record Finding(IssueSeverity severity, String ruleId, String message, String fix,
                      String file, int line, String location) {

    public String fingerprint() {
        return fingerprint(file, location, ruleId);
    }

    public static String fingerprint(String file, String location, String ruleId) {
        return ruleId + "|" + file + "|" + location;
    }
}
