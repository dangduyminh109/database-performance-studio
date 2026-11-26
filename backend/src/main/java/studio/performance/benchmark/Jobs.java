package studio.performance.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import studio.performance.metadata.Resource;
import studio.performance.metadata.Store;
import studio.performance.shared.Input;

import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class Jobs {
    private final Store store;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(32));
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final ConcurrentMap<UUID, Control> active = new ConcurrentHashMap<>();

    public Jobs(Store store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public interface Task { void run(Control control) throws Exception; }

    public final class Control {
        private Resource job;
        private volatile boolean cancelled;
        private final ConcurrentMap<Thread, Statement> statements = new ConcurrentHashMap<>();
        private volatile Thread thread;
        private volatile boolean timedOut;

        private Control(Resource job) { this.job = job; }
        public UUID id() { return job.id; }

        public void progress(int percent, String message) {
            check();
            update("running", percent, message);
        }

        public void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }

        public void position(Statement current) {
            if (current == null) statements.remove(Thread.currentThread());
            else statements.put(Thread.currentThread(), current);
            if (current != null) check();
        }

        private void cancel() {
            cancelled = true;
            for (Statement current : statements.values()) {
                try { current.cancel(); } catch (Exception ignored) {}
            }
            Thread runner = thread;
            if (runner != null) runner.interrupt();
        }

        private void update(String state, int percent, String message) {
            ObjectNode data = job.data.deepCopy();
            data.put("status", state).put("progress", percent).put("message", message);
            if ("running".equals(state) && !data.has("startedAt")) data.put("startedAt", Instant.now().toString());
            if (terminal(state)) data.put("finishedAt", Instant.now().toString());
            job.data = data;
            job = store.save(job);
        }
    }

    @PostConstruct
    void recover() {
        for (Resource job : store.resources.findByKind("job")) {
            if (!terminal(job.data.path("status").asText())) {
                new Control(job).update("failed", job.data.path("progress").asInt(), "Application restarted during the job.");
            }
        }
    }

    public Resource submit(UUID project, String type, String label, ObjectNode configuration, Task task) {
        store.project(project);
        ObjectNode data = json.createObjectNode().put("type", type).put("label", label).put("status", "queued")
                .put("progress", 0).put("message", "Queued").set("configuration", configuration);
        Resource job = store.save(store.create(project, "job", type + "-" + UUID.randomUUID(), data));
        Control control = new Control(job);
        active.put(job.id, control);
        try {
            workers.execute(() -> {
                control.thread = Thread.currentThread();
                var deadline = timers.schedule(() -> {
                    control.timedOut = true;
                    control.cancel();
                }, 25, TimeUnit.MINUTES);
                try {
                    control.progress(0, "Starting");
                    task.run(control);
                    control.check();
                    control.update("completed", 100, "Completed");
                } catch (Exception ex) {
                    Thread.interrupted();
                    String state = control.cancelled && !control.timedOut ? "cancelled" : "failed";
                    String message = control.timedOut ? "Job time limit exceeded." : control.cancelled ? "Cancelled."
                            : ex instanceof IllegalArgumentException ? ex.getMessage()
                            : ex instanceof java.sql.SQLException sql ? "PostgreSQL rejected the operation (SQLSTATE " + sql.getSQLState() + ")."
                            : "The operation failed. Check database availability, permissions and sandbox status.";
                    control.update(state, control.job.data.path("progress").asInt(), message);
                } finally {
                    deadline.cancel(false);
                    control.thread = null;
                    active.remove(job.id);
                }
            });
        } catch (RejectedExecutionException ex) {
            active.remove(job.id);
            control.update("failed", 0, "Job queue is full.");
        }
        return job;
    }

    public void cancel(UUID project, UUID id) {
        store.get(project, "job", id);
        Control control = active.get(id);
        if (control == null) throw Input.invalid("This job has already finished.");
        control.cancel();
    }

    public SseEmitter events(UUID project, UUID id) {
        store.get(project, "job", id);
        SseEmitter emitter = new SseEmitter(1_800_000L);
        var subscription = timers.scheduleWithFixedDelay(() -> {
            try {
                Resource job = store.get(project, "job", id);
                emitter.send(SseEmitter.event().name("progress").data(job));
                if (terminal(job.data.path("status").asText())) emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }, 0, 750, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> subscription.cancel(false));
        emitter.onTimeout(() -> { subscription.cancel(false); emitter.complete(); });
        emitter.onError(ex -> subscription.cancel(false));
        return emitter;
    }

    public static boolean terminal(String state) {
        return Set.of("completed", "failed", "cancelled").contains(state);
    }

    @PreDestroy
    void stop() {
        active.values().forEach(Control::cancel);
        workers.shutdownNow();
        timers.shutdownNow();
    }
}
