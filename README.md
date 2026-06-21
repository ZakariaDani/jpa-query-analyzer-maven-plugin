# JPA Query Analyzer — Maven Plugin

Static analysis for JPA / Spring Data in a Spring Boot project. Runs as a Maven
goal, scans your source for repositories and entities, and prints a terminal
report of query performance smells, grouped by severity, with a small analytics
dashboard.

It parses source (via JavaParser), so it runs without a database, without
starting the app, and without running any query.

## What it inspects

**`@Query` / `@NamedQuery` (JPQL):**
- `FETCH_JOIN_NO_DISTINCT` — JOIN FETCH without DISTINCT (duplicate root rows)
- `FETCH_JOIN_NO_PAGINATION` — JOIN FETCH that will be paged in memory
- `MULTIPLE_COLLECTION_FETCH` — 2+ JOIN FETCH → Cartesian product / `MultipleBagFetchException`
- `NO_WHERE_CLAUSE` — full-table-scan risk
- `CARTESIAN_PRODUCT_RISK` — comma-joined entities in FROM
- `LEADING_WILDCARD_LIKE` — `LIKE '%x'` can't use a B-tree index
- `FUNCTION_ON_WHERE_COLUMN` — function wrapping a column → non-sargable predicate
- `IN_CLAUSE` — large IN lists degrade / break
- `IN_SUBQUERY` — `IN (SELECT …)` → prefer `EXISTS` / JOIN
- `IMPLICIT_JOIN` — multi-level path nav (`e.parent.name`) → uncontrolled inner join
- `ORDER_BY_UNBOUNDED` — sorting an unbounded result set
- `BULK_MUTATION_PC_BYPASS` — bulk `UPDATE`/`DELETE` leaves loaded entities stale

**`@Query(nativeQuery = true)`:**
- `NATIVE_QUERY` — portability / injection note
- `SELECT_STAR` — over-fetching

**Spring Data derived methods:**
- `FINDALL_NO_PAGINATION`
- `UNBOUNDED_COLLECTION_RETURN` — returns `List<>` with no `Pageable`
- `COMPLEX_DERIVED_METHOD` — too many predicates encoded in the name
- `DERIVED_DELETE_LOADS_ENTITIES` — `deleteBy…` fetches then deletes row-by-row (N+1)
- `TOP_WITHOUT_ORDERBY` — `findFirst`/`findTop` with no `OrderBy` → non-deterministic row

**`@Entity` mappings:**
- `EAGER_COLLECTION_FETCH` — `@OneToMany/@ManyToMany(fetch = EAGER)`
- `EXPLICIT_EAGER_FETCH` — eager single-valued refs (N+1)
- `IDENTITY_GENERATOR_BATCHING` — `@GeneratedValue(IDENTITY)` disables JDBC insert batching
- `AUTO_GENERATOR_STRATEGY` — `@GeneratedValue(AUTO)` may pick the slow `TABLE` generator
- `MANY_TO_MANY_LIST` — `@ManyToMany` over a `List` re-inserts the whole join table on change
- `UNIDIRECTIONAL_ONE_TO_MANY` — `@OneToMany` with no `mappedBy`/`@JoinColumn` → extra join table
- `CASCADE_REMOVE_ON_MANY_TO_MANY` — cascade `ALL`/`REMOVE` deletes shared rows
- `ORDINAL_ENUM` — `@Enumerated` default `ORDINAL` corrupts on enum reorder (use `STRING`)
- `LOMBOK_TOSTRING_ON_ENTITY` / `LOMBOK_EQUALS_ON_ENTITY`
- `NO_OPTIMISTIC_LOCK` — no `@Version`

## Build & install

```bash
mvn clean install
```

## Run it against a project

One-off, no POM changes:

```bash
mvn com.zakdev.data.jpaanalyzer:jpa-query-analyzer-maven-plugin:1.0.0-M1:analyze
```

Or add to the target project's `pom.xml` and call `mvn jpa-analyzer:analyze`:

```xml
<plugin>
  <groupId>com.zakdev.data.jpaanalyzer</groupId>
  <artifactId>jpa-query-analyzer-maven-plugin</artifactId>
  <version>1.0.0-M1</version>
</plugin>
```

Bind it to the build so CI fails on regressions:

```xml
<executions>
  <execution>
    <goals><goal>analyze</goal></goals>
    <configuration>
      <failOnCritical>true</failOnCritical>
    </configuration>
  </execution>
</executions>
```

## Options

| Property | Default | Effect |
|---|---|---|
| `jpa.analyzer.failOnCritical` | `false` | fail build on any CRITICAL |
| `jpa.analyzer.failOnWarning` | `false` | fail build on any WARNING+ |
| `jpa.analyzer.noColor` | `false` | disable ANSI colour |
| `jpa.analyzer.skip` | `false` | skip analysis |

```bash
mvn jpa-analyzer:analyze -Djpa.analyzer.failOnCritical=true
```

## Scope / honest limits

This is **static** analysis of query strings and mappings. It does **not**:
- capture queries actually executed at runtime,
- detect N+1 caused by lazy access in service code,
- read real execution plans.

For those, pair it with a runtime approach (Hibernate `generate_statistics`,
`datasource-proxy`, or p6spy). The two are complementary: static catches the
declared-query and mapping problems early; runtime catches the access-pattern
ones. A future `runtime` mode could hook a test-scope agent to log executed SQL.

## Extending

Add a rule by dropping a `checkX(...)` method into the relevant analyzer
(`JpqlAnalyzer`, `EntityAnalyzer`, `SpringDataMethodAnalyzer`) and calling
`addIssue(new Issue(severity, ruleId, why, fix))`. The reporter and analytics
pick it up automatically.
