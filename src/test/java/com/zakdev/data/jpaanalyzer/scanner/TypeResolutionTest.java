package com.zakdev.data.jpaanalyzer.scanner;

import com.zakdev.data.jpaanalyzer.analyzer.ConfigAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.EntityAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.JpqlAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.SpringDataMethodAnalyzer;
import com.zakdev.data.jpaanalyzer.model.ScanReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Type resolution (custom base repositories)")
class TypeResolutionTest {

    /**
     * Three source files: a JpaRepository stub, a custom base interface extending it, and a
     * concrete repository extending the base. Only symbol resolution can tell that the concrete
     * repository is a Spring Data repository, since its direct supertype is the custom base.
     */
    private static void writeSampleTree(Path root) throws IOException {
        write(root, "org/springframework/data/jpa/repository/JpaRepository.java",
                "package org.springframework.data.jpa.repository;\n"
                        + "public interface JpaRepository<T, ID> { }\n");
        write(root, "com/example/BaseRepository.java",
                "package com.example;\n"
                        + "import org.springframework.data.jpa.repository.JpaRepository;\n"
                        + "public interface BaseRepository<T, ID> extends JpaRepository<T, ID> { }\n");
        write(root, "com/example/CustomerRepository.java",
                "package com.example;\n"
                        + "public interface CustomerRepository extends BaseRepository<String, Long> { }\n");
    }

    private static void write(Path root, String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ScanReport scan(Path root, boolean resolveTypes) {
        TypeResolution tr = TypeResolution.create(java.util.List.of(root.toFile()),
                java.util.List.of(), resolveTypes);
        ProjectScanner scanner = new ProjectScanner(new JpqlAnalyzer(), new EntityAnalyzer(),
                new SpringDataMethodAnalyzer(), new ConfigAnalyzer(), tr);
        return scanner.scan(root.toFile());
    }

    @Test
    @DisplayName("resolution detects a repository that extends a custom base interface")
    void resolvesTransitiveRepository(@TempDir Path root) throws IOException {
        writeSampleTree(root);
        // BaseRepository (direct JpaRepository) + CustomerRepository (only via resolution).
        assertEquals(2, scan(root, true).getRepositoryCount());
    }

    @Test
    @DisplayName("without resolution, only the directly-extending repository is found")
    void nameMatchingMissesTransitiveRepository(@TempDir Path root) throws IOException {
        writeSampleTree(root);
        // Only BaseRepository matches by simple supertype name; CustomerRepository is missed.
        assertEquals(1, scan(root, false).getRepositoryCount());
    }
}
