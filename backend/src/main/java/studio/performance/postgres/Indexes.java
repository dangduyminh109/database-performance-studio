package studio.performance.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.shared.Input;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

@Service
public class Indexes {
    private final Store store;
    private final Postgres pg;

    public Indexes(Store store, Postgres pg) { this.store = store; this.pg = pg; }

    public ArrayNode inventory(Resource sandbox, Target target) throws Exception {
        try (Connection c = pg.open(target)) {
            ArrayNode indexes = pg.indexes(c);
            Set<String> names = new HashSet<>();
            indexes.forEach(i -> {
                ((ObjectNode) i).put("enabled", true);
                names.add(key(i));
            });
            for (Resource record : store.list(sandbox.projectId, "index-definition")) {
                if (!record.data.path("sandboxId").asText().equals(sandbox.id.toString())) continue;
                JsonNode index = record.data.path("index");
                if (!names.contains(key(index))) indexes.add(((ObjectNode) index.deepCopy()).put("enabled", false).put("bytes", 0));
            }
            return indexes;
        }
    }

    public void toggle(Resource sandbox, Target target, JsonNode request) throws Exception {
        String schema = Input.string(request, "schema", 63);
        String name = Input.string(request, "name", 63);
        if (!request.path("enabled").isBoolean()) throw Input.invalid("Specify the requested index state.");
        boolean enabled = request.path("enabled").asBoolean();
        JsonNode index = null;
        for (JsonNode item : inventory(sandbox, target)) {
            if (item.path("schema").asText().equals(schema) && item.path("name").asText().equals(name)) index = item;
        }
        if (index == null) throw Input.invalid("Index not found.");
        if (index.path("enabled").asBoolean() == enabled) return;
        if (index.path("protected").asBoolean()) throw Input.invalid("This index is required by a PostgreSQL dependency.");
        try (Connection c = pg.open(target); Statement st = c.createStatement()) {
            st.setQueryTimeout(90);
            if (!enabled) {
                String recordName = definitionName(sandbox, index);
                ObjectNode data = pg.json.createObjectNode().put("sandboxId", sandbox.id.toString()).set("index", index);
                Resource record = store.resources.findByProjectIdAndKindAndName(sandbox.projectId, "index-definition", recordName)
                        .orElseGet(() -> store.create(sandbox.projectId, "index-definition", recordName, data));
                record.data = data;
                store.save(record);
                st.execute("drop index " + Input.relation(schema, name));
            } else st.execute(index.path("definition").asText());
            c.commit();
        }
    }

    public void remove(Resource sandbox, Target target, JsonNode request) throws Exception {
        String schema = Input.string(request, "schema", 63);
        String name = Input.string(request, "name", 63);
        for (JsonNode index : inventory(sandbox, target)) {
            if (!index.path("schema").asText().equals(schema) || !index.path("name").asText().equals(name)) continue;
            if (index.path("protected").asBoolean()) throw Input.invalid("This index is required by a PostgreSQL dependency.");
            if (index.path("enabled").asBoolean()) {
                try (Connection c = pg.open(target); Statement st = c.createStatement()) {
                    st.setQueryTimeout(90);
                    st.execute("drop index " + Input.relation(schema, name));
                    c.commit();
                }
            }
            store.resources.findByProjectIdAndKindAndName(sandbox.projectId, "index-definition", definitionName(sandbox, index))
                    .ifPresent(store.resources::delete);
            return;
        }
        throw Input.invalid("Index not found.");
    }

    private String definitionName(Resource sandbox, JsonNode index) {
        return UUID.nameUUIDFromBytes((sandbox.id + "|" + key(index)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public String definition(JsonNode request) {
        String schema = Input.text(request, "schema", "public", 63);
        String table = Input.string(request, "table", 63);
        String name = Input.string(request, "name", 63);
        String method = Input.text(request, "method", "btree", 8);
        if (!Set.of("btree", "hash", "gin", "gist", "spgist", "brin").contains(method)) throw Input.invalid("Unsupported index method.");
        List<String> fields = columns(request.path("columns"), 8);
        if (fields.isEmpty()) throw Input.invalid("Choose at least one indexed column.");
        String expression = Input.text(request, "expression", "", 10);
        if (!expression.isBlank()) {
            if (fields.size() != 1 || !Set.of("lower", "upper").contains(expression)) throw Input.invalid("Choose a supported single-column expression.");
            fields.set(0, expression + "(" + fields.getFirst() + ")");
        }
        if (request.path("trigram").asBoolean()) {
            if (!"gin".equals(method) || fields.size() != 1) throw Input.invalid("Trigram indexes use one GIN key.");
            fields.set(0, fields.getFirst() + " gin_trgm_ops");
        }
        List<String> include = columns(request.path("include"), 8);
        JsonNode predicate = Input.object(request, "predicate");
        String where = "";
        if (!predicate.isEmpty()) {
            String column = Input.identifier(Input.string(predicate, "column", 63));
            String operation = Input.string(predicate, "operator", 20);
            where = switch (operation) {
                case "is null", "is not null" -> " where " + column + " " + operation;
                case "=" -> " where " + column + " = '" + Input.string(predicate, "value", 300).replace("'", "''") + "'";
                default -> throw Input.invalid("Choose a supported partial index predicate.");
            };
        }
        return "create " + (request.path("unique").asBoolean() ? "unique " : "") + "index " + Input.identifier(name) + " on "
                + Input.relation(schema, table) + " using " + method + " (" + String.join(", ", fields) + ")"
                + (include.isEmpty() ? "" : " include (" + String.join(", ", include) + ")") + where;
    }

    private List<String> columns(JsonNode value, int limit) {
        List<String> items = new ArrayList<>();
        if (value.isMissingNode()) return items;
        if (!value.isArray() || value.size() > limit) throw Input.invalid("Invalid index columns.");
        for (JsonNode item : value) {
            if (!item.isTextual()) throw Input.invalid("Invalid index column.");
            items.add(Input.identifier(item.asText()));
        }
        return items;
    }

    public double create(Connection c, JsonNode spec) throws Exception {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(120);
            if (spec.path("trigram").asBoolean()) st.execute("create extension if not exists pg_trgm");
            long start = System.nanoTime();
            st.execute(definition(spec));
            return (System.nanoTime() - start) / 1_000_000d;
        }
    }

    public static String key(JsonNode index) { return index.path("schema").asText() + "." + index.path("name").asText(); }
}
