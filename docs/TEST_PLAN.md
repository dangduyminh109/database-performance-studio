# Test plan

## Build and automated checks

- Run `(cd backend && ./gradlew test bootJar)` with Java 21. Tests cover AES-GCM, SQL command validation and qualification, and evidence-based plan findings.
- Run `(cd frontend && npm ci && npm run typecheck && npm run build)`. The build checks strict TypeScript and prepares bundled local Monaco assets.
- Run `node scripts/smoke.mjs` after starting the metadata service and Java API. It checks provisioning, catalog discovery, index building, removal, toggling and protection, SSE progress, six measured scenarios, CSV and HTML exports, plain dump import, query preview, cancellation, concurrent load, source host rejection, browser-origin checks, data persistence across stop/start, and cleanup.
- To exercise both clone modes, seed a disposable PostgreSQL 17 database named `smoke_source` with a table `inventory` that holds two rows. Export `STUDIO_TEST_SOURCE_PORT`, `STUDIO_TEST_SOURCE_PASSWORD` and optionally `STUDIO_TEST_SOURCE_USER`, then run `node scripts/clone-smoke.mjs`. The test verifies that passwords are absent from responses, schema-only cloning has zero rows, full cloning has two rows, and the source count remains unchanged.

Run the main smoke suite with `STUDIO_VERIFY_LARGE_DATASET=1` to generate and verify one million rows in each of two related tables.

## Browser acceptance

Run the sample, create or switch projects, exercise dark and light themes, compare and inspect a plan, import and format a SQL worksheet, change an optional index and verify its actual state, and download a report. Check narrow desktop layout and keyboard operation of dialogs and charts. Browser verification is recorded in PROGRESS.md.
