package com.zakdev.data.jpaanalyzer.analyzer;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.zakdev.data.jpaanalyzer.model.EntityInfo;
import com.zakdev.data.jpaanalyzer.model.Issue;
import com.zakdev.data.jpaanalyzer.model.IssueSeverity;

/**
 * Inspects @Entity classes for mapping decisions that hurt performance:
 * eager fetch on collections, Lombok pitfalls, and missing optimistic locking.
 */
public class EntityAnalyzer {

    public void analyze(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        checkFetchTypes(classDecl, entityInfo);
        checkDefaultEagerToOne(classDecl, entityInfo);
        checkOneToOneMapsId(classDecl, entityInfo);
        checkLombokOnEntity(classDecl, entityInfo);
        checkMissingVersionField(classDecl, entityInfo);
        checkIdGeneratorStrategy(classDecl, entityInfo);
        checkCollectionMappings(classDecl, entityInfo);
        checkEnumMappings(classDecl, entityInfo);
    }

    private void checkFetchTypes(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> {
            String fieldName = field.getVariables().isEmpty()
                    ? "?" : field.getVariables().get(0).getNameAsString();
            // Collections default to LAZY, so EAGER here is an explicit and costly choice.
            checkEagerOnCollection(field, fieldName, "OneToMany", entityInfo);
            checkEagerOnCollection(field, fieldName, "ManyToMany", entityInfo);
            // Single-valued refs default to EAGER; flag explicit EAGER as an N+1 smell.
            checkEagerOnSingleRef(field, fieldName, "ManyToOne", entityInfo);
            checkEagerOnSingleRef(field, fieldName, "OneToOne", entityInfo);
        });
    }

    private void checkEagerOnCollection(FieldDeclaration field, String fieldName,
                                        String annotationName, EntityInfo entityInfo) {
        field.getAnnotationByName(annotationName).ifPresent(ann -> {
            if (ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("fetch")
                            && pair.getValue().toString().contains("EAGER")) {
                        entityInfo.addIssue(new Issue(
                            IssueSeverity.CRITICAL,
                            "EAGER_COLLECTION_FETCH",
                            "@" + annotationName + "(fetch = EAGER) on '" + fieldName
                                    + "' loads the whole collection on every parent read.",
                            "Switch to FetchType.LAZY and pull the collection with JOIN FETCH only where needed."
                        ));
                    }
                }
            }
        });
    }

    private void checkEagerOnSingleRef(FieldDeclaration field, String fieldName,
                                       String annotationName, EntityInfo entityInfo) {
        field.getAnnotationByName(annotationName).ifPresent(ann -> {
            if (ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("fetch")
                            && pair.getValue().toString().contains("EAGER")) {
                        entityInfo.addIssue(new Issue(
                            IssueSeverity.INFO,
                            "EXPLICIT_EAGER_FETCH",
                            "@" + annotationName + "(fetch = EAGER) on '" + fieldName
                                    + "' triggers an extra select per parent row (classic N+1).",
                            "Prefer FetchType.LAZY and fetch explicitly with JOIN FETCH or an entity graph."
                        ));
                    }
                }
            }
        });
    }

    /**
     * @ManyToOne and @OneToOne default to FetchType.EAGER when no fetch is given. This is the
     * single most common JPA performance bug (HO's headline mapping event): every parent read
     * silently drags the association along with an extra select. Explicit EAGER is handled by
     * {@link #checkFetchTypes}; this catches the far more common implicit case.
     */
    private void checkDefaultEagerToOne(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> {
            String fieldName = field.getVariables().isEmpty()
                    ? "?" : field.getVariables().get(0).getNameAsString();
            checkDefaultEager(field, fieldName, "ManyToOne", entityInfo);
            checkDefaultEager(field, fieldName, "OneToOne", entityInfo);
        });
    }

    private void checkDefaultEager(FieldDeclaration field, String fieldName,
                                   String annotationName, EntityInfo entityInfo) {
        field.getAnnotationByName(annotationName).ifPresent(ann -> {
            // No fetch attribute at all -> the JPA default of EAGER applies.
            if (annotationPairValue(ann, "fetch") == null) {
                entityInfo.addIssue(new Issue(
                    IssueSeverity.WARNING,
                    "DEFAULT_EAGER_TO_ONE",
                    "@" + annotationName + " on '" + fieldName + "' has no fetch type, so it defaults to EAGER and "
                            + "is loaded with an extra select on every parent read (N+1).",
                    "Set fetch = FetchType.LAZY explicitly and pull the association with JOIN FETCH or an entity graph where needed."
                ));
            }
        });
    }

    /**
     * An owning @OneToOne (no mappedBy) without @MapsId stores a separate FK column and cannot be
     * loaded lazily reliably, so Hibernate eagerly fires a secondary select. Sharing the parent's
     * PK via @MapsId removes both the column and the extra select (per Vlad Mihalcea).
     */
    private void checkOneToOneMapsId(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> field.getAnnotationByName("OneToOne").ifPresent(ann -> {
            boolean inverseSide = annotationPairValue(ann, "mappedBy") != null;
            boolean hasMapsId = field.getAnnotationByName("MapsId").isPresent();
            if (!inverseSide && !hasMapsId) {
                String fieldName = field.getVariables().isEmpty()
                        ? "?" : field.getVariables().get(0).getNameAsString();
                entityInfo.addIssue(new Issue(
                    IssueSeverity.WARNING,
                    "ONE_TO_ONE_NO_MAPSID",
                    "Owning @OneToOne on '" + fieldName + "' without @MapsId uses a separate FK column and can't be "
                            + "loaded lazily, so it behaves like an eager secondary select.",
                    "Add @MapsId so the child shares the parent's primary key, removing the extra column and the eager select."
                ));
            }
        }));
    }

    private void checkLombokOnEntity(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        boolean hasData = classDecl.getAnnotationByName("Data").isPresent();
        boolean hasToString = hasData || classDecl.getAnnotationByName("ToString").isPresent();
        boolean hasEq = hasData || classDecl.getAnnotationByName("EqualsAndHashCode").isPresent();

        if (hasToString) {
            entityInfo.addIssue(new Issue(
                IssueSeverity.WARNING,
                "LOMBOK_TOSTRING_ON_ENTITY",
                "@ToString/@Data on an entity can trigger lazy loading (and StackOverflow on bidi relations).",
                "Use @ToString(onlyExplicitlyIncluded = true) and include only simple scalar fields."
            ));
        }
        if (hasEq) {
            entityInfo.addIssue(new Issue(
                IssueSeverity.WARNING,
                "LOMBOK_EQUALS_ON_ENTITY",
                "@EqualsAndHashCode/@Data on an entity derives equals from all fields, which breaks across detach/merge.",
                "Base equals/hashCode on a stable business key, or follow the Vlad Mihalcea entity-identity pattern."
            ));
        }
    }

    /**
     * @GeneratedValue(IDENTITY) disables JDBC batch inserts in Hibernate, and AUTO
     * resolves to suboptimal strategies on some databases (e.g. TABLE). SEQUENCE with a
     * pooled optimizer is the batch-friendly default (per Vlad Mihalcea).
     */
    private void checkIdGeneratorStrategy(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> field.getAnnotationByName("GeneratedValue").ifPresent(ann -> {
            String strategy = annotationPairValue(ann, "strategy");
            // A bare @GeneratedValue with no strategy defaults to AUTO.
            String effective = strategy == null ? "AUTO" : strategy.toUpperCase();
            if (effective.contains("IDENTITY")) {
                entityInfo.addIssue(new Issue(
                    IssueSeverity.WARNING,
                    "IDENTITY_GENERATOR_BATCHING",
                    "@GeneratedValue(strategy = IDENTITY) disables Hibernate JDBC batch inserts (each row needs its generated key immediately).",
                    "Use GenerationType.SEQUENCE with a pooled/pooled-lo optimizer to enable insert batching."
                ));
            } else if (effective.contains("AUTO")) {
                entityInfo.addIssue(new Issue(
                    IssueSeverity.INFO,
                    "AUTO_GENERATOR_STRATEGY",
                    "@GeneratedValue(AUTO) lets the provider pick the strategy, which can resolve to the slow TABLE generator.",
                    "Be explicit: prefer GenerationType.SEQUENCE (or IDENTITY only when the DB has no sequences)."
                ));
            }
        }));
    }

    /**
     * Collection-mapping pitfalls: @ManyToMany over a List re-inserts the whole join table
     * on any change; a unidirectional @OneToMany (no mappedBy, no @JoinColumn) creates an
     * extra join table and noisy DML; cascade REMOVE/ALL on @ManyToMany deletes shared rows.
     */
    private void checkCollectionMappings(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> {
            String fieldName = field.getVariables().isEmpty()
                    ? "?" : field.getVariables().get(0).getNameAsString();
            String fieldType = field.getVariables().isEmpty()
                    ? "" : field.getVariables().get(0).getTypeAsString();

            field.getAnnotationByName("ManyToMany").ifPresent(ann -> {
                if (fieldType.startsWith("List") || fieldType.startsWith("Collection")
                        || fieldType.startsWith("ArrayList")) {
                    entityInfo.addIssue(new Issue(
                        IssueSeverity.WARNING,
                        "MANY_TO_MANY_LIST",
                        "@ManyToMany on List '" + fieldName + "' makes Hibernate delete and re-insert the entire "
                                + "join table whenever the collection changes.",
                        "Use a Set instead of a List for @ManyToMany associations."
                    ));
                }
                String cascade = annotationPairValue(ann, "cascade");
                if (cascade != null && (cascade.contains("ALL") || cascade.contains("REMOVE"))) {
                    entityInfo.addIssue(new Issue(
                        IssueSeverity.WARNING,
                        "CASCADE_REMOVE_ON_MANY_TO_MANY",
                        "cascade = ALL/REMOVE on @ManyToMany '" + fieldName + "' cascades deletes to entities that "
                                + "are shared with other parents.",
                        "Never cascade REMOVE on @ManyToMany; cascade only PERSIST/MERGE if anything."
                    ));
                }
            });

            field.getAnnotationByName("OneToMany").ifPresent(ann -> {
                boolean hasMappedBy = annotationPairValue(ann, "mappedBy") != null;
                boolean hasJoinColumn = field.getAnnotationByName("JoinColumn").isPresent();
                if (!hasMappedBy && !hasJoinColumn) {
                    entityInfo.addIssue(new Issue(
                        IssueSeverity.WARNING,
                        "UNIDIRECTIONAL_ONE_TO_MANY",
                        "Unidirectional @OneToMany '" + fieldName + "' (no mappedBy, no @JoinColumn) creates an extra "
                                + "join table and inefficient extra DML.",
                        "Make it bidirectional with mappedBy on the @ManyToOne side, or add @JoinColumn for a FK mapping."
                    ));
                }
            });
        });
    }

    /**
     * @Enumerated defaults to ORDINAL, which stores positional ints - reordering or inserting
     * an enum constant silently corrupts existing rows.
     */
    private void checkEnumMappings(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        classDecl.getFields().forEach(field -> field.getAnnotationByName("Enumerated").ifPresent(ann -> {
            String value = annotationPairValue(ann, "value");
            // Marker @Enumerated or @Enumerated(ORDINAL) both persist the ordinal.
            boolean isString = value != null && value.toUpperCase().contains("STRING");
            if (!isString) {
                String fieldName = field.getVariables().isEmpty()
                        ? "?" : field.getVariables().get(0).getNameAsString();
                entityInfo.addIssue(new Issue(
                    IssueSeverity.WARNING,
                    "ORDINAL_ENUM",
                    "@Enumerated on '" + fieldName + "' persists the ORDINAL (default) - reordering the enum corrupts stored data.",
                    "Use @Enumerated(EnumType.STRING) so values are stored by name."
                ));
            }
        }));
    }

    /**
     * Reads a member value from a normal annotation; returns null for marker annotations
     * or when the requested member is absent.
     */
    private String annotationPairValue(com.github.javaparser.ast.expr.AnnotationExpr ann, String member) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(member)) {
                    return pair.getValue().toString();
                }
            }
        }
        // @Foo(VALUE) is shorthand for @Foo(value = VALUE).
        if (ann instanceof SingleMemberAnnotationExpr single && member.equals("value")) {
            return single.getMemberValue().toString();
        }
        return null;
    }

    private void checkMissingVersionField(ClassOrInterfaceDeclaration classDecl, EntityInfo entityInfo) {
        boolean hasVersion = classDecl.getFields().stream()
                .anyMatch(f -> f.getAnnotationByName("Version").isPresent());
        if (!hasVersion) {
            entityInfo.addIssue(new Issue(
                IssueSeverity.INFO,
                "NO_OPTIMISTIC_LOCK",
                "No @Version field - concurrent updates can silently overwrite each other (lost update).",
                "Add a @Version Long/Integer field to enable optimistic locking where writes contend."
            ));
        }
    }
}
