package studio.performance.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.postgresql.core.SqlCommandType;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import studio.performance.dataset.Generator;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.postgres.Indexes;
import studio.performance.postgres.Postgres;
import studio.performance.postgres.Target;
import studio.performance.sandbox.Sandboxes;
import studio.performance.shared.Input;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class Benchmarks {
    private final Store store;
    private final Sandboxes sandboxes;
    private final Postgres pg;
    private final Generator generator;
    private final Indexes indexes;
    private final Jobs jobs;
    private final SqlRunner runner;
    private final Results results;
    private final Analysis analysis;

    private record Workload(String name, String sql, JsonNode parameters, boolean write) {}
    private record Variant(String name, boolean removeOptional, JsonNode disabled, JsonNode definitions) {}
    private record Sequence(String relation, long value, boolean called) {}
    private record Measurement(List<SqlRunner.Timing> samples, double throughput, JsonNode plan) {}

    public Benchmarks(Store store, Sandboxes sandboxes, Postgres pg, Generator generator, Indexes indexes,
                      Jobs jobs, SqlRunner runner, Results results, Analysis analysis) {
        this.store = store;
        this.sandboxes = sandboxes;
        this.pg = pg;
        this.generator = generator;
        this.indexes = indexes;
        this.jobs = jobs;
        this.runner = runner;
        this.results = results;
        this.analysis = analysis;
    }

    public Resource submit(UUID project, ObjectNode config) throws Exception {
        UUID sandboxId = Input.uuid(config, "sandboxId");
        store.get(project, "sandbox", sandboxId);
        List<Integer> sizes = new ArrayList<>();
        for (JsonNode entry : array(config, "sizes", 8)) {
            if (!entry.isIntegralNumber() || !entry.canConvertToInt() || entry.asInt() < 0 || entry.asInt() > 1_000_000) {
                throw Input.invalid("Dataset sizes must be between 0 and 1,000,000.");
            }
            sizes.add(entry.asInt());
        }
        if (sizes.isEmpty() || new HashSet<>(sizes).size() != sizes.size()) throw Input.invalid("Choose distinct dataset sizes.");
        if (!config.path("replaceData").asBoolean()) throw Input.invalid("Confirm dataset replacement in the selected sandbox.");
        JsonNode overrides = Input.object(config, "parameterOverrides");
        List<Workload> queries = new ArrayList<>();
        for (JsonNode id : array(config, "queryIds", 12)) {
            Resource query = store.get(project, "query", uuid(id));
            String sql = query.data.path("sql").asText();
            boolean write = Postgres.validate(sql, false) != SqlCommandType.SELECT;
            queries.add(new Workload(query.name, sql, overrides.has(query.id.toString())
                    ? SqlRunner.parameters(pg.json.createObjectNode().set("parameters", overrides.path(query.id.toString())))
                    : SqlRunner.parameters(query.data), write));
        }
        if (queries.isEmpty()) throw Input.invalid("Select at least one saved query.");
        List<Variant> variants = new ArrayList<>();
        for (JsonNode id : array(config, "indexConfigIds", 10)) {
            Resource variant = store.get(project, "index-config", uuid(id));
            variants.add(new Variant(variant.name, variant.data.path("removeOptional").asBoolean(),
                    variant.data.path("disabled"), variant.data.path("definitions")));
        }
        if (variants.isEmpty()) variants.add(new Variant("Existing indexes", false, pg.json.createArrayNode(), pg.json.createArrayNode()));
        boolean write = queries.stream().anyMatch(Workload::write);
        int concurrency = Input.number(config, "concurrency", 1, 1, 8);
        if (write && concurrency != 1) throw Input.invalid("Write benchmarks use one connection for sequence restoration and rollback.");
        int warmups = Input.number(config, "warmups", 3, 0, 100);
        int iterations = Input.number(config, "iterations", 10, 1, 200);
        int timeout = Input.number(config, "timeout", 30, 1, 60);
        int seed = Input.number(config, "seed", 42, Integer.MIN_VALUE, Integer.MAX_VALUE);
        int total = sizes.size() * variants.size() * queries.size();
        if (total > 240) throw Input.invalid("Limit the benchmark matrix to 240 scenarios.");
        String label = Input.text(config, "name", "Benchmark run", 120);
        Input.object(config, "strategies");
        Input.object(config, "tables");
        return jobs.submit(project, "benchmark", label, config, control -> sandboxes.exclusive(sandboxId, () -> {
            Resource sandbox = store.get(project, "sandbox", sandboxId);
            Target target = sandboxes.target(sandbox);
            restoreInterrupted(sandbox, target);
            ArrayNode baseline;
            try (Connection c = pg.open(target)) { baseline = pg.indexes(c); }
            ObjectNode checkpoint = pg.json.createObjectNode().put("sandboxId", sandbox.id.toString());
            checkpoint.set("baseline", baseline);
            Resource recovery = store.save(store.create(project, "report", "index-recovery-" + sandbox.id, checkpoint));
            int ordinal = 0;
            try {
                for (int size : sizes) {
                    if (ordinal > 0) apply(target, baseline, null, control);
                    control.progress(100 * ordinal / total, "Preparing " + size + " rows per table");
                    ObjectNode dataset = pg.json.createObjectNode().put("rows", size).put("seed", seed).put("replace", true);
                    dataset.set("strategies", config.path("strategies").isObject() ? config.path("strategies") : pg.json.createObjectNode());
                    ObjectNode tables = dataset.putObject("tables");
                    config.path("tables").fields().forEachRemaining(entry -> tables.put(entry.getKey(), size));
                    final int started = ordinal;
                    generator.generate(target, dataset, control, (percent, stage) -> control.progress(100 * started / total, stage));
                    List<Variant> ordered = new ArrayList<>(variants);
                    if (config.path("randomOrder").asBoolean()) Collections.shuffle(ordered, new Random(seed ^ size));
                    for (Variant variant : ordered) {
                        control.check();
                        double indexBuild = apply(target, baseline, variant, control);
                        JsonNode storage = storage(target);
                        ArrayNode variantIndexes;
                        try (Connection indexConnection = pg.open(target)) { variantIndexes = pg.indexes(indexConnection); }
                        List<Workload> work = new ArrayList<>(queries);
                        if (config.path("randomOrder").asBoolean()) Collections.shuffle(work, new Random(seed ^ variant.name().hashCode() ^ size));
                        for (Workload query : work) {
                            control.progress(100 * ordinal / total, variant.name() + " · " + query.name() + " · " + size + " rows");
                            Measurement measured = measure(target, query, warmups, iterations, timeout, concurrency, control);
                            ObjectNode summary = summarize(measured).put("size", size).put("query", query.name()).put("indexConfig", variant.name())
                                    .put("indexBuildMs", indexBuild).put("indexBytes", storage.path("indexBytes").asLong())
                                    .put("tableBytes", storage.path("tableBytes").asLong()).put("concurrency", concurrency);
                            summary.set("parameters", query.parameters());
                            ArrayNode findings = analysis.recommend(pg, measured.plan());
                            analysis.observeIndexes(variantIndexes, findings);
                            summary.set("recommendations", findings);
                            results.insert(control.id(), ordinal++, summary, measured.plan(), measured.samples());
                        }
                    }
                }
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    apply(target, baseline, null, null);
                    store.resources.delete(recovery);
                }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            return null;
        }));
    }

    private void restoreInterrupted(Resource sandbox, Target target) throws Exception {
        var recovery = store.resources.findByProjectIdAndKindAndName(sandbox.projectId, "report", "index-recovery-" + sandbox.id);
        if (recovery.isEmpty()) return;
        Resource checkpoint = recovery.get();
        if (checkpoint.data.path("baseline") instanceof ArrayNode baseline) {
            apply(target, baseline, null, null);
            store.resources.delete(checkpoint);
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 30_000)
    public void recoverSandboxIndexes() {
        for (Resource checkpoint : store.resources.findByKind("report")) {
            if (!checkpoint.name.startsWith("index-recovery-")) continue;
            try {
                UUID id = Input.uuid(checkpoint.data, "sandboxId");
                sandboxes.whenIdle(id, () -> {
                    Resource sandbox = store.get(checkpoint.projectId, "sandbox", id);
                    if ("running".equals(sandbox.data.path("status").asText())) restoreInterrupted(sandbox, sandboxes.target(sandbox));
                    return null;
                });
            } catch (Exception ignored) {
                // Keep the checkpoint so the next pass can retry once the sandbox is available.
            }
        }
    }

    private double apply(Target target, ArrayNode baseline, Variant variant, Jobs.Control control) throws Exception {
        Map<String, JsonNode> original = new HashMap<>();
        baseline.forEach(index -> original.put(Indexes.key(index), index));
        try (Connection c = pg.open(target); Statement st = c.createStatement()) {
            if (control != null) control.position(st);
            st.setQueryTimeout(120);
            ArrayNode current = pg.indexes(c);
            Set<String> present = new HashSet<>();
            Set<String> disabled = new HashSet<>();
            if (variant != null) variant.disabled().forEach(v -> disabled.add(v.asText()));
            if (original.values().stream().anyMatch(index -> index.path("protected").asBoolean() && disabled.contains(Indexes.key(index)))) {
                throw Input.invalid("This index configuration excludes a protected PostgreSQL index.");
            }
            for (JsonNode index : current) {
                String key = Indexes.key(index);
                if (!index.path("protected").asBoolean() && (!original.containsKey(key)
                        || variant != null && (variant.removeOptional() || disabled.contains(key)))) {
                    st.execute("drop index " + Input.relation(index.path("schema").asText(), index.path("name").asText()));
                } else present.add(key);
            }
            for (JsonNode index : baseline) {
                if (!present.contains(Indexes.key(index)) && (variant == null || !variant.removeOptional() && !disabled.contains(Indexes.key(index)))) {
                    st.execute(index.path("definition").asText());
                }
            }
            double build = 0;
            if (variant != null) {
                for (JsonNode spec : variant.definitions()) build += indexes.create(c, spec);
                st.execute("analyze");
            }
            c.commit();
            return build;
        } finally { if (control != null) control.position(null); }
    }

    private JsonNode storage(Target target) throws Exception {
        try (Connection c = pg.open(target)) {
            return pg.readArray(c, """
                select coalesce(sum(pg_table_size(c.oid)),0) as "tableBytes",
                       coalesce(sum(pg_indexes_size(c.oid)),0) as "indexBytes"
                from pg_class c join pg_namespace s on s.oid=c.relnamespace
                where c.relkind='r' and s.nspname !~ '^pg_' and s.nspname <> 'information_schema'
                """).get(0);
        }
    }

    private Measurement measure(Target target, Workload query, int warmups, int iterations, int timeout, int concurrency,
                                Jobs.Control control) throws Exception {
        List<SqlRunner.Timing> all = new ArrayList<>();
        double duration;
        try (Connection c = pg.open(target)) {
            c.setReadOnly(!query.write());
            List<Sequence> sequences = query.write() ? sequences(c) : List.of();
            try {
                if (concurrency == 1) {
                    for (int i = 0; i < warmups; i++) {
                        control.check();
                        runner.measure(c, query.sql(), query.parameters(), timeout, control, false);
                        reset(c, sequences);
                    }
                    long start = System.nanoTime();
                    for (int i = 0; i < iterations; i++) {
                        control.check();
                        all.add(runner.measure(c, query.sql(), query.parameters(), timeout, control, false));
                        reset(c, sequences);
                    }
                    duration = (System.nanoTime() - start) / 1_000_000_000d;
                } else {
                    try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(iterations, concurrency))) {
                        CountDownLatch ready = new CountDownLatch(Math.min(iterations, concurrency));
                        CountDownLatch begin = new CountDownLatch(1);
                        List<Future<List<SqlRunner.Timing>>> work = new ArrayList<>();
                        int lanes = (int) ready.getCount();
                        for (int lane = 0; lane < lanes; lane++) {
                            int replicas = iterations / lanes + (lane < iterations % lanes ? 1 : 0);
                            work.add(pool.submit(() -> {
                                try (Connection worker = pg.open(target)) {
                                    worker.setReadOnly(true);
                                    for (int i = 0; i < warmups; i++) runner.measure(worker, query.sql(), query.parameters(), timeout, control, false);
                                    ready.countDown();
                                    begin.await();
                                    List<SqlRunner.Timing> samples = new ArrayList<>();
                                    for (int i = 0; i < replicas; i++) {
                                        control.check();
                                        samples.add(runner.measure(worker, query.sql(), query.parameters(), timeout, control, false));
                                    }
                                    return samples;
                                } catch (Exception ex) {
                                    ready.countDown();
                                    throw ex;
                                }
                            }));
                        }
                        try {
                            ready.await();
                            long start = System.nanoTime();
                            begin.countDown();
                            for (var future : work) all.addAll(future.get());
                            duration = (System.nanoTime() - start) / 1_000_000_000d;
                        } catch (Exception ex) {
                            begin.countDown();
                            work.forEach(future -> future.cancel(true));
                            throw ex;
                        }
                    }
                }
                control.check();
                JsonNode plan = runner.explain(c, query.sql(), query.parameters(), timeout, "buffers", control);
                c.rollback();
                return new Measurement(all, iterations / Math.max(duration, .000001), plan);
            } finally {
                if (!sequences.isEmpty()) {
                    c.rollback();
                    reset(c, sequences);
                }
            }
        }
    }

    private List<Sequence> sequences(Connection c) throws Exception {
        List<Sequence> sequences = new ArrayList<>();
        for (JsonNode item : pg.readArray(c, """
                select n.nspname as schema, s.relname as name from pg_class s join pg_namespace n on n.oid=s.relnamespace
                where s.relkind='S' and pg_has_role(s.relowner,'USAGE')
                """)) {
            String relation = Postgres.relation(item);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select last_value, is_called from " + relation)) {
                rs.next();
                sequences.add(new Sequence(relation, rs.getLong(1), rs.getBoolean(2)));
            }
        }
        return sequences;
    }

    private void reset(Connection c, List<Sequence> sequences) throws SQLException {
        if (sequences.isEmpty()) return;
        try (PreparedStatement st = c.prepareStatement("select setval(?::regclass, ?, ?)")) {
            for (Sequence sequence : sequences) {
                st.setString(1, sequence.relation());
                st.setLong(2, sequence.value());
                st.setBoolean(3, sequence.called());
                st.execute();
            }
        }
        c.commit();
    }

    private ObjectNode summarize(Measurement measured) {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        measured.samples().forEach(sample -> stats.addValue(sample.ms()));
        JsonNode envelope = measured.plan().path(0);
        JsonNode root = envelope.path("Plan");
        ObjectNode result = pg.json.createObjectNode().put("min", stats.getMin()).put("max", stats.getMax()).put("mean", stats.getMean())
                .put("median", stats.getPercentile(50)).put("stddev", stats.getStandardDeviation())
                .put("p95", stats.getPercentile(95)).put("p99", stats.getPercentile(99))
                .put("throughput", measured.throughput()).put("count", measured.samples().size()).put("planNode", root.path("Node Type").asText());
        result.set("planningMs", envelope.path("Planning Time"));
        result.set("executionMs", envelope.path("Execution Time"));
        if (root instanceof ObjectNode metrics) {
            ObjectNode rootMetrics = metrics.deepCopy();
            rootMetrics.remove("Plans");
            result.set("metrics", rootMetrics);
        }
        return result;
    }

    public static JsonNode array(JsonNode config, String name, int limit) {
        JsonNode item = config.path(name);
        if (item.isMissingNode()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (!item.isArray() || item.size() > limit) throw Input.invalid("Invalid " + name + " selection.");
        return item;
    }

    private static UUID uuid(JsonNode id) {
        try { return UUID.fromString(id.asText()); }
        catch (IllegalArgumentException ex) { throw Input.invalid("Invalid resource selection."); }
    }
}
