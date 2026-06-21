package com.zakdev.data.jpaanalyzer.scanner;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.zakdev.data.jpaanalyzer.analyzer.EntityAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.JpqlAnalyzer;
import com.zakdev.data.jpaanalyzer.analyzer.SpringDataMethodAnalyzer;
import com.zakdev.data.jpaanalyzer.model.EntityInfo;
import com.zakdev.data.jpaanalyzer.model.QueryInfo;
import com.zakdev.data.jpaanalyzer.model.QueryType;
import com.zakdev.data.jpaanalyzer.model.ScanReport;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks the source tree, identifies Spring Data repositories and JPA entities,
 * extracts query metadata, and runs the analyzers against each finding.
 */
public class ProjectScanner {

    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "JpaSpecificationExecutor", "Repository", "ListCrudRepository",
            "ListPagingAndSortingRepository", "MongoRepository"
    );

    private static final Set<String> PAGINATION_TYPES = Set.of("Pageable", "PageRequest", "Sort");

    private final JpqlAnalyzer jpqlAnalyzer;
    private final EntityAnalyzer entityAnalyzer;
    private final SpringDataMethodAnalyzer derivedAnalyzer;

    public ProjectScanner(JpqlAnalyzer jpqlAnalyzer,
                          EntityAnalyzer entityAnalyzer,
                          SpringDataMethodAnalyzer derivedAnalyzer) {
        this.jpqlAnalyzer = jpqlAnalyzer;
        this.entityAnalyzer = entityAnalyzer;
        this.derivedAnalyzer = derivedAnalyzer;
    }

    public ScanReport scan(File sourceDirectory) {
        ScanReport report = new ScanReport();
        if (sourceDirectory == null || !sourceDirectory.exists()) return report;

        try (Stream<java.nio.file.Path> paths = Files.walk(sourceDirectory.toPath())) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> processFile(p.toFile(), sourceDirectory, report));
        } catch (Exception e) {
            System.err.println("[JPA Analyzer] Scan error: " + e.getMessage());
        }
        return report;
    }

    private void processFile(File file, File baseDir, ScanReport report) {
        report.incrementScannedFiles();
        String rel = baseDir.toPath().relativize(file.toPath()).toString();
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
                if (isEntity(decl)) {
                    report.incrementEntityCount();
                    EntityInfo ei = new EntityInfo(rel, decl.getNameAsString());
                    entityAnalyzer.analyze(decl, ei);
                    report.addEntity(ei);
                    extractNamedQueries(decl, rel, report);
                }
                if (isRepository(decl)) {
                    report.incrementRepositoryCount();
                    extractRepositoryQueries(decl, rel, report);
                }
            });
        } catch (Exception ignored) {
            // Skip files JavaParser cannot handle (e.g. newer syntax); not fatal.
        }
    }

    private boolean isEntity(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotationByName("Entity").isPresent();
    }

    private boolean isRepository(ClassOrInterfaceDeclaration decl) {
        if (decl.getAnnotationByName("Repository").isPresent()) return true;
        boolean ext = decl.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .anyMatch(REPOSITORY_SUPERTYPES::contains);
        boolean impl = decl.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .anyMatch(REPOSITORY_SUPERTYPES::contains);
        return ext || impl;
    }

    private void extractRepositoryQueries(ClassOrInterfaceDeclaration decl, String rel, ScanReport report) {
        decl.getMethods().forEach(method -> {
            boolean hasPagination = method.getParameters().stream()
                    .anyMatch(p -> PAGINATION_TYPES.stream()
                            .anyMatch(pt -> p.getTypeAsString().contains(pt)));
            String returnType = method.getTypeAsString();
            int line = method.getBegin().map(b -> b.line).orElse(0);

            method.getAnnotationByName("Query").ifPresentOrElse(ann -> {
                QueryAnnotationData data = readQueryAnnotation(ann);
                if (data.value != null) {
                    QueryType type = data.nativeQuery ? QueryType.NATIVE_SQL : QueryType.JPQL;
                    QueryInfo qi = new QueryInfo(rel, decl.getNameAsString(), method.getNameAsString(),
                            data.value, type, ann.getBegin().map(b -> b.line).orElse(line),
                            hasPagination, returnType);
                    jpqlAnalyzer.analyze(qi);
                    report.addQuery(qi);
                }
            }, () -> {
                QueryInfo qi = new QueryInfo(rel, decl.getNameAsString(), method.getNameAsString(),
                        null, QueryType.SPRING_DATA_DERIVED, line, hasPagination, returnType);
                derivedAnalyzer.analyze(qi);
                report.addQuery(qi);
            });
        });
    }

    private void extractNamedQueries(ClassOrInterfaceDeclaration decl, String rel, ScanReport report) {
        decl.getAnnotationByName("NamedQuery").ifPresent(ann -> {
            if (ann instanceof NormalAnnotationExpr normal) {
                String name = null, query = null;
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("name"))  name  = readString(pair.getValue());
                    if (pair.getNameAsString().equals("query")) query = readString(pair.getValue());
                }
                if (query != null) {
                    QueryInfo qi = new QueryInfo(rel, decl.getNameAsString(),
                            name != null ? name : "namedQuery", query, QueryType.NAMED_QUERY,
                            ann.getBegin().map(b -> b.line).orElse(0), false, "List");
                    jpqlAnalyzer.analyze(qi);
                    report.addQuery(qi);
                }
            }
        });
    }

    private QueryAnnotationData readQueryAnnotation(AnnotationExpr ann) {
        QueryAnnotationData data = new QueryAnnotationData();
        if (ann instanceof SingleMemberAnnotationExpr single) {
            data.value = readString(single.getMemberValue());
        } else if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                switch (pair.getNameAsString()) {
                    case "value" -> data.value = readString(pair.getValue());
                    case "nativeQuery" -> data.nativeQuery =
                            "true".equalsIgnoreCase(pair.getValue().toString());
                }
            }
        }
        return data;
    }

    private String readString(Expression expr) {
        if (expr instanceof StringLiteralExpr s)  return s.asString();
        if (expr instanceof TextBlockLiteralExpr t) return t.asString();
        // Best-effort for concatenated string literals.
        return expr.toString().replace("\"", "").replace(" + ", " ").trim();
    }

    private static final class QueryAnnotationData {
        String value;
        boolean nativeQuery;
    }
}
