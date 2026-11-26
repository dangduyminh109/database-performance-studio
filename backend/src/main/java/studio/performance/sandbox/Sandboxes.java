package studio.performance.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.postgres.Connections;
import studio.performance.postgres.Postgres;
import studio.performance.postgres.Target;
import studio.performance.shared.Input;
import studio.performance.shared.Vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class Sandboxes {
    private final Docker docker;
    private final Store store;
    private final Postgres pg;
    private final Vault vault;
    private final Connections connections;
    private final String host;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public Sandboxes(Docker docker, Store store, Postgres pg, Vault vault, Connections connections,
                     @Value("${studio.sandbox-host}") String host) {
        this.docker = docker;
        this.store = store;
        this.pg = pg;
        this.vault = vault;
        this.connections = connections;
        this.host = host;
        Arrays.setAll(locks, i -> new ReentrantLock());
    }

    private ReentrantLock lock(UUID id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    public interface Action<T> { T run() throws Exception; }

    public <T> T exclusive(UUID id, Action<T> action) throws Exception {
        ReentrantLock lock = lock(id);
        lock.lockInterruptibly();
        try {
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    public void whenIdle(UUID id, Action<Void> action) throws Exception {
        ReentrantLock lock = lock(id);
        if (!lock.tryLock()) return;
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public Resource reserve(UUID project, JsonNode request) {
        store.project(project);
        String name = Input.string(request, "name", 120);
        String version = Input.text(request, "version", "17", 2);
        String mode = Input.text(request, "mode", "empty", 16);
        if (!Set.of("15", "16", "17", "18").contains(version) || !Set.of("empty", "demo", "schema", "clone").contains(mode)) {
            throw Input.invalid("Choose a supported PostgreSQL version and sandbox mode.");
        }
        ObjectNode data = pg.json.createObjectNode().put("version", version).put("mode", mode).put("status", "queued")
                .put("memoryMb", Input.number(request, "memoryMb", 512, 128, 4096))
                .put("cpus", Input.number(request, "cpus", 1, 1, 4))
                .put("ttlHours", Input.number(request, "ttlHours", 24, 1, 168));
        data.put("expiresAt", Instant.now().plus(Duration.ofHours(data.path("ttlHours").asInt())).toString());
        if (Set.of("schema", "clone").contains(mode)) {
            UUID source = Input.uuid(request, "connectionId");
            store.get(project, "connection", source);
            data.put("connectionId", source.toString());
        }
        Resource sandbox = store.create(project, "sandbox", name, data);
        sandbox.secret = vault.encrypt(sandbox.id, vault.randomPassword() + ":" + vault.randomPassword());
        return store.save(sandbox);
    }

    public Resource provision(Resource sandbox) throws Exception {
        String container = name(sandbox);
        ObjectNode data = sandbox.data.deepCopy();
        String image = "postgres:" + data.path("version").asText() + "-alpine";
        data.put("status", "creating").remove("port");
        sandbox.data = data;
        sandbox = store.save(sandbox);
        try {
            String[] credentials = credentials(sandbox);
            docker.run(Duration.ofMinutes(4), null, "create", "--name", container, "--label", "app=database-performance-studio",
                    "--memory", data.path("memoryMb").asText() + "m", "--memory-swap", data.path("memoryMb").asText() + "m",
                    "--cpus", data.path("cpus").asText(), "--pids-limit", "128", "--security-opt", "no-new-privileges",
                    // Docker volumes persist across stop and start.
                    "-e", "PGDATA=/var/lib/postgresql/studio/pgdata", "--mount",
                    "type=volume,target=/var/lib/postgresql/studio",
                    "-p", "127.0.0.1::5432",
                    "-e", "POSTGRES_DB=studio", "-e", "POSTGRES_HOST_AUTH_METHOD=scram-sha-256",
                    "-e", "POSTGRES_PASSWORD_FILE=/run/studio-password", image,
                    "postgres", "-c", "max_connections=40", "-c", "temp_file_limit=256MB",
                    "-c", "idle_in_transaction_session_timeout=300000");
            Path secretDir = Files.createTempDirectory("studio-pg-");
            Path secret = secretDir.resolve("password");
            try {
                Files.writeString(secret, credentials[0]);
                Files.setPosixFilePermissions(secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                docker.run(Duration.ofSeconds(15), null, "cp", secret.toString(), container + ":/run/studio-password");
            } finally {
                Files.deleteIfExists(secret);
                Files.deleteIfExists(secretDir);
            }
            docker.run(Duration.ofSeconds(30), null, "start", container);
            String address = docker.run(Duration.ofSeconds(10), null, "port", container, "5432/tcp");
            data.put("port", Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
            data.put("status", "running").put("expiresAt", Instant.now().plus(Duration.ofHours(data.path("ttlHours").asInt())).toString());
            sandbox.data = data;
            awaitDatabase(sandbox);
            try (Connection c = pg.open(admin(sandbox)); Statement st = c.createStatement()) {
                st.execute("create role studio_work login nosuperuser nocreatedb nocreaterole noreplication password '" + credentials[1] + "'");
                st.execute("revoke create on schema public from public");
                st.execute("grant create on schema public to studio_work");
                st.execute("grant create on database studio to studio_work");
                c.commit();
            }
            String mode = data.path("mode").asText();
            if ("demo".equals(mode)) sample(sandbox);
            if (Set.of("schema", "clone").contains(mode)) {
                Resource source = store.get(sandbox.projectId, "connection", UUID.fromString(data.path("connectionId").asText()));
                Target target = connections.target(source);
                Path archive = Files.createTempFile("studio-dump-", ".archive");
                try {
                    docker.dump(target.host(), Integer.toString(target.port()), target.database(), target.username(),
                            target.sslMode(), target.password(), image, "schema".equals(mode), archive);
                    docker.restore(container, archive);
                } finally {
                    Files.deleteIfExists(archive);
                }
            }
            return store.save(sandbox);
        } catch (Exception ex) {
            data.put("status", "failed");
            sandbox.data = data;
            store.save(sandbox);
            try { removeContainer(sandbox); } catch (Exception ignored) {}
            throw ex;
        }
    }

    private void awaitDatabase(Resource sandbox) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Connection c = pg.open(admin(sandbox))) {
                if (c.isValid(2)) return;
            } catch (java.sql.SQLException ignored) {}
            Thread.sleep(300);
        }
        throw new IOException("Sandbox PostgreSQL did not become ready.");
    }

    private void sample(Resource sandbox) throws Exception {
        try (Connection c = pg.open(target(sandbox)); Statement st = c.createStatement()) {
            st.execute("""
                create table customers (
                    id bigint generated by default as identity primary key,
                    name text not null,
                    email text not null unique,
                    region varchar(20) not null,
                    created_at timestamp not null
                );
                create table orders (
                    id bigint generated by default as identity primary key,
                    customer_id bigint not null references customers(id),
                    status varchar(20) not null,
                    amount numeric(12, 2) not null,
                    created_at timestamp not null
                );
                create index orders_customer_id_idx on orders(customer_id)
                """);
            c.commit();
        }
    }

    public Resource lifecycle(Resource sandbox, String action) throws Exception {
        return exclusive(sandbox.id, () -> {
            Resource current = store.get(sandbox.projectId, "sandbox", sandbox.id);
            ObjectNode data = current.data.deepCopy();
            switch (action) {
                case "start", "restart" -> {
                    docker.run(Duration.ofSeconds(30), null, action, name(current));
                    String address = docker.run(Duration.ofSeconds(10), null, "port", name(current), "5432/tcp");
                    data.put("port", Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
                    current.data = data;
                    awaitDatabase(current);
                    data.put("status", "running");
                }
                case "stop" -> {
                    docker.run(Duration.ofSeconds(30), null, "stop", name(current));
                    data.put("status", "stopped");
                }
                case "reset" -> {
                    removeContainer(current);
                    clearIndexDefinitions(current);
                    return provision(current);
                }
                case "delete" -> {
                    removeContainer(current);
                    clearIndexDefinitions(current);
                    store.resources.delete(current);
                    return null;
                }
                default -> throw Input.invalid("Unknown sandbox action.");
            }
            current.data = data;
            return store.save(current);
        });
    }

    private void clearIndexDefinitions(Resource sandbox) {
        store.resources.findByProjectIdAndKindAndName(sandbox.projectId, "report", "index-recovery-" + sandbox.id)
                .ifPresent(store.resources::delete);
        for (Resource index : store.list(sandbox.projectId, "index-definition")) {
            if (sandbox.id.toString().equals(index.data.path("sandboxId").asText())) store.resources.delete(index);
        }
    }

    private void removeContainer(Resource sandbox) throws Exception {
        String present = docker.run(Duration.ofSeconds(10), null, "ps", "-aq", "--filter", "name=^/" + name(sandbox) + "$");
        if (!present.isEmpty()) docker.run(Duration.ofSeconds(30), null, "rm", "-fv", name(sandbox));
    }

    public Target target(Resource sandbox) {
        if (!"running".equals(sandbox.data.path("status").asText())) throw Input.invalid("Start the sandbox first.");
        return new Target(host, sandbox.data.path("port").asInt(), "studio", "studio_work", credentials(sandbox)[1], "disable", 3, false);
    }

    private Target admin(Resource sandbox) {
        return new Target(host, sandbox.data.path("port").asInt(), "studio", "postgres", credentials(sandbox)[0], "disable", 2, false);
    }

    private String[] credentials(Resource sandbox) { return vault.decrypt(sandbox.id, sandbox.secret).split(":"); }
    private String name(Resource sandbox) { return "dps-" + sandbox.id; }

    public int importSql(Resource sandbox, String script) throws Exception {
        return exclusive(sandbox.id, () -> {
            Resource current = store.get(sandbox.projectId, "sandbox", sandbox.id);
            try (Connection c = pg.open(target(current))) {
                int statements = new SqlImport().apply(c, script);
                c.commit();
                return statements;
            }
        });
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpired() {
        for (Resource sandbox : store.resources.findByKind("sandbox")) {
            String expiration = sandbox.data.path("expiresAt").asText();
            if (expiration.isBlank() || Instant.parse(expiration).isAfter(Instant.now()) || lock(sandbox.id).isLocked()) continue;
            try { lifecycle(sandbox, "delete"); } catch (Exception ignored) {}
        }
    }

    private static final class IOException extends java.io.IOException {
        private IOException(String message) { super(message); }
    }
}
