package com.zakdev.data.jpaanalyzer.reporter;

import com.zakdev.data.jpaanalyzer.model.Finding;
import com.zakdev.data.jpaanalyzer.model.QueryType;
import com.zakdev.data.jpaanalyzer.model.ScanReport;

import java.util.List;

/**
 * Renders a {@link ScanReport} as a stable JSON document (summary + findings array)
 * for consumption by dashboards, scripts, or other tooling.
 */
public class JsonReporter {

    public String render(ScanReport r) {
        List<Finding> findings = r.findings();
        StringBuilder b = new StringBuilder(256 + findings.size() * 128);
        b.append("{\n");
        b.append("  \"tool\": \"jpa-query-analyzer\",\n");

        b.append("  \"summary\": {\n");
        b.append("    \"scannedFiles\": ").append(r.getScannedFiles()).append(",\n");
        b.append("    \"repositories\": ").append(r.getRepositoryCount()).append(",\n");
        b.append("    \"entities\": ").append(r.getEntityCount()).append(",\n");
        b.append("    \"queries\": ").append(r.getQueries().size()).append(",\n");
        b.append("    \"namedQueries\": ").append(r.countByType(QueryType.NAMED_QUERY)).append(",\n");
        b.append("    \"critical\": ").append(r.totalCritical()).append(",\n");
        b.append("    \"warning\": ").append(r.totalWarnings()).append(",\n");
        b.append("    \"info\": ").append(r.totalInfo()).append(",\n");
        b.append("    \"total\": ").append(r.totalIssues()).append("\n");
        b.append("  },\n");

        b.append("  \"findings\": [");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            b.append(i == 0 ? "\n" : ",\n");
            b.append("    {\n");
            b.append("      \"severity\": \"").append(f.severity()).append("\",\n");
            b.append("      \"ruleId\": \"").append(Json.esc(f.ruleId())).append("\",\n");
            b.append("      \"file\": \"").append(Json.esc(f.file())).append("\",\n");
            b.append("      \"line\": ").append(f.line()).append(",\n");
            b.append("      \"location\": \"").append(Json.esc(f.location())).append("\",\n");
            b.append("      \"message\": \"").append(Json.esc(f.message())).append("\",\n");
            b.append("      \"fix\": \"").append(Json.esc(f.fix())).append("\",\n");
            b.append("      \"fingerprint\": \"").append(Json.esc(f.fingerprint())).append("\"\n");
            b.append("    }");
        }
        b.append(findings.isEmpty() ? "]\n" : "\n  ]\n");
        b.append("}\n");
        return b.toString();
    }
}
