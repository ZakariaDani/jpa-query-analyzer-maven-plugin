package com.zakdev.data.jpaanalyzer.reporter;

import com.zakdev.data.jpaanalyzer.model.Finding;
import com.zakdev.data.jpaanalyzer.model.ScanReport;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes the suppression baseline — a JSON file of issue fingerprints. Applying a
 * baseline removes already-known issues from the report so CI fails only on <em>new</em> ones;
 * writing one snapshots the current findings as the accepted set. The file is JSON, parsed via
 * SnakeYAML (YAML is a JSON superset), so no extra dependency is needed.
 */
public final class Baseline {

    private Baseline() {}

    /** Loads the fingerprint set from a baseline file; empty if missing or unreadable. */
    public static Set<String> load(File file) {
        Set<String> out = new LinkedHashSet<>();
        if (file == null || !file.exists()) return out;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(r);
            if (root instanceof Map<?, ?> map && map.get("fingerprints") instanceof List<?> list) {
                list.forEach(fp -> { if (fp != null) out.add(fp.toString()); });
            }
        } catch (Exception ignored) {
            // A malformed baseline must not abort the scan; treat as no suppressions.
        }
        return out;
    }

    /** Suppresses every issue in {@code report} that the baseline file lists. Returns the count removed. */
    public static long apply(File file, ScanReport report) {
        return report.suppress(load(file));
    }

    /** Writes the current findings' fingerprints to {@code file} as JSON, creating parent dirs. */
    public static void write(File file, ScanReport report) throws IOException {
        // De-duplicate: several findings can share a fingerprint (same rule, file, location).
        Set<String> fingerprints = new LinkedHashSet<>();
        for (Finding f : report.findings()) fingerprints.add(f.fingerprint());

        StringBuilder b = new StringBuilder(64 + fingerprints.size() * 48);
        b.append("{\n  \"version\": 1,\n  \"fingerprints\": [");
        int i = 0;
        for (String fp : fingerprints) {
            b.append(i++ == 0 ? "\n" : ",\n");
            b.append("    \"").append(Json.esc(fp)).append("\"");
        }
        b.append(fingerprints.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");

        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), b.toString(), StandardCharsets.UTF_8);
    }

    /** Number of unique fingerprints currently in a report (for log messages). */
    public static long size(ScanReport report) {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (Finding f : report.findings()) fingerprints.add(f.fingerprint());
        return fingerprints.size();
    }
}
