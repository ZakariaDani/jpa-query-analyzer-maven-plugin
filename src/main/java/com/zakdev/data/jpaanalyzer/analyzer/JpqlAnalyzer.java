package com.zakdev.data.jpaanalyzer.analyzer;

import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;
import com.zakdev.data.jpaanalyzer.model.QueryInfo;
import com.zakdev.data.jpaanalyzer.model.QueryType;

/**
 * Analyzes JPQL and native SQL query strings (from @Query / @NamedQuery)
 * for common performance pitfalls.
 */
public class JpqlAnalyzer {

    public void analyze(QueryInfo qi) {
        if (qi.getQueryString() == null) return;

        // Normalize: collapse whitespace, uppercase for keyword matching
        String upper = qi.getQueryString().toUpperCase().replaceAll("\\s+", " ").trim();

        if (qi.getType() == QueryType.NATIVE_SQL) {
            analyzeNativeQuery(qi, upper);
            return;
        }

        checkFetchJoinWithoutDistinct(qi, upper);
        checkFetchJoinWithoutPagination(qi, upper);
        checkMultipleCollectionFetch(qi, upper);
        checkNoWhereClause(qi, upper);
        checkCartesianProduct(qi, upper);
        checkInClause(qi, upper);
        checkInSubquery(qi, upper);
        checkNotInSubquery(qi, upper);
        checkOrderByOnUnbounded(qi, upper);
        checkLikeLeadingWildcard(qi, upper);
        checkFunctionOnWhereColumn(qi, upper);
        checkImplicitJoin(qi, upper);
        checkBulkMutationContextBypass(qi, upper);
    }

    private void checkFetchJoinWithoutDistinct(QueryInfo qi, String upper) {
        if (upper.contains("JOIN FETCH") && !upper.contains("SELECT DISTINCT")) {
            qi.addIssue(new Issue(
                IssueSeverity.CRITICAL,
                "FETCH_JOIN_NO_DISTINCT",
                "JOIN FETCH without DISTINCT returns duplicate root entity rows (one per child).",
                "Use: SELECT DISTINCT e FROM Entity e JOIN FETCH e.collection"
            ));
        }
    }

