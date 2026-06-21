package com.zakdev.data.jpaanalyzer.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.zakdev.data.jpaanalyzer.analyzer.TestSupport.jpqlRules;
import static com.zakdev.data.jpaanalyzer.analyzer.TestSupport.nativeRules;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JpqlAnalyzer")
class JpqlAnalyzerTest {

    @Test
    @DisplayName("flags JOIN FETCH without DISTINCT")
    void fetchJoinWithoutDistinct() {
        assertTrue(jpqlRules("SELECT a FROM Author a JOIN FETCH a.books")
                .contains("FETCH_JOIN_NO_DISTINCT"));
    }

    @Test
    @DisplayName("flags JOIN FETCH without pagination, but not when a Pageable is present")
    void fetchJoinWithoutPagination() {
        assertTrue(jpqlRules("SELECT a FROM Author a JOIN FETCH a.books")
                .contains("FETCH_JOIN_NO_PAGINATION"));
        assertFalse(jpqlRules("SELECT a FROM Author a JOIN FETCH a.books", true)
                .contains("FETCH_JOIN_NO_PAGINATION"));
    }

    @Test
    @DisplayName("flags multiple collection JOIN FETCH (MultipleBagFetchException risk)")
    void multipleCollectionFetch() {
        assertTrue(jpqlRules("SELECT DISTINCT a FROM Author a JOIN FETCH a.books JOIN FETCH a.reviews")
                .contains("MULTIPLE_COLLECTION_FETCH"));
        assertFalse(jpqlRules("SELECT DISTINCT a FROM Author a JOIN FETCH a.books")
                .contains("MULTIPLE_COLLECTION_FETCH"));
    }

    @Test
    @DisplayName("flags a missing WHERE clause, but not for COUNT/UPDATE/DELETE")
    void noWhereClause() {
        assertTrue(jpqlRules("SELECT a FROM Author a").contains("NO_WHERE_CLAUSE"));
        assertFalse(jpqlRules("SELECT COUNT(a) FROM Author a").contains("NO_WHERE_CLAUSE"));
    }

    @Test
    @DisplayName("flags comma-joined entities as a Cartesian product")
    void cartesianProduct() {
        assertTrue(jpqlRules("SELECT a FROM Author a, Book b WHERE a.id = b.authorId")
                .contains("CARTESIAN_PRODUCT_RISK"));
    }

    @Test
    @DisplayName("flags an IN clause and, separately, an IN (subquery)")
    void inClauseAndSubquery() {
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE a.id IN :ids").contains("IN_CLAUSE"));
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE a.id IN (SELECT b.authorId FROM Book b)")
                .contains("IN_SUBQUERY"));
    }

    @Test
    @DisplayName("flags ORDER BY over an unbounded result")
    void orderByUnbounded() {
        assertTrue(jpqlRules("SELECT a FROM Author a ORDER BY a.name")
                .contains("ORDER_BY_UNBOUNDED"));
    }

    @Test
    @DisplayName("flags a leading-wildcard LIKE")
    void leadingWildcardLike() {
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE a.name LIKE '%doe'")
                .contains("LEADING_WILDCARD_LIKE"));
    }

    @Test
    @DisplayName("flags a function wrapping a column in WHERE (non-sargable)")
    void functionOnWhereColumn() {
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE UPPER(a.name) = :n")
                .contains("FUNCTION_ON_WHERE_COLUMN"));
    }

    @Test
    @DisplayName("flags multi-level path navigation as an implicit join")
    void implicitJoin() {
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE a.publisher.name = :n")
                .contains("IMPLICIT_JOIN"));
    }

    @Test
    @DisplayName("flags bulk UPDATE/DELETE as a persistence-context bypass")
    void bulkMutationContextBypass() {
        assertTrue(jpqlRules("UPDATE Author a SET a.active = false WHERE a.id = :id")
                .contains("BULK_MUTATION_PC_BYPASS"));
        assertTrue(jpqlRules("DELETE FROM Author a WHERE a.id = :id")
                .contains("BULK_MUTATION_PC_BYPASS"));
    }

    @Test
    @DisplayName("flags native SELECT * and the native-query note")
    void nativeQuery() {
        var rules = nativeRules("SELECT * FROM author WHERE id = ?1");
        assertTrue(rules.contains("NATIVE_QUERY"));
        assertTrue(rules.contains("SELECT_STAR"));
    }

    @Test
    @DisplayName("a clean, bounded query raises nothing")
    void cleanQueryIsSilent() {
        assertTrue(jpqlRules("SELECT a FROM Author a WHERE a.id = :id").isEmpty());
    }
}
