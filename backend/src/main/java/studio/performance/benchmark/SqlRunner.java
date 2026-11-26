package studio.performance.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import studio.performance.postgres.Postgres;
import studio.performance.postgres.Target;
import studio.performance.shared.Input;

import java.sql.*;
import java.util.Set;

@Service
public class SqlRunner {
    private final Postgres pg;
    public record Timing(double ms, long rows) {}

    public SqlRunner(Postgres pg) { this.pg = pg; }

    public static JsonNode parameters(JsonNode input) {
        JsonNode params = input.path("parameters");
        if (params.isMissingNode()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (!params.isArray() || params.size() > 64) throw Input.invalid("Use a JSON array of up to 64 query parameters.");
        return params;
    }

    public ObjectNode execute(Target target, String sql, JsonNode params, int timeout, String mode, boolean commit, Jobs.Control control) throws Exception {
        Postgres.validate(sql, target.readOnly());
        if (!Set.of("execute", "explain", "analyze", "buffers").contains(mode)) throw Input.invalid("Choose a query execution mode.");
        if (target.readOnly() && commit) throw Input.invalid("Source execution is read-only.");
        try (Connection c = pg.open(target)) {
            if (!"execute".equals(mode)) {
                JsonNode plan = explain(c, sql, params, timeout, mode, control);
                c.rollback();
                return pg.json.createObjectNode().set("plan", plan);
            }
            try (PreparedStatement st = pg.prepare(c, sql, params, timeout)) {
                st.setMaxRows(501);
                if (control != null) control.position(st);
                ArrayNode columns = pg.json.createArrayNode();
                ArrayNode rows = pg.json.createArrayNode();
                int updated = 0;
                boolean truncated = false;
                long start = System.nanoTime();
                if (st.execute()) {
                    try (ResultSet result = st.getResultSet()) {
                        ResultSetMetaData meta = result.getMetaData();
                        for (int col = 1; col <= meta.getColumnCount(); col++) columns.add(meta.getColumnLabel(col));
                        while (result.next()) {
                            if (rows.size() == 500) { truncated = true; break; }
                            ArrayNode row = rows.addArray();
                            for (int col = 1; col <= meta.getColumnCount(); col++) {
                                String value = result.getString(col);
                                if (value == null) row.addNull();
                                else row.add(value.length() > 512 ? value.substring(0, 512) + "…" : value);
                            }
                        }
                    }
                } else updated = st.getUpdateCount();
                double ms = (System.nanoTime() - start) / 1_000_000d;
                if (control != null) control.check();
                if (commit) c.commit(); else c.rollback();
                ObjectNode response = pg.json.createObjectNode().put("clientMs", ms).put("updated", updated).put("truncated", truncated);
                response.set("columns", columns);
                response.set("rows", rows);
                return response;
            } finally { if (control != null) control.position(null); }
        }
    }

    public Timing measure(Connection c, String sql, JsonNode params, int timeout, Jobs.Control control, boolean commit) throws Exception {
        try (PreparedStatement st = pg.prepare(c, sql, params, timeout)) {
            st.setFetchSize(512);
            control.position(st);
            long rows = 0;
            long start = System.nanoTime();
            if (st.execute()) {
                try (ResultSet result = st.getResultSet()) {
                    while (result.next()) {
                        if (++rows % 512 == 0) control.check();
                    }
                }
            } else rows = Math.max(0, st.getUpdateCount());
            double elapsed = (System.nanoTime() - start) / 1_000_000d;
            if (commit) c.commit(); else c.rollback();
            return new Timing(elapsed, rows);
        } finally { control.position(null); }
    }

    public JsonNode explain(Connection c, String sql, JsonNode params, int timeout, String mode, Jobs.Control control) throws Exception {
        String options = switch (mode) {
            case "explain" -> "format json";
            case "analyze" -> "analyze, format json";
            default -> "analyze, buffers, wal, verbose, format json";
        };
        try (PreparedStatement st = pg.prepare(c, "explain (" + options + ") " + sql, params, timeout)) {
            if (control != null) control.position(st);
            try (ResultSet result = st.executeQuery()) {
                result.next();
                return pg.json.readTree(result.getString(1));
            }
        } finally { if (control != null) control.position(null); }
    }
}
