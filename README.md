# JPA Query Analyzer — Maven Plugin

Static analysis for JPA / Spring Data in a Spring Boot project. Runs as a Maven
goal, scans your source for repositories and entities, inspects your Spring/Hibernate
configuration, and reports query performance smells grouped by severity — as a
terminal dashboard, or as JSON / SARIF for CI.

It parses source (via JavaParser) and reads `application.properties` / `.yml`, so it
runs without a database, without starting the app, and without running any query.

Highlights:
- **40 rules** across JPQL/native `@Query`, Spring Data derived methods, `@Entity`
  mappings, and Spring/Hibernate configuration.
- **CI-ready output:** SARIF for GitHub code scanning, JSON for custom tooling, and
  configurable build failure on critical/warning issues.
- **Baseline support:** adopt it on a legacy codebase and fail only on *new* issues.

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
- `NOT_IN_SUBQUERY` — `NOT IN (SELECT …)` → returns nothing on a NULL row; prefer `NOT EXISTS`
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
- `STREAM_QUERY_RESOURCE` — returns `Stream<>` → open cursor/connection until closed
- `DERIVED_LIKE_CONTAINS` — `Containing` keyword → `LIKE '%term%'` leading-wildcard scan

**Configuration (`application.properties` / `application.yml`, merged across profiles):**
- `OPEN_SESSION_IN_VIEW` — `spring.jpa.open-in-view` unset/`true` → connection held for the whole request, hides view-layer N+1
- `NO_BATCH_INSERTS` — `hibernate.jdbc.batch_size` unset/≤1 → JDBC batching off
- `BATCH_WITHOUT_ORDERED_STATEMENTS` — batching on but `order_inserts`/`order_updates` not both true
- `SQL_LOGGING_ENABLED` — `show-sql=true` → synchronous `System.out` logging
- `DDL_AUTO_RISK` — `ddl-auto` = `create`/`create-drop` (CRITICAL) or `update` (WARNING)

**`@Entity` mappings:**
- `EAGER_COLLECTION_FETCH` — `@OneToMany/@ManyToMany(fetch = EAGER)`
- `DEFAULT_EAGER_TO_ONE` — `@ManyToOne`/`@OneToOne` with no `fetch` → defaults to EAGER (N+1)
- `EXPLICIT_EAGER_FETCH` — eager single-valued refs (N+1)
- `ONE_TO_ONE_NO_MAPSID` — owning `@OneToOne` without `@MapsId` → extra column + eager secondary select
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
| `jpa.analyzer.format` | `terminal` | output format: `terminal`, `json`, or `sarif` |
| `jpa.analyzer.outputFile` | _(stdout)_ | file to write `json`/`sarif` output to |
| `jpa.analyzer.baseline` | _(none)_ | baseline file of accepted issues; matches are suppressed |
| `jpa.analyzer.writeBaseline` | `false` | (re)generate the baseline from current findings, then exit without failing |
| `jpa.analyzer.resolveTypes` | `true` | resolve types against the project classpath to catch repositories that extend a custom base interface; set `false` for name-only matching |

```bash
mvn jpa-analyzer:analyze -Djpa.analyzer.failOnCritical=true
```

### Machine-readable output & CI

Emit SARIF for GitHub code scanning (Security tab + inline PR annotations), or JSON for your own tooling:

```bash
mvn jpa-analyzer:analyze -Djpa.analyzer.format=sarif -Djpa.analyzer.outputFile=target/jpa.sarif
mvn jpa-analyzer:analyze -Djpa.analyzer.format=json  -Djpa.analyzer.outputFile=target/jpa.json
```

**Baseline (adopt on a legacy codebase):** snapshot today's issues as accepted, then fail only on *new* ones. Fingerprints are line-independent, so unrelated edits don't reintroduce a baselined issue.

```bash
# 1. record the current issues as the accepted baseline
mvn jpa-analyzer:analyze -Djpa.analyzer.writeBaseline=true -Djpa.analyzer.baseline=jpa-baseline.json
# 2. in CI: suppress baselined issues, fail on anything new
mvn jpa-analyzer:analyze -Djpa.analyzer.baseline=jpa-baseline.json -Djpa.analyzer.failOnWarning=true
```

### Type resolution

By default the plugin resolves types against the project's compile classpath (so the goal
triggers Maven dependency resolution). This catches repositories that reach a Spring Data
supertype through a **custom base interface** — e.g. `CustomerRepository extends BaseRepository`
where `BaseRepository extends JpaRepository` — which plain name matching misses. If resolution
is slow or undesirable, disable it with `-Djpa.analyzer.resolveTypes=false`; detection then falls
back to matching direct supertype names.

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
(`JpqlAnalyzer`, `EntityAnalyzer`, `SpringDataMethodAnalyzer`, or `ConfigAnalyzer`)
and calling `addIssue(new Issue(severity, ruleId, why, fix))`. Every reporter
(terminal, JSON, SARIF) and the baseline pick it up automatically.
