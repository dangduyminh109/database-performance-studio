# Implementation roadmap

| Phase | Implemented scope | Acceptance evidence |
| --- | --- | --- |
| Discovery | Runtime inventory, requirements, architecture, metadata and test design | Docs and dependency versions |
| Foundation | Java 21, Gradle wrapper, Next.js, Flyway, PostgreSQL Compose service, CI | Unit tests and production builds |
| Sandbox workflow | Projects, encrypted sources, catalog, cloning, SQL imports, seeded generation, SQL workspace | API and clone smoke suites |
| Index experiments | Catalog inventory, protected and optional index states, builder, named variants and restoration | PostgreSQL smoke suite |
| Benchmarks and analysis | Matrix, SSE and cancellation, raw timings, JSON plans, recommendations, historical comparisons and exports | Six measured scenarios and report verification |
| Load testing | Concurrent saved workloads, ramp-up, duration, latency, errors and sampled contention | PostgreSQL smoke suite |

Open work and environment verification gaps are tracked in [PROGRESS.md](PROGRESS.md).
