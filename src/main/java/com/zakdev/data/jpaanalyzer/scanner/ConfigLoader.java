package com.zakdev.data.jpaanalyzer.scanner;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Loads a Spring Boot project's configuration into a single flattened map of
 * dot-notation keys (lowercased) to string values. Reads every
 * {@code application*.properties} / {@code application*.yml} / {@code .yaml}
 * under a resources directory and merges them — profile files included — so the
 * "is this property set anywhere" checks in {@link
 * com.zakdev.data.jpaanalyzer.analyzer.ConfigAnalyzer} see the union.
 *
 * <p>YAML documents are flattened to dot notation
 * ({@code spring.jpa.open-in-view}); list elements are indexed
 * ({@code key[0]}). Unparseable files are skipped, never fatal.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Returns the merged, lowercased-key config, or an empty map if none found. */
    public static Map<String, String> load(File resourcesDirectory) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (resourcesDirectory == null || !resourcesDirectory.exists()) return merged;

        try (Stream<java.nio.file.Path> paths = Files.walk(resourcesDirectory.toPath())) {
            paths.filter(Files::isRegularFile)
                 .map(java.nio.file.Path::toFile)
                 .filter(ConfigLoader::isConfigFile)
                 .forEach(f -> mergeFile(f, merged));
        } catch (Exception ignored) {
            // Resources are optional; a scan should never fail on config IO.
        }
        return merged;
    }

    private static boolean isConfigFile(File f) {
        String n = f.getName().toLowerCase();
        boolean ext = n.endsWith(".properties") || n.endsWith(".yml") || n.endsWith(".yaml");
        return ext && n.startsWith("application");
    }

    private static void mergeFile(File f, Map<String, String> target) {
        String name = f.getName().toLowerCase();
        try {
            if (name.endsWith(".properties")) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                p.forEach((k, v) -> put(target, String.valueOf(k), String.valueOf(v)));
            } else {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    // A YAML file may hold several profile documents separated by '---'.
                    for (Object doc : new Yaml().loadAll(in)) {
                        flatten("", doc, target);
                    }
                }
            }
        } catch (Exception ignored) {
            // Skip malformed config files; not fatal to the scan.
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, Map<String, String> target) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toString();
                flatten(prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), target);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), target);
            }
        } else if (node != null && !prefix.isEmpty()) {
            put(target, prefix, node.toString());
        }
    }

    private static void put(Map<String, String> target, String key, String value) {
        target.put(key.toLowerCase(), value);
    }
}
