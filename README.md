# Database Performance Studio

Database Performance Studio is a local PostgreSQL tool for reproducible performance experiments. Generate sandbox data, compare actual index configurations, and inspect client timings and PostgreSQL execution plans without changing the source database.

## Run locally

Requirements are a Java 21 JDK, Node.js 22 or newer, npm, OpenSSL, and a local Linux Docker Engine with the Compose plugin. The backend uses Docker to provision PostgreSQL sandboxes.

From this directory:

```sh
./scripts/initialize-env.sh
docker compose up -d --wait metadata
```

Start the backend in one terminal:

```sh
set -a
. ./.env
set +a
cd backend
./gradlew bootRun
```

Start the frontend in another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open [Database Performance Studio](http://127.0.0.1:3000). The starting view shows labeled sample results. **Run benchmark → Run demo benchmark** provisions a disposable PostgreSQL database and collects real results. To bring your own schema, create a project, add a PostgreSQL source connection, and clone it into a sandbox.

The API listens on port 8080 and the metadata database on port 5433. OpenAPI and Swagger UI are available at [Swagger UI](http://127.0.0.1:8080/swagger-ui.html). Use **Sandboxes** to stop or delete disposable databases. Use `docker compose stop metadata` to stop the metadata database while preserving its volume.

## Workspace

- Inspect schemas, columns, constraints, statistics, extensions, indexes and foreign-key relationships.
- Generate seeded datasets using streaming PostgreSQL COPY. Specify per-table counts and column rules for domain-specific constraints.
- Work in Monaco SQL tabs with saved queries, JDBC parameters, capped previews, and JSON execution plans.
- Toggle optional indexes inside a sandbox with reversible DROP and CREATE operations. Save named variants and create candidate indexes with the builder.
- Benchmark a matrix of queries, dataset sizes and index variants. Compare client latency, separately instrumented plans, storage, throughput and historical runs.
- Run concurrent load workloads with ramp-up, latency percentiles, error rates, and sampled lock contention. Export benchmark results in JSON, CSV or HTML.

The studio is intended for one local operator. Add permitted source hostnames to `STUDIO_SOURCE_HOSTS` before restarting the API. Keep the encryption key with the metadata backup. See [security](docs/SECURITY.md) and [benchmark methodology](docs/BENCHMARK_METHODOLOGY.md) for operational boundaries.

## Verification

```sh
(cd backend && ./gradlew test bootJar)
(cd frontend && npm run typecheck && npm run build)
node scripts/smoke.mjs
```

The smoke test requires a running backend and Docker. It creates and deletes a disposable project. The [test plan](docs/TEST_PLAN.md) covers the separate source-clone check. See [progress and limitations](docs/PROGRESS.md) for verified behavior and open scope.
