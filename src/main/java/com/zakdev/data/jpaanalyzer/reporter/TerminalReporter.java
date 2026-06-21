package com.zakdev.data.jpaanalyzer.reporter;

import com.zakdev.data.jpaanalyzer.model.EntityInfo;
import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;
import com.zakdev.data.jpaanalyzer.model.QueryInfo;
import com.zakdev.data.jpaanalyzer.model.QueryType;
import com.zakdev.data.jpaanalyzer.model.ScanReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the scan report to the terminal with ANSI colours and a small
 * analytics dashboard. Honours the NO_COLOR convention and non-tty output.
 */
public class TerminalReporter {

    private final boolean color;
    private static final int WIDTH = 70;

    private final String RESET, BOLD, DIM, RED, YELLOW, CYAN, GREEN, MAGENTA, BRED, BYEL, BCYAN;

    public TerminalReporter(boolean colorEnabled) {
        this.color = colorEnabled;
        RESET   = c("\u001B[0m");
        BOLD    = c("\u001B[1m");
        DIM     = c("\u001B[2m");
        RED     = c("\u001B[31m");
        YELLOW  = c("\u001B[33m");
        CYAN    = c("\u001B[36m");
        GREEN   = c("\u001B[32m");
        MAGENTA = c("\u001B[35m");
        BRED    = c("\u001B[1;31m");
        BYEL    = c("\u001B[1;33m");
        BCYAN   = c("\u001B[1;36m");
    }

    private String c(String code) { return color ? code : ""; }
    private void p(String s) { System.out.println(s); }
    private String bar(char ch, int n) { return String.valueOf(ch).repeat(Math.max(0, n)); }

    public void report(ScanReport report) {
        p("");
        header();
        summary(report);
        if (report.totalIssues() == 0) {
            p("  " + GREEN + BOLD + "No JPA performance issues detected. Nice." + RESET);
            p("");
            footer(report);
            return;
        }
        section("CRITICAL ISSUES", BRED, report, IssueSeverity.CRITICAL);
        section("WARNINGS", BYEL, report, IssueSeverity.WARNING);
        section("INFO", BCYAN, report, IssueSeverity.INFO);
        analytics(report);
        footer(report);
        p("");
    }

    private void header() {
        p(BOLD + CYAN + bar('=', WIDTH) + RESET);
        p(BOLD + CYAN + "   JPA Query Analyzer  -  Spring Boot Static Analysis" + RESET);
        p(BOLD + CYAN + bar('=', WIDTH) + RESET);
        p("");
    }

    private void summary(ScanReport r) {
        p(BOLD + "  SCAN SUMMARY" + RESET);
        p(DIM + "  " + bar('-', WIDTH - 2) + RESET);
        p(row("Files scanned", r.getScannedFiles(), "Repositories", r.getRepositoryCount()));
        p(row("Entities", r.getEntityCount(), "Queries total", r.getQueries().size()));
        p(row("JPQL", (int) r.countByType(QueryType.JPQL), "Native SQL", (int) r.countByType(QueryType.NATIVE_SQL)));
        p(row("Derived methods", (int) r.countByType(QueryType.SPRING_DATA_DERIVED),
              "Named queries", (int) r.countByType(QueryType.NAMED_QUERY)));
        p(DIM + "  " + bar('-', WIDTH - 2) + RESET);
        p("");
        long crit = r.totalCritical(), warn = r.totalWarnings(), info = r.totalInfo();
        String line = (crit > 0 ? BRED : DIM) + "  CRITICAL: " + crit + RESET
                + (warn > 0 ? BYEL : DIM) + "    WARNING: " + warn + RESET
                + BCYAN + "    INFO: " + info + RESET;
        p(line);
        p("");
    }

    private String row(String k1, int v1, String k2, int v2) {
        return String.format("  %-18s: %-6d   %-16s: %d", k1, v1, k2, v2);
    }

