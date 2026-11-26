# Decisions

- Use a local-only deployment with a dedicated Compose metadata service and Docker-backed PostgreSQL sandboxes. Docker CLI integration keeps the permitted operations explicit and leaves the daemon with the backend.
- Use a bounded in-process executor with durable job state instead of Redis. A restart marks interrupted jobs as failed.
- Use PostgreSQL JDBC's SQL tokenizer for the worksheet command gate and JDBC parameters for values. PostgreSQL roles and container boundaries govern data access.
- Use a constrained index builder so the application can construct quoted DDL. Catalog-defined original DDL is preserved for index restoration.
- Run ordinary iterations separately from EXPLAIN ANALYZE; preserve both in reports.
- Treat English as the default locale. Vietnamese covers the navigation, settings and benchmark summary in this release.
- Ship a clearly labeled illustrative dataset for discovery and provide a separate command that actually provisions and measures the practice benchmark.
