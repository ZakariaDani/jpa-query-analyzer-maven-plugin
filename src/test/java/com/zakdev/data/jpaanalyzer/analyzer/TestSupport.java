package com.zakdev.data.jpaanalyzer.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.zakdev.data.jpaanalyzer.model.EntityInfo;
import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.QueryInfo;
import com.zakdev.data.jpaanalyzer.model.QueryType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helpers that run an analyzer against a small input and return the set of
 * rule IDs it raised. Tests assert on rule-ID membership rather than message text,
 * so wording can change without breaking the suite.
 */
final class TestSupport {

    private TestSupport() {}

    static Set<String> jpqlRules(String query) {
        return jpqlRules(query, false);
    }

    static Set<String> jpqlRules(String query, boolean hasPagination) {
        QueryInfo qi = new QueryInfo("R.java", "R", "m", query, QueryType.JPQL, 1, hasPagination, "List");
        new JpqlAnalyzer().analyze(qi);
        return ruleIds(qi.getIssues());
    }

    static Set<String> nativeRules(String query) {
        QueryInfo qi = new QueryInfo("R.java", "R", "m", query, QueryType.NATIVE_SQL, 1, false, "List");
        new JpqlAnalyzer().analyze(qi);
        return ruleIds(qi.getIssues());
    }

    static Set<String> derivedRules(String method, String returnType, boolean hasPagination) {
        QueryInfo qi = new QueryInfo("R.java", "R", method, null,
                QueryType.SPRING_DATA_DERIVED, 1, hasPagination, returnType);
        new SpringDataMethodAnalyzer().analyze(qi);
        return ruleIds(qi.getIssues());
    }

    static Set<String> entityRules(String classSource) {
        ClassOrInterfaceDeclaration decl = StaticJavaParser.parse(classSource)
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException("no class in source"));
        EntityInfo ei = new EntityInfo("E.java", decl.getNameAsString());
        new EntityAnalyzer().analyze(decl, ei);
        return ruleIds(ei.getIssues());
    }

    static Set<String> ruleIds(List<Issue> issues) {
        return issues.stream().map(Issue::getRuleId).collect(Collectors.toSet());
    }
}
