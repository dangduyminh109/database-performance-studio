package studio.performance.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.shared.Input;
import studio.performance.shared.Vault;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class Connections {
    private final Store store;
    private final Postgres pg;
    private final Vault vault;
    private final Set<String> allowedHosts;

    public Connections(Store store, Postgres pg, Vault vault, @Value("${studio.source-hosts}") String hosts) {
        this.store = store;
        this.pg = pg;
        this.vault = vault;
        allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public Resource save(UUID project, UUID id, JsonNode request) {
        store.project(project);
        String name = Input.string(request, "name", 120);
        ObjectNode data = pg.json.createObjectNode();
        String url = Input.text(request, "connectionString", "", 600);
        String host = Input.text(request, "host", "localhost", 253);
        int port = Input.number(request, "port", 5432, 1, 65535);
        String database = Input.text(request, "database", "", 63);
        String sslMode = Input.text(request, "sslMode", "prefer", 20);
        if (!url.isBlank()) {
            try {
                URI uri = URI.create(url.startsWith("jdbc:") ? url.substring(5) : url);
                if (!Set.of("postgresql", "postgres").contains(uri.getScheme()) || uri.getHost() == null ||
                        uri.getUserInfo() != null || uri.getFragment() != null || uri.getPath() == null) {
                    throw Input.invalid("Invalid connection string.");
                }
                host = uri.getHost();
                port = uri.getPort() < 0 ? 5432 : uri.getPort();
                database = uri.getPath().substring(1);
                if (uri.getRawQuery() != null) {
                    if (!uri.getRawQuery().matches("sslmode=[a-z-]+")) throw Input.invalid("Invalid connection string options.");
                    sslMode = uri.getRawQuery().substring(8);
                }
            } catch (RuntimeException ex) {
                throw Input.invalid("Use a PostgreSQL connection string without credentials or arbitrary driver options.");
            }
        }
        String username = Input.string(request, "username", 63);
        validate(host, port, database, sslMode);
        data.put("host", host.toLowerCase(Locale.ROOT)).put("port", port).put("database", database)
                .put("username", username).put("sslMode", sslMode)
                .put("timeoutSeconds", Input.number(request, "timeoutSeconds", 5, 1, 30));
        Resource connection = id == null ? store.create(project, "connection", name, data) : store.get(project, "connection", id);
        connection.name = name;
        connection.data = data;
        String password = Input.text(request, "password", "", 1000);
        if (!password.isEmpty() || connection.secret == null) connection.secret = vault.encrypt(connection.id, password);
        return store.save(connection);
    }

    private void validate(String host, int port, String database, String sslMode) {
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT)) || !host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")) {
            throw Input.invalid("Source host is outside the configured allowlist.");
        }
        if (port < 1 || port > 65535 || database.isBlank() || database.length() > 63 || database.contains("/") || database.contains("=") ||
                !Set.of("disable", "prefer", "require", "verify-ca", "verify-full").contains(sslMode)) {
            throw Input.invalid("Invalid database, port or SSL mode.");
        }
    }

    public Target target(Resource resource) {
        JsonNode d = resource.data;
        validate(d.path("host").asText(), d.path("port").asInt(), d.path("database").asText(), d.path("sslMode").asText());
        return new Target(d.path("host").asText(), d.path("port").asInt(), d.path("database").asText(),
                d.path("username").asText(), vault.decrypt(resource.id, resource.secret), d.path("sslMode").asText(),
                d.path("timeoutSeconds").asInt(), true);
    }
}
