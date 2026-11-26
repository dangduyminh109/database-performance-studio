# Security

Run the studio on a single operator's local workstation. The API and frontend bind to loopback. The API checks Host, Origin and browser fetch metadata. There are no remote accounts or project access controls. Keep all three published service ports private.

## Credentials and source databases

- Store the required 32-byte AES-256 key outside source control. Credentials are stored using AES-GCM with a new random nonce for each encryption and the resource UUID as authenticated data. API responses omit ciphertext and plaintext. The UI never stores entered connection passwords in application state or browser storage.
- The Java adapter checks an explicit source hostname allowlist before opening a connection. Add only names managed by the operator. Standard JDBC TLS modes are available. Connection strings carry host, port, database and SSL mode only.
- Use a dedicated PostgreSQL source role with the minimum required catalog, schema and table read access. Database sessions are read-only. Cloning invokes pg_dump with read-only transaction defaults. The product executes interactive SQL only against sandbox targets.
- Clone archives and database names are passed through controlled Docker commands. The clone credential travels on stdin. PostgreSQL's client container must be able to reach the source over the Docker host network.

## Sandbox boundary

The backend provisions disposable official PostgreSQL containers with loopback-only ports, memory, CPU and process limits, unprivileged runtime and no host data mounts. Each sandbox has distinct admin and workload credentials. JDBC execution, imported SQL and pg_restore run as the limited sandbox workload role. A SQL command whitelist shapes the workspace interface. PostgreSQL role privileges, the separate server and the absence of source credentials from execution provide the boundary.

Only the Java process invokes Docker. Docker daemon access is powerful and should be provided to a trusted local operator. Imported SQL executes as the sandbox owner and can corrupt that sandbox. Confirm resets, deletions and committed writes in the UI. Job, SQL statement and connection timeouts limit stalled operations. Expired sandboxes are checked each minute. Docker-host disk volumes inherit the host's storage controls.

Previews cap rows and cell length and remain in process memory for 15 minutes. Benchmark reports store SQL-related metadata and raw JSON plans, which can contain literals, identifiers and values. Treat reports and metadata backups as local project data.
