package com.zakdev.data.jpaanalyzer.reporter;

import com.zakdev.data.jpaanalyzer.model.Finding;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;
import com.zakdev.data.jpaanalyzer.model.ScanReport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a {@link ScanReport} as SARIF 2.1.0, the format GitHub code scanning ingests
 * (Security tab + inline PR annotations). Severity maps CRITICAL→error, WARNING→warning,
 * INFO→note. Findings without a real source line (entities, config) are anchored at line 1.
 */
public class SarifReporter {

    private static final String TOOL = "jpa-query-analyzer";
    private static final String INFO_URI = "https://github.com/zakdev/jpa-query-analyzer-maven-plugin";

    public String render(ScanReport r) {
        List<Finding> findings = r.findings();

        // SARIF wants the set of rule descriptors that results reference, deduplicated by id.
        Set<String> ruleIds = new LinkedHashSet<>();
        findings.forEach(f -> ruleIds.add(f.ruleId()));

        StringBuilder b = new StringBuilder(512 + findings.size() * 192);
        b.append("{\n");
        b.append("  \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n");
        b.append("  \"version\": \"2.1.0\",\n");
        b.append("  \"runs\": [\n    {\n");
        b.append("      \"tool\": {\n        \"driver\": {\n");
        b.append("          \"name\": \"").append(TOOL).append("\",\n");
        b.append("          \"informationUri\": \"").append(INFO_URI).append("\",\n");
        b.append("          \"rules\": [");
        int ri = 0;
        for (String id : ruleIds) {
            b.append(ri++ == 0 ? "\n" : ",\n");
            b.append("            { \"id\": \"").append(Json.esc(id))
             .append("\", \"shortDescription\": { \"text\": \"").append(Json.esc(id)).append("\" } }");
        }
        b.append(ruleIds.isEmpty() ? "]\n" : "\n          ]\n");
        b.append("        }\n      },\n");

        b.append("      \"results\": [");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            String text = f.message() + (f.fix() == null || f.fix().isBlank() ? "" : " Fix: " + f.fix());
            b.append(i == 0 ? "\n" : ",\n");
            b.append("        {\n");
            b.append("          \"ruleId\": \"").append(Json.esc(f.ruleId())).append("\",\n");
            b.append("          \"level\": \"").append(level(f.severity())).append("\",\n");
            b.append("          \"message\": { \"text\": \"").append(Json.esc(text)).append("\" },\n");
            b.append("          \"locations\": [\n");
            b.append("            {\n              \"physicalLocation\": {\n");
            b.append("                \"artifactLocation\": { \"uri\": \"").append(Json.esc(f.file())).append("\" },\n");
            b.append("                \"region\": { \"startLine\": ").append(Math.max(1, f.line())).append(" }\n");
            b.append("              }\n            }\n");
            b.append("          ]\n");
            b.append("        }");
        }
        b.append(findings.isEmpty() ? "]\n" : "\n      ]\n");
        b.append("    }\n  ]\n}\n");
        return b.toString();
    }

    private static String level(IssueSeverity s) {
        return switch (s) {
            case CRITICAL -> "error";
            case WARNING  -> "warning";
            case INFO     -> "note";
        };
    }
}
