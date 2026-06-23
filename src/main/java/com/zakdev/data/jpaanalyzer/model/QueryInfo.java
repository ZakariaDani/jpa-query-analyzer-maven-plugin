package com.zakdev.data.jpaanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueryInfo {
    private final String sourceFile;
    private final String className;
    private final String methodName;
    private final String queryString;   // null for derived methods
    private final QueryType type;
    private final int lineNumber;
    private final boolean hasPagination;
    private final String returnType;
    private final List<Issue> issues = new ArrayList<>();

    public QueryInfo(String sourceFile, String className, String methodName,
                     String queryString, QueryType type, int lineNumber,
                     boolean hasPagination, String returnType) {
        this.sourceFile = sourceFile;
        this.className = className;
        this.methodName = methodName;
        this.queryString = queryString;
        this.type = type;
        this.lineNumber = lineNumber;
        this.hasPagination = hasPagination;
        this.returnType = returnType;
    }

    public void addIssue(Issue issue) { issues.add(issue); }
    public void removeIssues(java.util.function.Predicate<Issue> p) { issues.removeIf(p); }
    public boolean hasIssues() { return !issues.isEmpty(); }

    public String getSourceFile() { return sourceFile; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getQueryString() { return queryString; }
    public QueryType getType() { return type; }
    public int getLineNumber() { return lineNumber; }
    public boolean hasPagination() { return hasPagination; }
    public String getReturnType() { return returnType; }
    public List<Issue> getIssues() { return Collections.unmodifiableList(issues); }

    public long countBySeverity(IssueSeverity severity) {
        return issues.stream().filter(i -> i.getSeverity() == severity).count();
    }
}
