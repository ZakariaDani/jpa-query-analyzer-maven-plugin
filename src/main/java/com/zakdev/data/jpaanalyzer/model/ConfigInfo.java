package com.zakdev.data.jpaanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds JPA / Hibernate configuration findings extracted from a project's
 * application.properties / application.yml (merged across profiles). Mirrors
 * {@link EntityInfo} so config issues flow through the same Issue/Reporter
 * pipeline as entity and query issues.
 */
public class ConfigInfo {
    private final String sourceFile;
    private final List<Issue> issues = new ArrayList<>();

    public ConfigInfo(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public void addIssue(Issue issue) { issues.add(issue); }
    public void removeIssues(java.util.function.Predicate<Issue> p) { issues.removeIf(p); }
    public boolean hasIssues() { return !issues.isEmpty(); }

    public String getSourceFile() { return sourceFile; }
    public List<Issue> getIssues() { return Collections.unmodifiableList(issues); }

    public long countBySeverity(IssueSeverity severity) {
        return issues.stream().filter(i -> i.getSeverity() == severity).count();
    }
}
