package studio.performance.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import studio.performance.benchmark.*;
import studio.performance.dataset.Generator;
import studio.performance.metadata.*;
import studio.performance.postgres.*;
import studio.performance.sandbox.Sandboxes;
import studio.performance.shared.Input;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class Api {
    private static final Set<String> COLLECTIONS = Set.of("connection", "sandbox", "schema", "dataset", "query", "index-config", "job");
    private final Store store;
    private final Postgres pg;
    private final Connections connections;
    private final Sandboxes sandboxes;
    private final Indexes indexes;
    private final Generator generator;
    private final Jobs jobs;
    private final SqlRunner sql;
    private final Benchmarks benchmarks;
    private final Results results;
    private final LoadTests loads;
    private record Preview(JsonNode body, Instant expires) {}
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();

    public Api(Store store, Postgres pg, Connections connections, Sandboxes sandboxes, Indexes indexes, Generator generator,
               Jobs jobs, SqlRunner sql, Benchmarks benchmarks, Results results, LoadTests loads) {
        this.store = store;
        this.pg = pg;
        this.connections = connections;
        this.sandboxes = sandboxes;
        this.indexes = indexes;
        this.generator = generator;
        this.jobs = jobs;
        this.sql = sql;
        this.benchmarks = benchmarks;
        this.results = results;
        this.loads = loads;
    }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "ready"); }
    @GetMapping("/projects") public List<Project> projects() {
        return store.projects.findAll(org.springframework.data.domain.Sort.by("createdAt").descending());
    }

    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    public Project createProject(@RequestBody ObjectNode body) { return saveProject(new Project(), body); }

    @PutMapping("/projects/{project}")
    public Project updateProject(@PathVariable UUID project, @RequestBody ObjectNode body) { return saveProject(store.project(project), body); }

    private Project saveProject(Project project, JsonNode body) {
        project.name = Input.string(body, "name", 120);
        project.description = Input.text(body, "description", "", 2000);
        project.configuration = Input.object(body, "configuration");
        project.updatedAt = Instant.now();
        return store.projects.saveAndFlush(project);
    }

    @DeleteMapping("/projects/{project}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable UUID project) throws Exception {
        Project record = store.project(project);
        requireIdle(project);
        for (Resource sandbox : store.list(project, "sandbox")) sandboxes.lifecycle(sandbox, "delete");
        store.projects.delete(record);
    }

    private void requireIdle(UUID project) {
        if (store.list(project, "job").stream().anyMatch(job -> !Jobs.terminal(job.data.path("status").asText()))) {
            throw Input.invalid("Finish or cancel active jobs first.");
        }
    }

    @GetMapping("/projects/{project}/resources/{kind}")
    public List<Resource> list(@PathVariable UUID project, @PathVariable String kind) {
        if (!COLLECTIONS.contains(kind)) throw Input.invalid("Unknown collection.");
        return store.list(project, kind);
    }

    @PostMapping("/projects/{project}/resources/{kind}") @ResponseStatus(HttpStatus.CREATED)
    public Resource create(@PathVariable UUID project, @PathVariable String kind, @RequestBody ObjectNode body) throws Exception {
        return saveResource(project, kind, null, body);
    }

    @PutMapping("/projects/{project}/resources/{kind}/{id}")
    public Resource update(@PathVariable UUID project, @PathVariable String kind, @PathVariable UUID id,
                           @RequestBody ObjectNode body) throws Exception {
        return saveResource(project, kind, id, body);
    }

    @DeleteMapping("/projects/{project}/resources/{kind}/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID project, @PathVariable String kind, @PathVariable UUID id) {
        if (!Set.of("connection", "dataset", "query", "index-config", "schema").contains(kind)) throw Input.invalid("This resource cannot be removed here.");
        if ("connection".equals(kind) && store.list(project, "sandbox").stream().anyMatch(sandbox -> id.toString().equals(sandbox.data.path("connectionId").asText()))) {
            throw Input.invalid("Remove sandboxes that depend on this connection first.");
        }
        store.resources.delete(store.get(project, kind, id));
    }

    private Resource saveResource(UUID project, String kind, UUID id, ObjectNode body) throws Exception {
        store.project(project);
        if ("connection".equals(kind)) return connections.save(project, id, body);
        String name = Input.string(body, "name", 120);
        ObjectNode data = pg.json.createObjectNode();
        switch (kind) {
            case "query" -> {
                String statement = Input.string(body, "sql", 100_000);
                Postgres.validate(statement, false);
                data.put("sql", statement).put("folder", Input.text(body, "folder", "", 120));
                data.set("parameters", SqlRunner.parameters(body));
            }
            case "index-config" -> {
                JsonNode specs = Benchmarks.array(body, "definitions", 12);
                for (JsonNode spec : specs) indexes.definition(spec);
                JsonNode disabled = Benchmarks.array(body, "disabled", 100);
                for (JsonNode item : disabled) if (!item.isTextual() || item.asText().length() > 127) throw Input.invalid("Invalid disabled index name.");
                data.put("removeOptional", body.path("removeOptional").asBoolean());
                data.set("definitions", specs);
                data.set("disabled", disabled);
            }
            case "dataset" -> {
                data.put("rows", Input.number(body, "rows", 1000, 0, 1_000_000))
                        .put("seed", Input.number(body, "seed", 42, Integer.MIN_VALUE, Integer.MAX_VALUE));
                data.set("tables", Input.object(body, "tables"));
                data.set("strategies", Input.object(body, "strategies"));
            }
            default -> throw Input.invalid("This resource cannot be saved here.");
        }
        Resource record = id == null ? store.create(project, kind, name, data) : store.get(project, kind, id);
        record.name = name;
        record.data = data;
        return store.save(record);
    }

    @PostMapping("/projects/{project}/connections/{id}/test")
    public JsonNode test(@PathVariable UUID project, @PathVariable UUID id) throws Exception {
        Resource record = store.get(project, "connection", id);
        ObjectNode info = pg.info(connections.target(record));
        ObjectNode data = record.data.deepCopy();
        data.set("server", info);
        data.put("testedAt", Instant.now().toString());
        record.data = data;
        store.save(record);
        return info;
    }

    @GetMapping("/projects/{project}/catalog/{kind}/{id}")
    public JsonNode catalog(@PathVariable UUID project, @PathVariable String kind, @PathVariable UUID id,
                            @RequestParam(defaultValue = "false") boolean exact) throws Exception {
        if ("connection".equals(kind)) return pg.schema(connections.target(store.get(project, kind, id)), exact);
        if (!"sandbox".equals(kind)) throw Input.invalid("Choose a connection or sandbox.");
        return sandboxes.exclusive(id, () -> pg.schema(sandboxes.target(store.get(project, kind, id)), exact));
    }

    @PostMapping("/projects/{project}/catalog/{kind}/{id}/snapshot")
    public Resource snapshot(@PathVariable UUID project, @PathVariable String kind, @PathVariable UUID id,
                             @RequestBody ObjectNode body) throws Exception {
        String name = Input.string(body, "name", 120);
        return store.save(store.create(project, "schema", name, catalog(project, kind, id, body.path("exact").asBoolean())));
    }

    @PostMapping("/projects/{project}/sandboxes") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Resource> sandbox(@PathVariable UUID project, @RequestBody ObjectNode body) {
        Resource sandbox = sandboxes.reserve(project, body);
        ObjectNode configuration = pg.json.createObjectNode().put("sandboxId", sandbox.id.toString());
        Resource job = jobs.submit(project, "sandbox", "Create " + sandbox.name, configuration, control -> sandboxes.exclusive(sandbox.id, () -> {
            control.progress(10, "Starting isolated PostgreSQL");
            sandboxes.provision(store.get(project, "sandbox", sandbox.id));
            return null;
        }));
        return Map.of("sandbox", sandbox, "job", job);
    }

    @PostMapping("/projects/{project}/sandboxes/{id}/{action}")
    public Resource lifecycle(@PathVariable UUID project, @PathVariable UUID id, @PathVariable String action) throws Exception {
        requireIdle(project);
        return sandboxes.lifecycle(store.get(project, "sandbox", id), action);
    }

    @PostMapping("/projects/{project}/sandboxes/{id}/import")
    public Map<String, Integer> importSql(@PathVariable UUID project, @PathVariable UUID id, @RequestBody ObjectNode body) throws Exception {
        return Map.of("statements", sandboxes.importSql(store.get(project, "sandbox", id), Input.string(body, "sql", 2_000_000)));
    }

    @GetMapping("/projects/{project}/sandboxes/{id}/indexes")
    public ArrayNode indexInventory(@PathVariable UUID project, @PathVariable UUID id) throws Exception {
        return sandboxes.exclusive(id, () -> {
            Resource sandbox = store.get(project, "sandbox", id);
            return indexes.inventory(sandbox, sandboxes.target(sandbox));
        });
    }

    @PostMapping("/projects/{project}/sandboxes/{id}/indexes/toggle")
    public ArrayNode toggle(@PathVariable UUID project, @PathVariable UUID id, @RequestBody ObjectNode body) throws Exception {
        return sandboxes.exclusive(id, () -> {
            Resource sandbox = store.get(project, "sandbox", id);
            indexes.toggle(sandbox, sandboxes.target(sandbox), body);
            return indexes.inventory(sandbox, sandboxes.target(sandbox));
        });
    }

    @PostMapping("/projects/{project}/sandboxes/{id}/indexes/remove")
    public ArrayNode removeIndex(@PathVariable UUID project, @PathVariable UUID id, @RequestBody ObjectNode body) throws Exception {
        return sandboxes.exclusive(id, () -> {
            Resource sandbox = store.get(project, "sandbox", id);
            indexes.remove(sandbox, sandboxes.target(sandbox), body);
            return indexes.inventory(sandbox, sandboxes.target(sandbox));
        });
    }

    @PostMapping("/projects/{project}/sandboxes/{id}/indexes/build")
    public Map<String, Double> build(@PathVariable UUID project, @PathVariable UUID id, @RequestBody ObjectNode body) throws Exception {
        return sandboxes.exclusive(id, () -> {
            try (Connection c = pg.open(sandboxes.target(store.get(project, "sandbox", id)))) {
                double elapsed = indexes.create(c, body);
                c.commit();
                return Map.of("indexBuildMs", elapsed);
            }
        });
    }

    @PostMapping("/projects/{project}/generate") @ResponseStatus(HttpStatus.ACCEPTED)
    public Resource generate(@PathVariable UUID project, @RequestBody ObjectNode body) {
        UUID id = Input.uuid(body, "sandboxId");
        Resource sandbox = store.get(project, "sandbox", id);
        ObjectNode config = body.deepCopy();
        if (body.has("datasetId")) {
            config = store.get(project, "dataset", Input.uuid(body, "datasetId")).data.deepCopy();
            config.put("sandboxId", id.toString()).put("replace", body.path("replace").asBoolean());
        }
        ObjectNode settings = config;
        return jobs.submit(project, "dataset", "Generate data in " + sandbox.name, settings,
                control -> sandboxes.exclusive(id, () -> {
                    generator.generate(sandboxes.target(store.get(project, "sandbox", id)), settings, control, control::progress);
                    return null;
                }));
    }

    @PostMapping("/projects/{project}/execute") @ResponseStatus(HttpStatus.ACCEPTED)
    public Resource execute(@PathVariable UUID project, @RequestBody ObjectNode body) throws Exception {
        UUID sandboxId = Input.uuid(body, "sandboxId");
        store.get(project, "sandbox", sandboxId);
        JsonNode queryIds = Benchmarks.array(body, "queryIds", 12);
        List<ObjectNode> queries = new ArrayList<>();
        if (!queryIds.isEmpty()) {
            for (JsonNode queryId : queryIds) {
                Resource query = store.get(project, "query", UUID.fromString(queryId.asText()));
                ObjectNode item = query.data.deepCopy();
                queries.add(item.put("name", query.name));
            }
        } else {
            ObjectNode query = pg.json.createObjectNode().put("sql", Input.string(body, "sql", 100_000)).put("name", "SQL worksheet");
            query.set("parameters", SqlRunner.parameters(body));
            queries.add(query);
        }
        for (JsonNode query : queries) Postgres.validate(query.path("sql").asText(), false);
        int timeout = Input.number(body, "timeout", 30, 1, 60);
        String mode = Input.text(body, "mode", "execute", 16);
        boolean commit = body.path("commit").asBoolean();
        return jobs.submit(project, "query", queries.size() == 1 ? queries.getFirst().path("name").asText() : "Saved queries",
                pg.json.createObjectNode().put("sandboxId", sandboxId.toString()).put("mode", mode),
                control -> sandboxes.exclusive(sandboxId, () -> {
                    ArrayNode output = pg.json.createArrayNode();
                    var target = sandboxes.target(store.get(project, "sandbox", sandboxId));
                    for (ObjectNode query : queries) {
                        control.progress(100 * output.size() / queries.size(), "Executing " + query.path("name").asText());
                        output.add(sql.execute(target, query.path("sql").asText(), query.path("parameters"), timeout, mode, commit, control)
                                .put("name", query.path("name").asText()));
                    }
                    if (previews.size() >= 24) previews.entrySet().stream().min(Comparator.comparing(e -> e.getValue().expires())).ifPresent(e -> previews.remove(e.getKey()));
                    previews.put(control.id(), new Preview(output, Instant.now().plusSeconds(900)));
                    return null;
                }));
    }

    @GetMapping("/projects/{project}/jobs/{id}/preview")
    public JsonNode preview(@PathVariable UUID project, @PathVariable UUID id) {
        store.get(project, "job", id);
        Preview result = previews.get(id);
        if (result == null || result.expires().isBefore(Instant.now())) throw Input.invalid("The query preview is unavailable or has expired.");
        return result.body();
    }

    @Scheduled(fixedDelay = 60_000)
    void expirePreviews() { previews.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now())); }

    @PostMapping("/projects/{project}/benchmarks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Resource benchmark(@PathVariable UUID project, @RequestBody ObjectNode body) throws Exception {
        return benchmarks.submit(project, body);
    }

    @PostMapping("/projects/{project}/loads") @ResponseStatus(HttpStatus.ACCEPTED)
    public Resource load(@PathVariable UUID project, @RequestBody ObjectNode body) throws Exception { return loads.start(project, body); }

    @GetMapping("/projects/{project}/loads/{id}")
    public JsonNode loadReport(@PathVariable UUID project, @PathVariable UUID id) { return loads.report(project, id); }

    @GetMapping("/projects/{project}/jobs/{id}") public Resource job(@PathVariable UUID project, @PathVariable UUID id) {
        return store.get(project, "job", id);
    }

    @GetMapping(value = "/projects/{project}/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID project, @PathVariable UUID id) { return jobs.events(project, id); }

    @PostMapping("/projects/{project}/jobs/{id}/cancel") @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@PathVariable UUID project, @PathVariable UUID id) { jobs.cancel(project, id); }

    @GetMapping("/projects/{project}/reports/{id}")
    public ObjectNode report(@PathVariable UUID project, @PathVariable UUID id) {
        Resource job = store.get(project, "job", id);
        if (!"benchmark".equals(job.data.path("type").asText())) throw Input.invalid("Choose a benchmark run.");
        ObjectNode result = pg.json.createObjectNode();
        result.set("job", pg.json.valueToTree(job));
        result.set("scenarios", results.list(id));
        return result;
    }

    @GetMapping("/projects/{project}/reports/{id}/export")
    public ResponseEntity<String> export(@PathVariable UUID project, @PathVariable UUID id,
                                         @RequestParam(defaultValue = "json") String format) {
        ObjectNode report = report(project, id);
        if ("json".equals(format)) return download(id, "json", MediaType.APPLICATION_JSON, report.toPrettyString());
        if ("html".equals(format)) return download(id, "html", MediaType.TEXT_HTML, html(report));
        if (!"csv".equals(format)) throw Input.invalid("Choose JSON, CSV or HTML.");
        StringBuilder csv = new StringBuilder("query,index_configuration,dataset_rows,iteration,client_ms,result_rows,postgres_execution_ms,planning_ms,index_bytes,table_bytes\r\n");
        for (JsonNode scenario : report.path("scenarios")) {
            JsonNode summary = scenario.path("summary");
            int iteration = 0;
            for (JsonNode sample : scenario.path("samples")) {
                csv.append(csvField(summary.path("query").asText())).append(',').append(csvField(summary.path("indexConfig").asText()))
                        .append(',').append(summary.path("size").asInt()).append(',').append(++iteration).append(',')
                        .append(sample.path("clientMs").asDouble()).append(',').append(sample.path("rows").asLong()).append(',')
                        .append(summary.path("executionMs").asText("")).append(',').append(summary.path("planningMs").asText(""))
                        .append(',').append(summary.path("indexBytes").asLong()).append(',').append(summary.path("tableBytes").asLong()).append("\r\n");
            }
        }
        return download(id, "csv", MediaType.parseMediaType("text/csv"), csv.toString());
    }

    private static String html(ObjectNode report) {
        String title = escapeHtml(report.path("job").path("data").path("label").asText());
        StringBuilder page = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
                <title>PostgreSQL benchmark report</title>
                <style>body{font:16px system-ui;margin:3rem auto;padding:1rem;max-width:72rem}
                table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.6rem;border-bottom:1px solid #bbb}
                </style><main><h1>
                """);
        page.append(title).append("</h1><p>Client timings are separate from the instrumented PostgreSQL execution time.</p>")
                .append("<table><thead><tr><th>Query</th><th>Index configuration</th><th>Rows per table</th>")
                .append("<th>Mean client ms</th><th>P95 client ms</th><th>PostgreSQL ms</th><th>Index bytes</th></tr></thead><tbody>");
        for (JsonNode item : report.path("scenarios")) {
            JsonNode summary = item.path("summary");
            page.append("<tr>");
            for (String key : List.of("query", "indexConfig", "size", "mean", "p95", "executionMs", "indexBytes")) {
                page.append("<td>").append(escapeHtml(summary.path(key).asText("—"))).append("</td>");
            }
            page.append("</tr>");
        }
        return page.append("</tbody></table></main></html>").toString();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String csvField(String value) {
        if (value.stripLeading().matches("(?s)^[=+@-].*") || value.matches("(?s)^[\\t\\r\\n].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static ResponseEntity<String> download(UUID id, String format, MediaType type, String body) {
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=benchmark-" + id + "." + format).body(body);
    }

    @PostMapping("/demo") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> demo() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Project project = createProject(pg.json.createObjectNode().put("name", "Commerce Lab " + suffix)
                .put("description", "Disposable PostgreSQL practice project"));
        Map<String, Resource> setup = sandbox(project.id, pg.json.createObjectNode()
                .put("name", "commerce-sandbox").put("mode", "demo").put("ttlHours", 8));
        Resource query = saveResource(project.id, "query", null, pg.json.createObjectNode().put("name", "Customer email lookup")
                .put("sql", "SELECT id, name, email FROM customers WHERE lower(email) = ?;")
                .set("parameters", pg.json.createArrayNode().add("user250@example.test")));
        Resource fallback = saveResource(project.id, "index-config", null, pg.json.createObjectNode().put("name", "Baseline"));
        ObjectNode btree = pg.json.createObjectNode().put("name", "B-tree index");
        ((ObjectNode) btree.putArray("definitions").addObject().put("name", "customers_lower_email_idx").put("table", "customers")
                .put("method", "btree").put("expression", "lower")).putArray("columns").add("email");
        Resource candidate = saveResource(project.id, "index-config", null, btree);
        ObjectNode config = pg.json.createObjectNode().put("name", "Customer lookup comparison").put("sandboxId", setup.get("sandbox").id.toString())
                .put("replaceData", true).put("warmups", 3).put("iterations", 12).put("seed", 42);
        config.putArray("sizes").add(1000).add(10000).add(100000);
        config.putArray("queryIds").add(query.id.toString());
        config.putArray("indexConfigIds").add(fallback.id.toString()).add(candidate.id.toString());
        return Map.of("project", project, "sandbox", setup.get("sandbox"), "job", setup.get("job"), "benchmark", config);
    }
}
