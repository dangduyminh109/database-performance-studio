# Progress

Database Performance Studio v0.1 runs as a local Java 21 and Next.js application with separate PostgreSQL metadata and disposable Docker sandboxes. Core workflows are implemented from project setup through generated datasets, SQL, index variants, benchmark analysis, and load testing.

## Verification

- The Gradle 8.14.3 wrapper, Java 21, Spring Boot 3.5.16, PostgreSQL JDBC 42.7.15, and PostgreSQL 17.12 were used for Java and integration checks.
- Next.js 15.5.29 compiled with strict TypeScript. The production build uses locally bundled Monaco and font files.
- PostgreSQL integration runs exercised setup, generation, query previews, SSE, cancellation, named index variants, original index restoration, protected index checks, permanent index removal, concurrent load, source allowlist and browser-origin checks, JSON/CSV/HTML reports, plain SQL dump import, and sandbox stop/start.
- The demo measured six scenarios and stored 72 raw iterations at 1,000, 10,000, and 100,000 rows per table. The baseline produced sequential scans and the B-tree expression index produced index scans on the disposable Commerce setup. At 100,000 rows the final smoke run measured 26.945 ms mean client latency for the baseline and 0.410 ms for the expression index. These are local observations.
- Streaming COPY generated and validated exactly 1,000,000 customers and 1,000,000 foreign-key-linked orders in the same sandbox.
- An INSERT benchmark confirmed rollback and sequence restoration. An abrupt API restart during a benchmark marked the interrupted run failed and restored the original indexes from the checkpoint.
- A separate source test verified encrypted connection handling, PostgreSQL metadata, schema-only clone, schema-and-data clone, and unchanged source row counts.
- Rechecked on 2026-11-26. All three Java tests, strict TypeScript, the production build, both source clone modes, and the PostgreSQL smoke suite with one million rows in each of two related tables passed.
- Automated Chromium verified sample chart rendering, theme and language switching, the 920 px desktop layout, Escape dismissal of the run dialog, and same-origin access to the Java API. A benchmark started from the browser produced six scenarios and 72 measurements. Plan comparison, node details, CSV download, Monaco import and formatting, SQL execution, and optional index toggling all worked.
- The browser accessibility tree showed no accessible name for the run dialog or the index switch. Chart tooltips are pointer-driven and the page provides a results table. The browser requested a missing favicon. Headless Chromium was used because the shared Cloud Desktop browser runtime was unavailable.

## Limits and continuation

- English is the default. Vietnamese covers navigation, common controls, settings, and the benchmark summary. Some help, dynamic messages, advanced form instructions and metric labels are English.
- Dataset strategies cover built-in scalar types, bounded parent-key sampling, and explicit cycle overrides. Custom SQL expressions are evaluated once per configured column. Schema-specific composite uniqueness, complex checks, unsupported PostgreSQL types, trigger behavior and cycles can require explicit strategies. PostgreSQL enforces constraints at insertion/commit.
- The graphical index builder supports structured keys, lower/upper expression keys and simple predicates. Specialized PostgreSQL operator classes, collations and storage options can be imported with schema DDL.
- Recommendations use bounded deterministic rules and conservative catalog observations. Arbitrary expression inference, generalized partial-index synthesis, selectivity modeling and workload-wide index advice remain open.
- Jobs survive as metadata across a restart. Interrupted benchmark results retain completed scenarios and a recovery checkpoint for index restoration.
- Host hardware counters, HypoPG, pgbench, LLM providers, PDF export, and public multi-user operation are outside this release. JSON, CSV and static HTML are supported.
- Clone tests used PostgreSQL 17. The UI offers images 15–18. Cross-version extension and dump compatibility depends on the selected image and the source.
