package com.zakdev.data.jpaanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ScanReport {
    private final List<QueryInfo> queries = new ArrayList<>();
    private final List<EntityInfo> entities = new ArrayList<>();
    private final List<ConfigInfo> configs = new ArrayList<>();
    private int scannedFiles;
    private int repositoryCount;
    private int entityCount;

    public void addQuery(QueryInfo q) { queries.add(q); }
    public void addEntity(EntityInfo e) { entities.add(e); }
    public void addConfig(ConfigInfo c) { configs.add(c); }
    public void incrementScannedFiles() { scannedFiles++; }
    public void incrementRepositoryCount() { repositoryCount++; }
    public void incrementEntityCount() { entityCount++; }

    public List<QueryInfo> getQueries() { return Collections.unmodifiableList(queries); }
    public List<EntityInfo> getEntities() { return Collections.unmodifiableList(entities); }
    public List<ConfigInfo> getConfigs() { return Collections.unmodifiableList(configs); }
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

    public long countConfigIssuesBySeverity(IssueSeverity severity) {
        return configs.stream()
                .flatMap(c -> c.getIssues().stream())
                .filter(i -> i.getSeverity() == severity)
                .count();
    }

    public long totalCritical() {
        return countQueryIssuesBySeverity(IssueSeverity.CRITICAL)
                + countEntityIssuesBySeverity(IssueSeverity.CRITICAL)
                + countConfigIssuesBySeverity(IssueSeverity.CRITICAL);
    }

    public long totalWarnings() {
        return countQueryIssuesBySeverity(IssueSeverity.WARNING)
                + countEntityIssuesBySeverity(IssueSeverity.WARNING)
                + countConfigIssuesBySeverity(IssueSeverity.WARNING);
    }

    public long totalInfo() {
        return countQueryIssuesBySeverity(IssueSeverity.INFO)
                + countEntityIssuesBySeverity(IssueSeverity.INFO)
                + countConfigIssuesBySeverity(IssueSeverity.INFO);
    }

    public long totalIssues() {
        return totalCritical() + totalWarnings() + totalInfo();
    }

    public long countByType(QueryType type) {
        return queries.stream().filter(q -> q.getType() == type).count();
    }

    public boolean isEmpty() {
        return queries.isEmpty() && entities.isEmpty() && configs.isEmpty();
    }

    /** Flattens every issue into a location-tagged {@link Finding}, for the JSON/SARIF reporters. */
    public List<Finding> findings() {
        List<Finding> out = new ArrayList<>();
        for (EntityInfo e : entities) {
            for (Issue i : e.getIssues()) {
                out.add(new Finding(i.getSeverity(), i.getRuleId(), i.getMessage(), i.getTip(),
                        e.getSourceFile(), 0, entityLocation(e)));
            }
        }
        for (QueryInfo q : queries) {
            for (Issue i : q.getIssues()) {
                out.add(new Finding(i.getSeverity(), i.getRuleId(), i.getMessage(), i.getTip(),
                        q.getSourceFile(), q.getLineNumber(), queryLocation(q)));
            }
        }
        for (ConfigInfo c : configs) {
            for (Issue i : c.getIssues()) {
                out.add(new Finding(i.getSeverity(), i.getRuleId(), i.getMessage(), i.getTip(),
                        c.getSourceFile(), 0, CONFIG_LOCATION));
            }
        }
        return out;
    }

    /**
     * Removes every issue whose fingerprint is in {@code fingerprints} (a baseline), so it is
     * excluded from both the report output and the build-failure tallies. Returns the count removed.
     */
    public long suppress(Set<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return 0;
        long before = totalIssues();
        entities.forEach(e -> e.removeIssues(i ->
                fingerprints.contains(Finding.fingerprint(e.getSourceFile(), entityLocation(e), i.getRuleId()))));
        queries.forEach(q -> q.removeIssues(i ->
                fingerprints.contains(Finding.fingerprint(q.getSourceFile(), queryLocation(q), i.getRuleId()))));
        configs.forEach(c -> c.removeIssues(i ->
                fingerprints.contains(Finding.fingerprint(c.getSourceFile(), CONFIG_LOCATION, i.getRuleId()))));
        return before - totalIssues();
    }

    private static final String CONFIG_LOCATION = "config";
    private static String entityLocation(EntityInfo e) { return "entity " + e.getClassName(); }
    private static String queryLocation(QueryInfo q) { return q.getMethodName() + "()"; }
}
