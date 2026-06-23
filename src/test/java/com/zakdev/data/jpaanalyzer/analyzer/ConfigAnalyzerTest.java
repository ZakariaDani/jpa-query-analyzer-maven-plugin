package com.zakdev.data.jpaanalyzer.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.zakdev.data.jpaanalyzer.analyzer.TestSupport.configRules;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigAnalyzer")
class ConfigAnalyzerTest {

    @Test
    @DisplayName("flags Open Session In View when unset or explicitly true, not when false")
    void openSessionInView() {
        assertTrue(configRules(Map.of()).contains("OPEN_SESSION_IN_VIEW"));
        assertTrue(configRules(Map.of("spring.jpa.open-in-view", "true")).contains("OPEN_SESSION_IN_VIEW"));
        assertFalse(configRules(Map.of("spring.jpa.open-in-view", "false")).contains("OPEN_SESSION_IN_VIEW"));
    }

    @Test
    @DisplayName("flags missing/disabled JDBC batching, not a real batch size")
    void batchInserts() {
        assertTrue(configRules(Map.of()).contains("NO_BATCH_INSERTS"));
        assertTrue(configRules(Map.of("spring.jpa.properties.hibernate.jdbc.batch_size", "1"))
                .contains("NO_BATCH_INSERTS"));
        assertFalse(configRules(Map.of("spring.jpa.properties.hibernate.jdbc.batch_size", "30"))
                .contains("NO_BATCH_INSERTS"));
    }

    @Test
    @DisplayName("flags batching without ordered statements, only once batching is on")
    void orderedStatements() {
        assertTrue(configRules(Map.of("spring.jpa.properties.hibernate.jdbc.batch_size", "30"))
                .contains("BATCH_WITHOUT_ORDERED_STATEMENTS"));
        assertFalse(configRules(Map.of(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "30",
                "spring.jpa.properties.hibernate.order_inserts", "true",
                "spring.jpa.properties.hibernate.order_updates", "true"))
                .contains("BATCH_WITHOUT_ORDERED_STATEMENTS"));
        // No batching configured: the ordered-statements rule should stay quiet.
        assertFalse(configRules(Map.of()).contains("BATCH_WITHOUT_ORDERED_STATEMENTS"));
    }

    @Test
    @DisplayName("flags show-sql when enabled, not when off or absent")
    void sqlLogging() {
        assertTrue(configRules(Map.of("spring.jpa.show-sql", "true")).contains("SQL_LOGGING_ENABLED"));
        assertFalse(configRules(Map.of("spring.jpa.show-sql", "false")).contains("SQL_LOGGING_ENABLED"));
        assertFalse(configRules(Map.of()).contains("SQL_LOGGING_ENABLED"));
    }

    @Test
    @DisplayName("flags risky ddl-auto values, not validate/none")
    void ddlAuto() {
        assertTrue(configRules(Map.of("spring.jpa.hibernate.ddl-auto", "create")).contains("DDL_AUTO_RISK"));
        assertTrue(configRules(Map.of("spring.jpa.hibernate.ddl-auto", "create-drop")).contains("DDL_AUTO_RISK"));
        assertTrue(configRules(Map.of("spring.jpa.hibernate.ddl-auto", "update")).contains("DDL_AUTO_RISK"));
        assertFalse(configRules(Map.of("spring.jpa.hibernate.ddl-auto", "validate")).contains("DDL_AUTO_RISK"));
        assertFalse(configRules(Map.of("spring.jpa.hibernate.ddl-auto", "none")).contains("DDL_AUTO_RISK"));
    }

    @Test
    @DisplayName("a well-tuned config raises nothing")
    void cleanConfigIsSilent() {
        assertTrue(configRules(Map.of(
                "spring.jpa.open-in-view", "false",
                "spring.jpa.properties.hibernate.jdbc.batch_size", "30",
                "spring.jpa.properties.hibernate.order_inserts", "true",
                "spring.jpa.properties.hibernate.order_updates", "true",
                "spring.jpa.show-sql", "false",
                "spring.jpa.hibernate.ddl-auto", "validate")).isEmpty());
    }
}
