package com.zakdev.data.jpaanalyzer.model;

public enum QueryType {
    JPQL("JPQL"),
    NATIVE_SQL("Native SQL"),
    SPRING_DATA_DERIVED("Derived"),
    NAMED_QUERY("Named");

    private final String label;

    QueryType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
