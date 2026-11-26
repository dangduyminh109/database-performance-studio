# Metadata design

Application metadata lives in its own PostgreSQL database. Flyway is the schema authority and Hibernate validates mappings at startup.

| Relation | Purpose |
| --- | --- |
| `projects` | UUID, unique name, description, flexible configuration and audit timestamps |
| `resources` | Project-owned typed rows for connections, sandboxes, schema snapshots, dataset configurations, saved queries, index configurations and definitions, jobs, and load reports |
| `benchmark_scenarios` | Job-owned ordinal, JSONB metric summary and separate instrumented JSONB plan |
| `benchmark_iterations` | Scenario-owned ordinal, client milliseconds and returned/affected rows |

Resources have a uniqueness constraint on project, kind, and name, and have an index for project/kind history. Project deletion cascades through metadata. Index definitions are removed separately when a sandbox is reset or removed. Projects and resources use optimistic locking. Database credentials occupy a dedicated encrypted column excluded from API serialization. Other links, such as the source connection attached to a sandbox, live in type-validated JSONB and are checked in application services. Benchmark results normalize high-volume iteration rows while preserving PostgreSQL's evolving plan shape. Query previews are ephemeral.
