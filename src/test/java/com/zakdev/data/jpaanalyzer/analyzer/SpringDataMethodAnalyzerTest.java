package com.zakdev.data.jpaanalyzer.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.zakdev.data.jpaanalyzer.analyzer.TestSupport.derivedRules;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpringDataMethodAnalyzer")
class SpringDataMethodAnalyzerTest {

    @Test
    @DisplayName("flags findAll() without a Pageable, but not with one")
    void findAllNoPagination() {
        assertTrue(derivedRules("findAll", "List<A>", false).contains("FINDALL_NO_PAGINATION"));
        assertFalse(derivedRules("findAll", "List<A>", true).contains("FINDALL_NO_PAGINATION"));
    }

    @Test
    @DisplayName("flags an unbounded collection return, but not when paginated")
    void unboundedCollectionReturn() {
        assertTrue(derivedRules("findByStatus", "List<A>", false).contains("UNBOUNDED_COLLECTION_RETURN"));
        assertFalse(derivedRules("findByStatus", "List<A>", true).contains("UNBOUNDED_COLLECTION_RETURN"));
    }

    @Test
    @DisplayName("flags an overly complex derived method name")
    void complexDerivedMethod() {
        assertTrue(derivedRules("findByAAndBAndCAndDAndE", "List<A>", false)
                .contains("COMPLEX_DERIVED_METHOD"));
    }

    @Test
    @DisplayName("flags a derived delete that loads then deletes row-by-row")
    void derivedDeleteLoadsEntities() {
        assertTrue(derivedRules("deleteByStatus", "void", false)
                .contains("DERIVED_DELETE_LOADS_ENTITIES"));
        assertFalse(derivedRules("findByStatus", "List<A>", false)
                .contains("DERIVED_DELETE_LOADS_ENTITIES"));
    }

    @Test
    @DisplayName("flags findFirst/findTop without OrderBy, but not with it")
    void topWithoutOrderBy() {
        assertTrue(derivedRules("findFirstByStatus", "A", false).contains("TOP_WITHOUT_ORDERBY"));
        assertFalse(derivedRules("findFirstByStatusOrderByName", "A", false)
                .contains("TOP_WITHOUT_ORDERBY"));
    }

    @Test
    @DisplayName("a simple findById raises nothing")
    void cleanDerivedMethodIsSilent() {
        assertTrue(derivedRules("findById", "Optional<A>", false).isEmpty());
    }
}
