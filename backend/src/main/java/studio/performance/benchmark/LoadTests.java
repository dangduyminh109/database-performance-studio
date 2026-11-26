package studio.performance.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.postgresql.core.SqlCommandType;
import org.springframework.stereotype.Service;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.postgres.Postgres;
import studio.performance.postgres.Target;
import studio.performance.sandbox.Sandboxes;
import studio.performance.shared.Input;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

@Service
public class LoadTests {
    private final Store store;
    private final Jobs jobs;
    private final Sandboxes sandboxes;
    private final SqlRunner runner;
    private final Postgres pg;

    private record Work(String sql, JsonNode parameters, boolean read) {}

    private static final class Sample {
        private final double[] reservoir = new double[8192];
        private final Random random = new Random(42);
        private long count;
        synchronized void add(double value) {
            long index = random.nextLong(++count);
            if (count <= reservoir.length) reservoir[(int) count - 1] = value;
            else if (index < reservoir.length) reservoir[(int) index] = value;
        }
        synchronized DescriptiveStatistics statistics() {
            return new DescriptiveStatistics(Arrays.copyOf(reservoir, (int) Math.min(count, reservoir.length)));
        }
    }

    public LoadTests(Store store, Jobs jobs, Sandboxes sandboxes, SqlRunner runner, Postgres pg) {
        this.store = store;
        this.jobs = jobs;
        this.sandboxes = sandboxes;
        this.runner = runner;
        this.pg = pg;
    }

    public Resource start(UUID project, ObjectNode config) throws Exception {
        UUID sandbox = Input.uuid(config, "sandboxId");
        store.get(project, "sandbox", sandbox);
        String name = Input.text(config, "name", "Sandbox load test", 120);
        int clients = Input.number(config, "concurrency", 4, 1, 8);
        int duration = Input.number(config, "duration", 15, 2, 120);
        int ramp = Input.number(config, "ramp", 3, 0, 30);
        int timeout = Input.number(config, "timeout", 5, 1, 30);
        boolean commit = config.path("commit").asBoolean(false);
        List<Work> queries = new ArrayList<>();
        for (JsonNode entry : Benchmarks.array(config, "queryIds", 12)) {
            Resource query = store.get(project, "query", UUID.fromString(entry.asText()));
            String sql = query.data.path("sql").asText();
            queries.add(new Work(sql, SqlRunner.parameters(query.data), Postgres.validate(sql, false) == SqlCommandType.SELECT));
        }
        if (queries.isEmpty()) throw Input.invalid("Select at least one saved query.");
        return jobs.submit(project, "load", name, config, control -> sandboxes.exclusive(sandbox, () -> {
            ObjectNode report = measure(sandboxes.target(store.get(project, "sandbox", sandbox)), clients, duration, ramp, timeout, commit, queries, control);
            store.save(store.create(project, "report", control.id().toString(), report));
            return null;
        }));
    }

    public JsonNode report(UUID project, UUID job) {
        store.get(project, "job", job);
        return store.resources.findByProjectIdAndKindAndName(project, "report", job.toString())
                .orElseThrow(() -> Input.invalid("Load test results are not available yet.")).data;
    }

    private ObjectNode measure(Target target, int clients, int duration, int ramp, int timeout, boolean commit,
                               List<Work> queries, Jobs.Control control) throws Exception {
        LongAdder success = new LongAdder(), errors = new LongAdder(), deadlocks = new LongAdder(), lockTimeouts = new LongAdder();
        Sample times = new Sample();
        boolean readOnly = queries.stream().allMatch(Work::read);
        int peakWaiting = 0;
        long begin = System.nanoTime();
        long warm = begin + TimeUnit.SECONDS.toNanos(ramp);
        long end = warm + TimeUnit.SECONDS.toNanos(duration);
        try (ExecutorService pool = Executors.newFixedThreadPool(clients); Connection observer = pg.open(target)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int client = 0; client < clients; client++) {
                int worker = client;
                tasks.add(pool.submit(() -> {
                    Thread.sleep(1000L * ramp * worker / clients);
                    try (Connection c = pg.open(target)) {
                        c.setReadOnly(readOnly);
                        int next = worker;
                        while (System.nanoTime() < end) {
                            control.check();
                            Work item = queries.get(Math.floorMod(next++, queries.size()));
                            long start = System.nanoTime();
                            boolean record = start >= warm;
                            try {
                                var timing = runner.measure(c, item.sql(), item.parameters(), timeout, control, !item.read() && commit);
                                if (record && System.nanoTime() <= end) { success.increment(); times.add(timing.ms()); }
                            } catch (SQLException ex) {
                                c.rollback();
                                if (record && System.nanoTime() <= end) {
                                    errors.increment();
                                    if ("40P01".equals(ex.getSQLState())) deadlocks.increment();
                                    if ("55P03".equals(ex.getSQLState())) lockTimeouts.increment();
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            try (var st = observer.createStatement()) {
                st.setQueryTimeout(3);
                while (System.nanoTime() < end) {
                    control.progress((int) Math.min(99, 100 * (System.nanoTime() - begin) / (end - begin)),
                            System.nanoTime() < warm ? "Ramping clients" : "Measuring concurrent workload");
                    try (var row = st.executeQuery("""
                            select count(*) from pg_stat_activity where datname=current_database()
                                and usename=current_user and wait_event_type='Lock'
                            """)) {
                        row.next();
                        peakWaiting = Math.max(peakWaiting, row.getInt(1));
                    }
                    observer.commit();
                    Thread.sleep(500);
                }
                for (var task : tasks) task.get();
            } catch (Exception ex) {
                tasks.forEach(task -> task.cancel(true));
                throw ex;
            }
        }
        DescriptiveStatistics stats = times.statistics();
        long total = success.sum() + errors.sum();
        ObjectNode result = pg.json.createObjectNode().put("durationSeconds", duration).put("rampSeconds", ramp)
                .put("concurrency", clients).put("successful", success.sum()).put("errors", errors.sum())
                .put("errorPercent", total == 0 ? 0 : 100d * errors.sum() / total).put("deadlocks", deadlocks.sum())
                .put("lockTimeouts", lockTimeouts.sum()).put("peakWaitingForLocks", peakWaiting)
                .put("throughput", (double) success.sum() / duration).put("sampleSize", stats.getN())
                .put("committedWrites", commit && !readOnly);
        if (stats.getN() > 0) result.put("p50", stats.getPercentile(50)).put("p95", stats.getPercentile(95)).put("p99", stats.getPercentile(99));
        return result;
    }
}
