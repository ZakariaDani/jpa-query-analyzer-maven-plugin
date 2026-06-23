package com.zakdev.data.jpaanalyzer.reporter;

import com.zakdev.data.jpaanalyzer.model.EntityInfo;
import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;
import com.zakdev.data.jpaanalyzer.model.ScanReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reporting (JSON / SARIF / baseline)")
class ReportingTest {

    private static ScanReport reportWith(String ruleId, IssueSeverity severity) {
        ScanReport r = new ScanReport();
        EntityInfo e = new EntityInfo("A.java", "A");
        e.addIssue(new Issue(severity, ruleId, "why text", "fix text"));
        r.addEntity(e);
        return r;
    }

    @Test
    @DisplayName("JSON output carries the rule id, severity, location and a fingerprint")
    void jsonOutput() {
        String json = new JsonReporter().render(reportWith("DEFAULT_EAGER_TO_ONE", IssueSeverity.WARNING));
        assertTrue(json.contains("\"ruleId\": \"DEFAULT_EAGER_TO_ONE\""), json);
        assertTrue(json.contains("\"severity\": \"WARNING\""), json);
        assertTrue(json.contains("\"file\": \"A.java\""), json);
        assertTrue(json.contains("\"fingerprint\": \"DEFAULT_EAGER_TO_ONE|A.java|entity A\""), json);
    }

    @Test
    @DisplayName("SARIF output is 2.1.0 and maps severity to a SARIF level")
    void sarifOutput() {
        String sarif = new SarifReporter().render(reportWith("NOT_IN_SUBQUERY", IssueSeverity.WARNING));
        assertTrue(sarif.contains("\"version\": \"2.1.0\""), sarif);
        assertTrue(sarif.contains("\"ruleId\": \"NOT_IN_SUBQUERY\""), sarif);
        assertTrue(sarif.contains("\"level\": \"warning\""), sarif);
        assertTrue(sarif.contains("\"startLine\": 1"), sarif); // entity finding anchored at line 1
    }

    @Test
    @DisplayName("SARIF maps CRITICAL to error and INFO to note")
    void sarifLevels() {
        assertTrue(new SarifReporter().render(reportWith("R", IssueSeverity.CRITICAL)).contains("\"level\": \"error\""));
        assertTrue(new SarifReporter().render(reportWith("R", IssueSeverity.INFO)).contains("\"level\": \"note\""));
    }

    @Test
    @DisplayName("a written baseline suppresses the same issue on a later run")
    void baselineRoundTrip(@TempDir Path dir) throws Exception {
        File bf = dir.resolve("baseline.json").toFile();
        Baseline.write(bf, reportWith("DEFAULT_EAGER_TO_ONE", IssueSeverity.WARNING));

        ScanReport later = reportWith("DEFAULT_EAGER_TO_ONE", IssueSeverity.WARNING);
        long suppressed = Baseline.apply(bf, later);

        assertEquals(1, suppressed);
        assertEquals(0, later.totalWarnings());
        assertTrue(later.findings().isEmpty());
    }

    @Test
    @DisplayName("a baseline only suppresses matching fingerprints")
    void baselineDoesNotSuppressNewIssues(@TempDir Path dir) throws Exception {
        File bf = dir.resolve("baseline.json").toFile();
        Baseline.write(bf, reportWith("OLD_RULE", IssueSeverity.WARNING));

        ScanReport later = reportWith("NEW_RULE", IssueSeverity.WARNING);
        long suppressed = Baseline.apply(bf, later);

        assertEquals(0, suppressed);
        assertEquals(1, later.totalWarnings());
    }
}
