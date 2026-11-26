package studio.performance.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.stereotype.Service;
import studio.performance.benchmark.Jobs;
import studio.performance.postgres.Postgres;
import studio.performance.postgres.Target;
import studio.performance.shared.Input;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.BiConsumer;

@Service
public class Generator {
    private final Postgres pg;
    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    private record Foreign(List<String> columns, String table, List<String> referent) {}
    private record Table(JsonNode meta, int count, List<Foreign> foreign, Set<String> unique) {
        String key() { return keyOf(meta.path("schema").asText(), meta.path("name").asText()); }
        String relation() { return Postgres.relation(meta); }
    }

    public Generator(Postgres pg) { this.pg = pg; }

    public void generate(Target target, JsonNode config, Jobs.Control control, BiConsumer<Integer, String> progress) throws Exception {
        JsonNode schema = pg.schema(target, false);
        JsonNode requested = Input.object(config, "tables");
        JsonNode strategies = Input.object(config, "strategies");
        int fallback = Input.number(config, "rows", 1000, 0, 1_000_000);
        long seed = Input.number(config, "seed", 42, Integer.MIN_VALUE, Integer.MAX_VALUE);
        Map<String, Table> tables = new LinkedHashMap<>();
        Map<String, JsonNode> known = new LinkedHashMap<>();
        for (JsonNode meta : schema.path("tables")) {
            if (!"r".equals(meta.path("kind").asText())) continue;
            String key = keyOf(meta.path("schema").asText(), meta.path("name").asText());
            if (known.put(key, meta) != null) throw Input.invalid("Ambiguous table name in the dataset.");
            if (requested.size() > 0 && !requested.has(key)) continue;
            int count = Input.number(requested, key, fallback, 0, 1_000_000);
            List<Foreign> foreign = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode constraint : meta.path("constraints")) {
                List<String> columns = names(constraint.path("columns"));
                String type = constraint.path("type").asText();
                if (("p".equals(type) || "u".equals(type)) && columns.size() == 1) unique.add(columns.getFirst());
                if ("f".equals(type)) foreign.add(new Foreign(columns, keyOf(constraint.path("foreignSchema").asText(),
                        constraint.path("foreignTable").asText()), names(constraint.path("foreignColumns"))));
                if (("c".equals(type) || "x".equals(type)) && count > 0) {
                    for (String column : columns) {
                        if (!strategies.path(key).has(column)) {
                            throw Input.invalid("Configure a strategy for " + key + "." + column + " to satisfy its check or exclusion constraint.");
                        }
                    }
                }
            }
            tables.put(key, new Table(meta, count, foreign, unique));
        }
        if (tables.isEmpty() || (requested.size() > 0 && tables.size() != requested.size())) {
            throw Input.invalid("Select existing ordinary sandbox tables.");
        }
        List<Table> ordered = order(tables, strategies);
        try (Connection c = pg.open(target); Statement st = c.createStatement()) {
            control.position(st);
            try {
                st.setQueryTimeout(120);
                st.execute("set constraints all deferred");
                if (config.path("replace").asBoolean(false)) {
                    st.execute("truncate " + String.join(", ", ordered.stream().map(Table::relation).toList()) + " restart identity");
                }
                int completed = 0;
                for (Table table : ordered) {
                    control.check();
                    progress.accept(100 * completed / ordered.size(), "Generating " + table.key());
                    if (table.count() > 0) copy(c, table, known, schema.path("indexes"), strategies.path(table.key()), seed, control, completed, ordered.size(), progress);
                    completed++;
                }
                c.commit();
                progress.accept(100, "Dataset generated");
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                control.position(null);
            }
        }
    }

    private List<Table> order(Map<String, Table> tables, JsonNode strategies) {
        List<Table> ordered = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>(tables.keySet());
        while (!pending.isEmpty()) {
            List<String> ready = pending.stream().filter(key -> tables.get(key).count() == 0 || tables.get(key).foreign().stream()
                    .noneMatch(link -> pending.contains(link.table()) && !explicit(link, strategies.path(key)))).toList();
            if (ready.isEmpty()) {
                throw Input.invalid("Circular foreign keys in " + String.join(", ", pending)
                        + ". Configure a strategy for every column of an edge in the cycle, such as constant NULL on a nullable edge.");
            }
            ready.forEach(key -> ordered.add(tables.get(key)));
            pending.removeAll(ready);
        }
        return ordered;
    }

    private static boolean explicit(Foreign foreign, JsonNode strategies) {
        return foreign.columns().stream().allMatch(column -> strategies.has(column)
                && !"foreignKey".equals(strategies.path(column).path("strategy").asText()));
    }

    private void copy(Connection c, Table table, Map<String, JsonNode> known, JsonNode indexes, JsonNode strategies, long seed,
                      Jobs.Control control, int completed, int total, BiConsumer<Integer, String> progress) throws Exception {
        List<JsonNode> columns = new ArrayList<>();
        for (JsonNode column : table.meta().path("columns")) {
            String name = column.path("name").asText();
            if (!column.path("generated").asText().isEmpty() || !column.path("identity").asText().isEmpty()) continue;
            if (!column.path("defaultValue").isNull() && !strategies.has(name) &&
                    table.foreign().stream().noneMatch(f -> f.columns().contains(name))) continue;
            columns.add(column);
        }
        Set<String> unique = new HashSet<>(table.unique());
        for (JsonNode index : indexes) {
            if (!index.path("unique").asBoolean() || index.path("protected").asBoolean()
                    || !table.meta().path("schema").asText().equals(index.path("schema").asText())
                    || !table.meta().path("name").asText().equals(index.path("table").asText())) continue;
            List<String> indexColumns = names(index.path("columns"));
            if (!index.path("predicate").isNull() || !index.path("expressions").isNull()) {
                if (strategies.isEmpty()) throw Input.invalid("Define column strategies for the partial or expression unique index on " + table.key() + ".");
            } else if (index.path("keyColumns").asInt() == 1) {
                unique.add(indexColumns.getFirst().replaceFirst("^\"(.*)\"$", "$1").replace("\"\"", "\""));
            }
        }
        Map<Foreign, List<List<String>>> references = new LinkedHashMap<>();
        for (Foreign foreign : table.foreign()) {
            if (explicit(foreign, strategies)) continue;
            if (!known.containsKey(foreign.table())) throw Input.invalid("Referenced table is unavailable: " + foreign.table());
            if (foreign.columns().stream().anyMatch(strategies::has)
                    && foreign.columns().stream().anyMatch(name -> !"foreignKey".equals(strategies.path(name).path("strategy").asText("foreignKey")))) {
                throw Input.invalid("Configure all columns of a composite foreign key together.");
            }
            String selected = String.join(", ", foreign.referent().stream().map(Input::identifier).toList());
            List<List<String>> pool = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(60);
                control.position(st);
                try (ResultSet rows = st.executeQuery("select " + selected + " from " + Postgres.relation(known.get(foreign.table()))
                        + " order by " + selected + " limit 20000")) {
                    while (rows.next()) {
                        List<String> tuple = new ArrayList<>();
                        for (int index = 1; index <= foreign.referent().size(); index++) tuple.add(rows.getString(index));
                        if (tuple.stream().noneMatch(Objects::isNull)) pool.add(tuple);
                    }
                } finally { control.position(null); }
            }
            if (pool.isEmpty()) throw Input.invalid("Generate or populate " + foreign.table() + " before " + table.key() + ".");
            references.put(foreign, pool);
        }
        Random random = new Random(seed ^ table.key().hashCode());
        Faker faker = new Faker(new Random(seed ^ table.key().hashCode() ^ 19));
        if (columns.isEmpty()) {
            try (Statement st = c.createStatement()) {
                control.position(st);
                st.setQueryTimeout(60);
                for (int row = 1; row <= table.count(); row++) {
                    control.check();
                    st.addBatch("insert into " + table.relation() + " default values");
                    if (row % 1000 == 0 || row == table.count()) { st.executeBatch(); st.clearBatch(); }
                }
            } finally { control.position(null); }
            return;
        }
        Map<String, String> evaluated = new HashMap<>();
        for (JsonNode column : columns) {
            String name = column.path("name").asText();
            JsonNode strategy = strategies.path(name);
            if (!"expression".equals(strategy.path("strategy").asText())) continue;
            String sql = "select (" + Input.string(strategy, "expression", 1000) + ")::text";
            Postgres.validate(sql, true);
            try (PreparedStatement statement = pg.prepare(c, sql, pg.json.createArrayNode(), 5)) {
                control.position(statement);
                try (ResultSet rs = statement.executeQuery()) { rs.next(); evaluated.put(name, rs.getString(1)); }
                finally { control.position(null); }
            }
        }
        Set<String> sequential = new HashSet<>(unique);
        for (JsonNode constraint : table.meta().path("constraints")) {
            if (!Set.of("p", "u").contains(constraint.path("type").asText())) continue;
            List<String> key = names(constraint.path("columns"));
            if (key.size() > 1 && table.foreign().stream().noneMatch(foreign -> foreign.columns().contains(key.getFirst()))) {
                sequential.add(key.getFirst());
            }
        }
        Map<String, Long> offsets = new HashMap<>();
        if (!columns.isEmpty()) {
            for (JsonNode column : columns) {
                String name = column.path("name").asText();
                if (!sequential.contains(name) || strategies.has(name)) continue;
                try (Statement st = c.createStatement();
                     ResultSet result = st.executeQuery("select count(*) from " + table.relation())) {
                    result.next();
                    offsets.put(name, result.getLong(1));
                }
            }
        }
        String columnList = String.join(", ", columns.stream().map(col -> Input.identifier(col.path("name").asText())).toList());
        CopyIn copy = c.unwrap(PGConnection.class).getCopyAPI().copyIn("copy " + table.relation() + " (" + columnList + ") from stdin with (format csv, null '\\N')");
        StringBuilder buffer = new StringBuilder(65_536);
        try {
            for (int row = 1; row <= table.count(); row++) {
                control.check();
                Map<String, String> fk = new HashMap<>();
                for (var entry : references.entrySet()) {
                    Foreign foreign = entry.getKey();
                    var pool = entry.getValue();
                    boolean distinct = foreign.columns().stream().anyMatch(unique::contains);
                    if (distinct && table.count() > pool.size()) throw Input.invalid("A unique foreign key needs at least as many parent rows.");
                    var tuple = pool.get(distinct ? row - 1 : (row - 1) % pool.size());
                    for (int index = 0; index < foreign.columns().size(); index++) fk.put(foreign.columns().get(index), tuple.get(index));
                }
                for (int index = 0; index < columns.size(); index++) {
                    if (index > 0) buffer.append(',');
                    JsonNode column = columns.get(index);
                    String name = column.path("name").asText();
                    String value = fk.containsKey(name) ? fk.get(name) : evaluated.containsKey(name) ? evaluated.get(name)
                            : value(column, strategies.path(name), row + offsets.getOrDefault(name, 0L), sequential.contains(name), random, faker);
                    if (value == null) buffer.append("\\N");
                    else buffer.append('"').append(value.replace("\"", "\"\"")).append('"');
                }
                buffer.append('\n');
                if (buffer.length() >= 65_536 || row == table.count()) {
                    byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
                    copy.writeToCopy(bytes, 0, bytes.length);
                    buffer.setLength(0);
                }
                if (row % 25_000 == 0) progress.accept((int) (100.0 * (completed + (double) row / table.count()) / total),
                        "Generating " + table.key() + " · " + row + " rows");
            }
            copy.endCopy();
        } finally {
            if (copy.isActive()) copy.cancelCopy();
        }
    }

    private String value(JsonNode col, JsonNode rule, long index, boolean unique, Random random, Faker faker) {
        String name = col.path("name").asText();
        String type = col.path("baseType").asText();
        String strategy = rule.path("strategy").asText(unique ? "sequential" : "random");
        if (col.path("nullable").asBoolean() && random.nextDouble() * 100 < Input.number(rule, "nullPercent", 0, 0, 100)) return null;
        int cardinality = Input.number(rule, "cardinality", 1000, 1, 1_000_000);
        double draw = random.nextDouble();
        double fraction = switch (rule.path("distribution").asText("uniform")) {
            case "normal" -> Math.clamp(.5 + random.nextGaussian() / 6, 0, .999999);
            case "skewed" -> draw * draw * draw;
            case "zipfian" -> Math.expm1(draw * Math.log1p(cardinality)) / cardinality;
            case "uniform" -> draw;
            default -> throw Input.invalid("Unsupported distribution for " + name + ".");
        };
        long ordinal = strategy.equals("sequential") ? index : 1 + (long) (fraction * cardinality);
        if (!unique && index > 1 && random.nextInt(100) < Input.number(rule, "duplicatePercent", 0, 0, 100)) ordinal = 1;
        JsonNode options = rule.path("values");
        return switch (strategy) {
            case "constant" -> rule.path("value").isNull() ? null : rule.path("value").isContainerNode()
                    ? rule.path("value").toString() : rule.path("value").asText();
            case "list" -> {
                if (!options.isArray() || options.isEmpty()) throw Input.invalid("Define a value list for " + name + ".");
                JsonNode selected = options.get((int) ((ordinal - 1) % options.size()));
                yield selected.isNull() ? null : selected.isContainerNode() ? selected.toString() : selected.asText();
            }
            case "pattern" -> fit(Input.string(rule, "pattern", 500).replace("{n}", Long.toString(ordinal)), col, ordinal, unique);
            case "faker" -> fit(fake(name, faker), col, ordinal, false);
            case "numericRange" -> {
                var min = rule.path("min").decimalValue();
                var max = rule.path("max").decimalValue();
                if (!rule.path("min").isNumber() || !rule.path("max").isNumber() || min.compareTo(max) > 0) throw Input.invalid("Invalid numeric range for " + name + ".");
                var result = min.add(max.subtract(min).multiply(java.math.BigDecimal.valueOf(fraction)));
                yield Set.of("int2", "int4", "int8").contains(type) ? result.toBigInteger().toString() : result.toPlainString();
            }
            case "dateRange" -> {
                var start = LocalDate.parse(Input.string(rule, "start", 10));
                var end = LocalDate.parse(Input.string(rule, "end", 10));
                long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
                if (days < 0) throw Input.invalid("Invalid date range for " + name + ".");
                yield start.plusDays((long) (fraction * (days + 1))).toString();
            }
            case "random", "sequential" -> automatic(col, type, name, ordinal, random, faker, unique, rule);
            default -> throw Input.invalid("Define a supported strategy for " + name + ".");
        };
    }

    private String automatic(JsonNode col, String type, String name, long n, Random random, Faker faker, boolean unique, JsonNode rule) {
        JsonNode labels = col.path("enum");
        if (labels.isArray() && !labels.isEmpty()) return labels.get((int) ((n - 1) % labels.size())).asText();
        if (name.equals("deleted_at") && col.path("nullable").asBoolean()
                && random.nextInt(100) >= Input.number(rule, "softDeletePercent", 0, 0, 100)) return null;
        LocalDateTime instant = LocalDateTime.ofInstant(EPOCH.plusSeconds(n * 197), ZoneOffset.UTC);
        return switch (type) {
            case "int2", "int4", "int8" -> Long.toString(n);
            case "numeric", "float4", "float8", "money" -> n + ".25";
            case "uuid" -> unique ? new UUID(0, n).toString() : new UUID(random.nextLong(), random.nextLong()).toString();
            case "json", "jsonb" -> "{\"value\":" + n + "}";
            case "bool" -> Boolean.toString(name.equals("deleted") || name.equals("is_deleted")
                    ? random.nextInt(100) < Input.number(rule, "softDeletePercent", 0, 0, 100) : n % 2 == 0);
            case "date" -> LocalDate.of(2024, 1, 1).plusDays(n).toString();
            case "timestamp" -> instant.toString();
            case "timestamptz" -> instant + "Z";
            case "time" -> instant.toLocalTime().toString();
            case "text", "varchar", "bpchar", "citext", "name" -> {
                String keyword = rule.path("keyword").asText("");
                String value = !keyword.isBlank() && random.nextInt(100) < Input.number(rule, "keywordPercent", 0, 0, 100)
                        ? keyword : name.equals("status") ? List.of("pending", "shipped", "complete", "cancelled").get((int) (n % 4))
                        : name.equals("region") ? List.of("north", "south", "east", "west").get((int) (n % 4))
                        : name.contains("email") ? "user" + n + "@example.test" : unique ? name + "-" + n : fake(name, faker);
                yield fit(value, col, n, unique);
            }
            default -> throw Input.invalid("Set an explicit strategy for " + name + " (" + type + ").");
        };
    }

    private static String fake(String name, Faker faker) {
        if (name.contains("email")) return faker.internet().emailAddress();
        if (name.contains("name")) return faker.name().fullName();
        if (name.contains("city")) return faker.address().city();
        if (name.contains("address")) return faker.address().streetAddress();
        return faker.lorem().words(3).toString().replaceAll("[\\[\\]]", "");
    }

    private String fit(String value, JsonNode col, long n, boolean unique) {
        var matcher = java.util.regex.Pattern.compile(".*\\((\\d+)\\)$").matcher(col.path("type").asText());
        if (!matcher.matches()) return value;
        int max = Integer.parseInt(matcher.group(1));
        if (value.length() <= max) return value;
        String digits = Long.toString(n, 36);
        if (unique && digits.length() > max) throw Input.invalid("The unique column is too short for the requested dataset size.");
        return unique ? digits : value.substring(0, max);
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.asText()));
        return names;
    }

    private static String keyOf(String schema, String table) { return schema + "." + table; }
}
