package com.zakdev.data.jpaanalyzer.analyzer;

import com.zakdev.data.jpaanalyzer.model.ConfigInfo;
import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;

import java.util.Map;

/**
 * Analyzes a project's merged Spring/Hibernate configuration (the flattened,
 * dot-notation view of application.properties / application.yml) for settings
 * that hurt JPA performance or correctness. Keys are matched case-insensitively
 * and across the common Spring Boot and raw-Hibernate spellings.
 */
public class ConfigAnalyzer {

    /** @param props flattened config: dot-notation keys (lowercased) to string values. */
    public void analyze(Map<String, String> props, ConfigInfo ci) {
        checkOpenSessionInView(props, ci);
        checkBatchInserts(props, ci);
        checkOrderedStatements(props, ci);
        checkSqlLogging(props, ci);
        checkDdlAuto(props, ci);
    }

    private void checkOpenSessionInView(Map<String, String> props, ConfigInfo ci) {
        String osiv = get(props, "spring.jpa.open-in-view");
        // Spring Boot enables OSIV by default; absence is as bad as an explicit true.
        if (osiv == null || isTrue(osiv)) {
            ci.addIssue(new Issue(
                IssueSeverity.WARNING,
                "OPEN_SESSION_IN_VIEW",
                osiv == null
                    ? "spring.jpa.open-in-view is unset, so Spring Boot enables Open Session In View by default; it holds a DB connection for the whole request and hides lazy-loading N+1s in the view layer."
                    : "spring.jpa.open-in-view=true holds a DB connection for the whole request and hides lazy-loading N+1s in the view layer.",
                "Set spring.jpa.open-in-view=false and fetch what the view needs inside the service/transaction."
            ));
        }
    }

    private void checkBatchInserts(Map<String, String> props, ConfigInfo ci) {
        String batch = get(props,
                "spring.jpa.properties.hibernate.jdbc.batch_size",
                "hibernate.jdbc.batch_size");
        int size = parseInt(batch, 0);
        if (size <= 1) {
            ci.addIssue(new Issue(
                IssueSeverity.WARNING,
                "NO_BATCH_INSERTS",
                batch == null
                    ? "hibernate.jdbc.batch_size is unset, so JDBC insert/update batching is disabled and every row is a separate round-trip."
                    : "hibernate.jdbc.batch_size=" + batch + " disables effective JDBC batching; every row is a separate round-trip.",
                "Set spring.jpa.properties.hibernate.jdbc.batch_size to 20-50 to batch inserts/updates."
            ));
        }
    }

    private void checkOrderedStatements(Map<String, String> props, ConfigInfo ci) {
        String batch = get(props,
                "spring.jpa.properties.hibernate.jdbc.batch_size",
                "hibernate.jdbc.batch_size");
        if (parseInt(batch, 0) <= 1) return; // only relevant once batching is on

        boolean orderInserts = isTrue(get(props,
                "spring.jpa.properties.hibernate.order_inserts", "hibernate.order_inserts"));
        boolean orderUpdates = isTrue(get(props,
                "spring.jpa.properties.hibernate.order_updates", "hibernate.order_updates"));
        if (!orderInserts || !orderUpdates) {
            ci.addIssue(new Issue(
                IssueSeverity.INFO,
                "BATCH_WITHOUT_ORDERED_STATEMENTS",
                "JDBC batching is enabled but hibernate.order_inserts/order_updates are not both true, so statements across different tables are not grouped and batches stay small.",
                "Set hibernate.order_inserts=true and hibernate.order_updates=true so Hibernate groups batchable statements."
            ));
        }
    }

    private void checkSqlLogging(Map<String, String> props, ConfigInfo ci) {
        boolean showSql = isTrue(get(props, "spring.jpa.show-sql"))
                || isTrue(get(props, "spring.jpa.properties.hibernate.show_sql", "hibernate.show_sql"));
        if (showSql) {
            ci.addIssue(new Issue(
                IssueSeverity.WARNING,
                "SQL_LOGGING_ENABLED",
                "show-sql=true writes every statement to System.out synchronously, which is unstructured and a real throughput hit under load.",
                "Disable show-sql and use a logger (org.hibernate.SQL=DEBUG) or datasource-proxy/p6spy for SQL you can filter."
            ));
        }
    }

    private void checkDdlAuto(Map<String, String> props, ConfigInfo ci) {
        String ddl = get(props, "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto", "hibernate.hbm2ddl.auto");
        if (ddl == null) return;
        String v = ddl.trim().toLowerCase();
        if (v.equals("create") || v.equals("create-drop")) {
            ci.addIssue(new Issue(
                IssueSeverity.CRITICAL,
                "DDL_AUTO_RISK",
                "ddl-auto=" + v + " drops and recreates the schema on startup, destroying data — fatal if it ever reaches a real environment.",
                "Use 'validate' (or 'none') in production and manage schema with a migration tool like Flyway or Liquibase."
            ));
        } else if (v.equals("update")) {
            ci.addIssue(new Issue(
                IssueSeverity.WARNING,
                "DDL_AUTO_RISK",
                "ddl-auto=update mutates the live schema from entity mappings; it never drops columns, drifts from intent, and is unsafe to trust in production.",
                "Use 'validate' (or 'none') in production and manage schema with a migration tool like Flyway or Liquibase."
            ));
        }
    }

    private static String get(Map<String, String> props, String... keys) {
        for (String k : keys) {
            String v = props.get(k.toLowerCase());
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean isTrue(String v) {
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    private static int parseInt(String v, int fallback) {
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