    private void section(String title, String col, ScanReport r, IssueSeverity sev) {
        boolean any = r.getQueries().stream().anyMatch(q -> q.countBySeverity(sev) > 0)
                   || r.getEntities().stream().anyMatch(e -> e.countBySeverity(sev) > 0);
        if (!any) return;

        p(col + bar('=', WIDTH) + RESET);
        p(col + BOLD + "  " + title + RESET);
        p(col + bar('=', WIDTH) + RESET);
        p("");

        for (EntityInfo e : r.getEntities()) {
            List<Issue> matching = e.getIssues().stream().filter(i -> i.getSeverity() == sev).toList();
            if (matching.isEmpty()) continue;
            p(BOLD + "  [ENTITY] " + e.getClassName() + RESET);
            p(DIM + "  " + bar('-', WIDTH - 2) + RESET);
            matching.forEach(this::issue);
        }
        for (QueryInfo q : r.getQueries()) {
            List<Issue> matching = q.getIssues().stream().filter(i -> i.getSeverity() == sev).toList();
            if (matching.isEmpty()) continue;
            p(BOLD + "  [QUERY]  " + q.getSourceFile() + ":" + q.getLineNumber()
                    + "  ->  " + q.getMethodName() + "()" + RESET);
            p(DIM + "  " + bar('-', WIDTH - 2) + RESET);
            if (q.getQueryString() != null) {
                p(DIM + "  query : " + truncate(q.getQueryString(), 58) + RESET);
            }
            p(DIM + "  type  : " + q.getType().getLabel() + RESET);
            matching.forEach(this::issue);
        }
    }

    private void issue(Issue i) {
        String tag = switch (i.getSeverity()) {
            case CRITICAL -> BRED + "  [x] " + RESET;
            case WARNING  -> BYEL + "  [!] " + RESET;
            case INFO     -> BCYAN + "  [i] " + RESET;
        };
        p(tag + BOLD + i.getRuleId() + RESET);
        wrap("      why : ", i.getMessage(), "");
        wrap("      fix : ", i.getTip(), GREEN);
        p("");
    }

    private void wrap(String label, String text, String col) {
        int avail = WIDTH - label.length();
        String indent = " ".repeat(label.length());
        StringBuilder cur = new StringBuilder();
        boolean first = true;
        for (String word : text.split(" ")) {
            if (cur.length() + word.length() + 1 > avail) {
                p((first ? col + label : col + indent) + cur + RESET);
                cur.setLength(0);
                first = false;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) p((first ? col + label : col + indent) + cur + RESET);
    }

    private void analytics(ScanReport r) {
        p(BOLD + MAGENTA + bar('=', WIDTH) + RESET);
        p(BOLD + MAGENTA + "  ANALYTICS" + RESET);
        p(BOLD + MAGENTA + bar('=', WIDTH) + RESET);
        p("");

        Map<String, Long> byFile = new LinkedHashMap<>();
        r.getQueries().forEach(q -> {
            if (!q.getIssues().isEmpty()) byFile.merge(q.getSourceFile(), (long) q.getIssues().size(), Long::sum);
        });
        r.getEntities().forEach(e -> {
            if (!e.getIssues().isEmpty()) byFile.merge(e.getSourceFile(), (long) e.getIssues().size(), Long::sum);
        });

        p(BOLD + "  Most problematic files" + RESET);
        byFile.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(en -> p(String.format("    %-42s %s  %d",
                        truncate(en.getKey(), 40), bar('*', en.getValue().intValue()), en.getValue())));
        p("");

        long crit = r.totalCritical(), warn = r.totalWarnings(), info = r.totalInfo();
        long total = crit + warn + info;
        p(BOLD + "  Severity distribution" + RESET);
        p(distRow("CRITICAL", crit, total, BRED));
        p(distRow("WARNING ", warn, total, BYEL));
        p(distRow("INFO    ", info, total, BCYAN));
        p("");
    }

    private String distRow(String label, long n, long total, String col) {
        int pct = total == 0 ? 0 : (int) Math.round(100.0 * n / total);
        int filled = total == 0 ? 0 : (int) Math.round(26.0 * n / total);
        return "    " + col + label + RESET + "  "
                + col + bar('#', filled) + RESET + DIM + bar('.', 26 - filled) + RESET
                + String.format("  %3d (%3d%%)", n, pct);
    }

    private void footer(ScanReport r) {
        long crit = r.totalCritical();
        p("");
        if (crit > 0) {
            p(BRED + bar('=', WIDTH) + RESET);
            p(BRED + "  " + crit + " critical issue(s) need attention before deploying." + RESET);
            p(BRED + bar('=', WIDTH) + RESET);
        } else {
            p(GREEN + bar('=', WIDTH) + RESET);
            p(GREEN + "  No critical issues. Review warnings when you can." + RESET);
            p(GREEN + bar('=', WIDTH) + RESET);
        }
    }

    private String truncate(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }
}
