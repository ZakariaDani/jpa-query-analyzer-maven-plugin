package com.zakdev.data.jpaanalyzer.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.zakdev.data.jpaanalyzer.analyzer.TestSupport.entityRules;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EntityAnalyzer")
class EntityAnalyzerTest {

    @Test
    @DisplayName("flags EAGER on a collection association")
    void eagerCollection() {
        assertTrue(entityRules("@Entity class A { @OneToMany(fetch = FetchType.EAGER) private List<B> bs; }")
                .contains("EAGER_COLLECTION_FETCH"));
    }

    @Test
    @DisplayName("flags explicit EAGER on a single-valued association")
    void eagerSingleRef() {
        assertTrue(entityRules("@Entity class A { @ManyToOne(fetch = FetchType.EAGER) private B b; }")
                .contains("EXPLICIT_EAGER_FETCH"));
    }

    @Test
    @DisplayName("flags @GeneratedValue(IDENTITY) for breaking insert batching")
    void identityGenerator() {
        assertTrue(entityRules("@Entity class A { @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id; }")
                .contains("IDENTITY_GENERATOR_BATCHING"));
    }

    @Test
    @DisplayName("flags a bare @GeneratedValue (AUTO), but not an explicit SEQUENCE")
    void autoGenerator() {
        assertTrue(entityRules("@Entity class A { @Id @GeneratedValue private Long id; }")
                .contains("AUTO_GENERATOR_STRATEGY"));
        var seq = entityRules("@Entity class A { @Id @GeneratedValue(strategy = GenerationType.SEQUENCE) private Long id; }");
        assertFalse(seq.contains("AUTO_GENERATOR_STRATEGY"));
        assertFalse(seq.contains("IDENTITY_GENERATOR_BATCHING"));
    }

    @Test
    @DisplayName("flags @ManyToMany over a List, but not over a Set")
    void manyToManyList() {
        assertTrue(entityRules("@Entity class A { @ManyToMany private List<B> bs; }")
                .contains("MANY_TO_MANY_LIST"));
        assertFalse(entityRules("@Entity class A { @ManyToMany private Set<B> bs; }")
                .contains("MANY_TO_MANY_LIST"));
    }

    @Test
    @DisplayName("flags cascade REMOVE/ALL on @ManyToMany")
    void cascadeRemoveOnManyToMany() {
        assertTrue(entityRules("@Entity class A { @ManyToMany(cascade = CascadeType.ALL) private Set<B> bs; }")
                .contains("CASCADE_REMOVE_ON_MANY_TO_MANY"));
    }

    @Test
    @DisplayName("flags a unidirectional @OneToMany, but not one with mappedBy")
    void unidirectionalOneToMany() {
        assertTrue(entityRules("@Entity class A { @OneToMany private List<B> bs; }")
                .contains("UNIDIRECTIONAL_ONE_TO_MANY"));
        assertFalse(entityRules("@Entity class A { @OneToMany(mappedBy = \"a\") private List<B> bs; }")
                .contains("UNIDIRECTIONAL_ONE_TO_MANY"));
    }

    @Test
    @DisplayName("flags ordinal @Enumerated (default and explicit), but not STRING")
    void ordinalEnum() {
        assertTrue(entityRules("@Entity class A { @Enumerated private Status s; }")
                .contains("ORDINAL_ENUM"));
        assertTrue(entityRules("@Entity class A { @Enumerated(EnumType.ORDINAL) private Status s; }")
                .contains("ORDINAL_ENUM"));
        assertFalse(entityRules("@Entity class A { @Enumerated(EnumType.STRING) private Status s; }")
                .contains("ORDINAL_ENUM"));
    }

    @Test
    @DisplayName("flags Lombok @Data on an entity (toString + equals)")
    void lombokData() {
        var rules = entityRules("@Data @Entity class A { @Id Long id; }");
        assertTrue(rules.contains("LOMBOK_TOSTRING_ON_ENTITY"));
        assertTrue(rules.contains("LOMBOK_EQUALS_ON_ENTITY"));
    }

    @Test
    @DisplayName("flags a missing @Version, but not when one is present")
    void missingVersion() {
        assertTrue(entityRules("@Entity class A { @Id Long id; }").contains("NO_OPTIMISTIC_LOCK"));
        assertFalse(entityRules("@Entity class A { @Id Long id; @Version Long v; }")
                .contains("NO_OPTIMISTIC_LOCK"));
    }
}