    private void checkFetchJoinWithoutPagination(QueryInfo qi, String upper) {
        if (upper.contains("JOIN FETCH") && !qi.hasPagination()) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "FETCH_JOIN_NO_PAGINATION",
                "JOIN FETCH with pagination forces in-memory paging (Hibernate loads ALL rows first).",
                "Paginate the root IDs first, then fetch associations in a second query."
            ));
        }
    }

    private void checkNoWhereClause(QueryInfo qi, String upper) {
        boolean hasWhere = upper.contains(" WHERE ");
        boolean isCount  = upper.startsWith("SELECT COUNT");
        boolean isDelete = upper.startsWith("DELETE");
        boolean isUpdate = upper.startsWith("UPDATE");
        if (!hasWhere && !isCount && !isDelete && !isUpdate) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "NO_WHERE_CLAUSE",
                "No WHERE clause - risk of a full table scan loading every row.",
                "Add filtering conditions or accept a Pageable to bound the result set."
            ));
        }
    }

    private void checkCartesianProduct(QueryInfo qi, String upper) {
        int fromIdx = upper.indexOf(" FROM ");
        if (fromIdx < 0) return;
        String afterFrom = upper.substring(fromIdx + 6);
        int cut = afterFrom.length();
        int whereIdx = afterFrom.indexOf(" WHERE ");
        int joinIdx  = afterFrom.indexOf(" JOIN ");
        if (whereIdx >= 0) cut = Math.min(cut, whereIdx);
        if (joinIdx  >= 0) cut = Math.min(cut, joinIdx);
        String fromClause = afterFrom.substring(0, cut);
        if (fromClause.contains(",")) {
            qi.addIssue(new Issue(
                IssueSeverity.CRITICAL,
                "CARTESIAN_PRODUCT_RISK",
                "Multiple entities in the FROM clause produce a Cartesian product.",
                "Use an explicit association JOIN: FROM Entity e JOIN e.other o"
            ));
        }
    }

    private void checkInClause(QueryInfo qi, String upper) {
        if (upper.contains(" IN ") || upper.contains(" IN(")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "IN_CLAUSE",
                "IN clause detected - query plans degrade and may break with very large lists (>1000 on Oracle).",
                "Batch large parameter lists, or restructure as a JOIN against a temp set."
            ));
        }
    }

    private void checkOrderByOnUnbounded(QueryInfo qi, String upper) {
        if (upper.contains(" ORDER BY ") && !qi.hasPagination() && !upper.contains(" WHERE ")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "ORDER_BY_UNBOUNDED",
                "ORDER BY over an unbounded result sorts the entire table.",
                "Add a Pageable or a WHERE clause so the DB sorts fewer rows (ideally via an index)."
            ));
        }
    }

    private void checkLikeLeadingWildcard(QueryInfo qi, String upper) {
        // Matches LIKE '%... or LIKE CONCAT('%' ... -> non-sargable, kills index usage
        if (upper.contains("LIKE '%") || upper.contains("LIKE CONCAT('%")
                || upper.contains("LIKE '%'")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "LEADING_WILDCARD_LIKE",
                "LIKE with a leading wildcard ('%term') cannot use a standard B-tree index.",
                "Anchor the pattern ('term%') or use a full-text / trigram index for contains-search."
            ));
        }
    }

    private void checkMultipleCollectionFetch(QueryInfo qi, String upper) {
        // Fetching more than one collection in a single query yields a Cartesian
        // product, and Hibernate throws MultipleBagFetchException for List-typed bags.
        if (countOccurrences(upper, "JOIN FETCH") >= 2) {
            qi.addIssue(new Issue(
                IssueSeverity.CRITICAL,
                "MULTIPLE_COLLECTION_FETCH",
                "Multiple JOIN FETCH clauses fetch more than one association at once - a Cartesian "
                        + "product (and MultipleBagFetchException for List collections).",
                "Fetch at most one collection per query; load the others in separate queries or with @EntityGraph."
            ));
        }
    }

    private void checkInSubquery(QueryInfo qi, String upper) {
        if (upper.contains("IN (SELECT") || upper.contains("IN(SELECT")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "IN_SUBQUERY",
                "IN (subquery) often forces the database to materialize the inner result before filtering.",
                "Prefer EXISTS (correlated) or an explicit JOIN, which usually plan and short-circuit better."
            ));
        }
    }

    private void checkNotInSubquery(QueryInfo qi, String upper) {
        // NOT IN (subquery) yields NO rows if the subquery returns a single NULL (three-valued
        // logic), and it cannot short-circuit the way NOT EXISTS does.
        if (upper.contains("NOT IN (SELECT") || upper.contains("NOT IN(SELECT")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "NOT_IN_SUBQUERY",
                "NOT IN (subquery) returns no rows at all if the subquery yields a single NULL, and it can't short-circuit like NOT EXISTS.",
                "Use NOT EXISTS (correlated) or a LEFT JOIN ... WHERE x IS NULL, which handle NULLs correctly and usually plan better."
            ));
        }
    }

    private void checkFunctionOnWhereColumn(QueryInfo qi, String upper) {
        int whereIdx = upper.indexOf(" WHERE ");
        if (whereIdx < 0) return;
        String where = upper.substring(whereIdx);
        // A SQL function wrapping a column path (alias.field) makes the predicate
        // non-sargable - the DB cannot use a plain B-tree index on that column.
        if (where.matches(".*\\b(UPPER|LOWER|TRIM|YEAR|MONTH|DAY|DATE|CAST|CONVERT|SUBSTRING|TRUNC|COALESCE)\\s*\\(\\s*\\w+\\.\\w+.*")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "FUNCTION_ON_WHERE_COLUMN",
                "A function wraps a column in the WHERE clause, making the predicate non-sargable (no index use).",
                "Index the raw column and transform the parameter instead, or add a functional/expression index."
            ));
        }
    }

    private void checkImplicitJoin(QueryInfo qi, String upper) {
        // Navigating an association path (alias.assoc.field) in WHERE/ORDER BY
        // generates an implicit inner join you don't control - easy to miss and to over-join.
        int whereIdx = upper.indexOf(" WHERE ");
        if (whereIdx < 0) return;
        String tail = upper.substring(whereIdx);
        if (tail.matches(".*\\b\\w+\\.\\w+\\.\\w+\\b.*")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "IMPLICIT_JOIN",
                "Multi-level path navigation (e.g. e.parent.name) creates an implicit INNER JOIN you don't control.",
                "Declare the JOIN explicitly so the join type and fetch behaviour are intentional."
            ));
        }
    }

    private void checkBulkMutationContextBypass(QueryInfo qi, String upper) {
        if (upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            qi.addIssue(new Issue(
                IssueSeverity.INFO,
                "BULK_MUTATION_PC_BYPASS",
                "Bulk JPQL UPDATE/DELETE bypasses the persistence context - already-loaded entities go stale.",
                "Annotate with @Modifying(clearAutomatically = true, flushAutomatically = true), or flush/clear around it."
            ));
        }
    }

    private int countOccurrences(String text, String token) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(token, idx)) != -1) {
            count++;
            idx += token.length();
        }
        return count;
    }

    private void analyzeNativeQuery(QueryInfo qi, String upper) {
        qi.addIssue(new Issue(
            IssueSeverity.INFO,
            "NATIVE_QUERY",
            "Native SQL bypasses the JPA dialect layer and is not portable across databases.",
            "Keep it parameterized (never string-concatenate inputs) and verify supporting indexes exist."
        ));
        if (upper.contains("SELECT *")) {
            qi.addIssue(new Issue(
                IssueSeverity.WARNING,
                "SELECT_STAR",
                "SELECT * over-fetches every column, including large/unused ones.",
                "Project only the columns you map: SELECT id, name, email FROM ..."
            ));
        }
    }
}
