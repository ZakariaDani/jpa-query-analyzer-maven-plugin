package com.zakdev.data.jpaanalyzer.model;

public final class Issue {
    private final IssueSeverity severity;
    private final String ruleId;
    private final String message;
    private final String tip;

    public Issue(IssueSeverity severity, String ruleId, String message, String tip) {
        this.severity = severity;
        this.ruleId = ruleId;
        this.message = message;
        this.tip = tip;
    }

    public IssueSeverity getSeverity() { return severity; }
    public String getRuleId() { return ruleId; }
    public String getMessage() { return message; }
    public String getTip() { return tip; }
}
