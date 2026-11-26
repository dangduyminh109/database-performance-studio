# Product requirements

Database Performance Studio supports the developer workflow from schema discovery to reproducible query and index experiments. It is a local, single-operator workbench.

## Acceptance criteria

1. An operator can manage projects and encrypted PostgreSQL connections and discover source metadata using read-only sessions.
2. An operator can create, clone, start, stop, reset and delete a Docker sandbox, and import a supported plain SQL dump.
3. A seeded configuration can generate rows in dependency order with bounded memory, including selected tables and explicit column strategies for ambiguous constraints.
4. The operator can save and parameterize SQL, execute it in a sandbox, or inspect an execution plan and a limited result preview.
5. Optional indexes can be built, removed, dropped and restored. Protected indexes remain in place. Named configurations can be compared against equivalent seeded data.
6. The engine can warm, time, cancel and store a benchmark matrix, preserve raw timings, and restore index state after testing.
7. An operator can view actual measurements, historical differences and plan details, use evidence-based recommendations, and export reports.
8. A bounded, duration-based load test can exercise concurrent saved workloads and record throughput, sampled latency and lock-related metrics.
9. Theme, primary localized UI controls, progress and error states support normal local use. Sample values have a visible label.

Experimental and deferred coverage is recorded in [PROGRESS.md](PROGRESS.md).
