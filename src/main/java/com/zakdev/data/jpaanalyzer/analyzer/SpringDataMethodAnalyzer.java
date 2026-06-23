package com.zakdev.data.jpaanalyzer.analyzer;

import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;
import com.zakdev.data.jpaanalyzer.model.QueryInfo;

import java.util.Set;

/**
 * Analyzes Spring Data derived query methods (e.g. findByEmailAndStatus)
 * based on their name and return type, since they have no explicit query string.
 */
public class SpringDataMethodAnalyzer {

    private static final Set<String> UNBOUNDED_RETURN_PREFIXES = Set.of(
            "List<", "Collection<", "Iterable<", "Set<", "ArrayList<", "LinkedList<", "Stream<"
    );

    public void analyze(QueryInfo qi) {
        String method = qi.getMethodName();
        String lower = method.toLowerCase();
        String returnType = qi.getReturnType() == null ? "" : qi.getReturnType();

        checkFindAllNoPagination(qi, lower);
        checkUnboundedCollectionReturn(qi, returnType, lower);
        checkComplexDerivedMethod(qi, method, lower);
        checkDerivedDeleteLoadsEntities(qi, lower);
        checkTopWithoutOrderBy(qi, lower);
        checkStreamReturn(qi, returnType);
        checkDerivedLikeContains(qi, lower);
    }

    private void checkFindAllNoPagination(QueryInfo qi, String lower) {
        if (lower.equals("findall") && !qi.hasPagination()) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "FINDALL_NO_PAGINATION",
                "findAll() with no Pageable materializes the entire table in memory.",
                "Call findAll(Pageable pageable) instead, even for admin/export screens."
            ));
        }
    }

    private void checkUnboundedCollectionReturn(QueryInfo qi, String returnType, String lower) {
        boolean returnsCollection = UNBOUNDED_RETURN_PREFIXES.stream().anyMatch(returnType::startsWith);
        boolean isAggregate = lower.startsWith("count") || lower.startsWith("exists") || lower.startsWith("delete");
        if (returnsCollection && !qi.hasPagination() && !isAggregate) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "UNBOUNDED_COLLECTION_RETURN",
                "Returns " + returnType + " with no Pageable - result size is unbounded.",
                "Add a Pageable parameter, or constrain with Top/First (e.g. findTop50By...)."
            ));
        }
    }

    private void checkComplexDerivedMethod(QueryInfo qi, String method, String lower) {
        int conditions = countOccurrences(lower, "and") + countOccurrences(lower, "or");
        if (conditions >= 4) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "COMPLEX_DERIVED_METHOD",
                "Derived method name encodes ~" + conditions + " predicates and is hard to read/maintain.",
                "Move it to an explicit @Query (JPQL) or a Specification/Criteria for clarity."
            ));
        }
    }

    private void checkDerivedDeleteLoadsEntities(QueryInfo qi, String lower) {
        // A derived deleteBy/removeBy first SELECTs the matching entities, then deletes them
        // one by one (to fire lifecycle callbacks) - N+1 deletes for a "bulk" operation.
        if (lower.startsWith("deleteby") || lower.startsWith("removeby")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "DERIVED_DELETE_LOADS_ENTITIES",
                "Derived delete '" + qi.getMethodName() + "' loads matching entities first, then deletes them "
                        + "one row at a time (N+1 deletes).",
                "Use a bulk @Modifying @Query(\"DELETE FROM ...\") when lifecycle callbacks aren't needed."
            ));
        }
    }

    private void checkTopWithoutOrderBy(QueryInfo qi, String lower) {
        boolean isLimited = lower.startsWith("findfirst") || lower.startsWith("findtop")
                || lower.startsWith("getfirst") || lower.startsWith("gettop");
        if (isLimited && !lower.contains("orderby")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "TOP_WITHOUT_ORDERBY",
                "First/Top query '" + qi.getMethodName() + "' has no OrderBy - the row returned is non-deterministic.",
                "Add an explicit OrderBy... clause (or a Sort) so the limited result is stable."
            ));
        }
    }

    private void checkStreamReturn(QueryInfo qi, String returnType) {
        // A Stream<> query holds a DB cursor/connection open until the stream is closed.
        if (returnType.startsWith("Stream<")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "STREAM_QUERY_RESOURCE",
                "A Stream<> query method keeps a database cursor and connection open until the stream is closed; "
                        + "leaking it exhausts the connection pool.",
                "Consume it inside a @Transactional method and a try-with-resources block, or return a List/Page instead."
            ));
        }
    }

    private void checkDerivedLikeContains(QueryInfo qi, String lower) {
        // The Containing/Contains keyword generates LIKE '%term%' - a leading wildcard.
        if (lower.contains("containing") || lower.contains("contains")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "DERIVED_LIKE_CONTAINS",
                "A 'Containing' keyword in '" + qi.getMethodName() + "' generates LIKE '%term%' with a leading "
                        + "wildcard, which cannot use a B-tree index.",
                "Use StartingWith ('term%') if a prefix search suffices, or a full-text/trigram index for contains-search."
            ));
        }
    }

    private int countOccurrences(String text, String word) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(word, idx)) != -1) {
            count++;
            idx += word.length();
        }
        return count;
    }
}
