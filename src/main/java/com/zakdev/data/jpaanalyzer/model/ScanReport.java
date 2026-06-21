package com.zakdev.data.jpaanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScanReport {
    private final List<QueryInfo> queries = new ArrayList<>();
    private final List<EntityInfo> entities = new ArrayList<>();
    private int scannedFiles;
    private int repositoryCount;
    private int entityCount;

    public void addQuery(QueryInfo q) { queries.add(q); }
    public void addEntity(EntityInfo e) { entities.add(e); }
    public void incrementScannedFiles() { scannedFiles++; }
    public void incrementRepositoryCount() { repositoryCount++; }
    public void incrementEntityCount() { entityCount++; }

    public List<QueryInfo> getQueries() { return Collections.unmodifiableList(queries); }
    public List<EntityInfo> getEntities() { return Collections.unmodifiableList(entities); }
    public int getScannedFiles() { return scannedFiles; }
    public int getRepositoryCount() { return repositoryCount; }
    public int getEntityCount() { return entityCount; }

    public long countQueryIssuesBySeverity(IssueSeverity severity) {
        return queries.stream()
                .flatMap(q -> q.getIssues().stream())
                .filter(i -> i.getSeverity() == severity)
                .count();
    }

    public long countEntityIssuesBySeverity(IssueSeverity severity) {
        return entities.stream()
                .flatMap(e -> e.getIssues().stream())
                .filter(i -> i.getSeverity() == severity)
                .count();
    }

    public long totalCritical() {
        return countQueryIssuesBySeverity(IssueSeverity.CRITICAL)
                + countEntityIssuesBySeverity(IssueSeverity.CRITICAL);
    }

    public long totalWarnings() {
        return countQueryIssuesBySeverity(IssueSeverity.WARNING)
                + countEntityIssuesBySeverity(IssueSeverity.WARNING);
    }

    public long totalInfo() {
        return countQueryIssuesBySeverity(IssueSeverity.INFO)
                + countEntityIssuesBySeverity(IssueSeverity.INFO);
    }

    public long totalIssues() {
        return totalCritical() + totalWarnings() + totalInfo();
    }

    public long countByType(QueryType type) {
        return queries.stream().filter(q -> q.getType() == type).count();
    }

    public boolean isEmpty() {
        return queries.isEmpty() && entities.isEmpty();
    }
}
