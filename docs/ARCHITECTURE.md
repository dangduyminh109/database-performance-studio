# Architecture

Database Performance Studio is a Spring Boot modular monolith with a Next.js client on the same workstation. The HTTP controller adapts inbound requests. Outbound adapters use Spring Data JPA and JDBC for metadata, PostgreSQL JDBC for dynamic targets, and a restricted Docker CLI adapter for sandbox lifecycle. Spring Security enforces loopback-oriented browser access rules. The frontend uses TanStack Query for remote state, MUI for controls, Monaco for SQL, ECharts for measurements, and React Flow for schema and plan graphs.

| Module | Responsibility |
| --- | --- |
| `metadata` | Projects, typed project resources and persistence |
| `postgres` | Source connections, PostgreSQL catalog queries, parsed JDBC SQL and index DDL |
| `sandbox` | Container lifecycle, controlled dumps, restoration, import and workload isolation |
| `dataset` | Dependency ordering, column strategies and streaming COPY |
| `benchmark` | Jobs, SQL execution, scenario matrices, result storage, load testing and recommendation rules |
| `shared` | Validation, encryption and the database engine connection and catalog port |
| `web` | Local HTTP and SSE adapters, security and error mapping |

The engine port covers connection, server information and catalog discovery. PostgreSQL uses dedicated implementations for COPY, catalog metrics, index operators, native plans and container images. Other engines would add their own adapters and domain-level normalization for plans, datasets and index capabilities.

Jobs store their state in PostgreSQL and execute on a bounded two-worker pool. Sandbox changes and benchmarks use a per-sandbox exclusive lock in the single Java process. A benchmark stores a recovery checkpoint before changing indexes. When it closes, it restores the baseline and clears the checkpoint. The scheduler retries unfinished restores after a process restart. SQL preview rows are transient. The source, metadata server and sandboxes have different connection paths and credentials.
