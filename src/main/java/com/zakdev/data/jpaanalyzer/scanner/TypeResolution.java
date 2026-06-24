package com.zakdev.data.jpaanalyzer.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Wraps an optional JavaParser {@link JavaSymbolSolver}. When type resolution is enabled and a
 * classpath is available, it can answer questions the simple-name scanner can't — chiefly whether
 * an interface is a Spring Data repository through a <em>custom base interface</em> (e.g.
 * {@code FooRepository extends BaseRepository} where {@code BaseRepository extends JpaRepository}).
 *
 * <p>Everything degrades gracefully: if resolution is disabled, the classpath is incomplete, or a
 * particular declaration can't be resolved, the methods here return {@code false} and the caller
 * falls back to its name-based heuristics. Resolution is never allowed to abort a scan.
 */
public final class TypeResolution {

    /** Fully-qualified Spring Data repository supertypes, matched against resolved ancestors. */
    private static final Set<String> REPOSITORY_FQNS = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.jpa.repository.JpaSpecificationExecutor",
            "org.springframework.data.mongodb.repository.MongoRepository"
    );

    /** Simple-name fallback, mirroring ProjectScanner's set, for ancestors we resolve by name only. */
    private static final Set<String> REPOSITORY_SIMPLE = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "JpaSpecificationExecutor", "Repository", "ListCrudRepository",
            "ListPagingAndSortingRepository", "MongoRepository"
    );

    private final JavaParser parser;
    private final boolean solving;

    private TypeResolution(JavaParser parser, boolean solving) {
        this.parser = parser;
        this.solving = solving;
    }

    /**
     * Builds a resolver over the project's own source roots plus its compile-classpath jars. When
     * {@code enabled} is false (or no solving context is useful) the returned instance just parses
     * without symbol resolution.
     */
    public static TypeResolution create(List<File> sourceRoots, List<String> classpathElements, boolean enabled) {
        ParserConfiguration config = new ParserConfiguration();
        if (!enabled) {
            return new TypeResolution(new JavaParser(config), false);
        }

        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        if (sourceRoots != null) {
            for (File root : sourceRoots) {
                if (root != null && root.isDirectory()) solver.add(new JavaParserTypeSolver(root));
            }
        }
        if (classpathElements != null) {
            for (String cp : classpathElements) {
                // Dependency jars carry the framework supertypes; the project's own compiled-class
                // directories are already covered by the source solvers, so skip them.
                if (cp != null && cp.toLowerCase().endsWith(".jar")) {
                    try {
                        solver.add(new JarTypeSolver(cp));
                    } catch (IOException ignored) {
                        // Missing/unreadable jar — resolution just loses that artifact's types.
                    }
                }
            }
        }
        config.setSymbolResolver(new JavaSymbolSolver(solver));
        return new TypeResolution(new JavaParser(config), true);
    }

    public boolean solvingEnabled() {
        return solving;
    }

    /** Parses a file with the (optionally symbol-aware) parser; empty if JavaParser couldn't parse it. */
    public ParseResult<CompilationUnit> parse(File file) throws IOException {
        return parser.parse(file);
    }

    /**
     * True only when type resolution proves the declaration's ancestor chain reaches a known Spring
     * Data repository supertype. Returns false (so the caller's name check still applies) when
     * resolution is off or anything can't be resolved.
     */
    public boolean isResolvedRepository(ClassOrInterfaceDeclaration decl) {
        if (!solving) return false;
        try {
            ResolvedReferenceTypeDeclaration resolved = decl.resolve();
            // breadthFirstFunc tolerates unresolvable ancestors instead of throwing.
            return resolved.getAllAncestors(ResolvedReferenceTypeDeclaration.breadthFirstFunc).stream()
                    .map(a -> a.getTypeDeclaration().map(ResolvedReferenceTypeDeclaration::getQualifiedName).orElse(""))
                    .anyMatch(qn -> REPOSITORY_FQNS.contains(qn) || REPOSITORY_SIMPLE.contains(simpleName(qn)));
        } catch (Exception e) {
            return false;
        }
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }
}
