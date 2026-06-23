package com.zakdev.data.jpaanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EntityInfo {
    private final String sourceFile;
    private final String className;
    private final List<Issue> issues = new ArrayList<>();

    public EntityInfo(String sourceFile, String className) {
        this.sourceFile = sourceFile;
        this.className = className;
    }

    public void addIssue(Issue issue) { issues.add(issue); }
    public void removeIssues(java.util.function.Predicate<Issue> p) { issues.removeIf(p); }
    public boolean hasIssues() { return !issues.isEmpty(); }

    public String getSourceFile() { return sourceFile; }
    public String getClassName() { return className; }
    public List<Issue> getIssues() { return Collections.unmodifiableList(issues); }

    public long countBySeverity(IssueSeverity severity) {
        return issues.stream().filter(i -> i.getSeverity() == severity).count();
    }
}
