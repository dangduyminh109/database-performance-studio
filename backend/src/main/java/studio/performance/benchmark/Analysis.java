package studio.performance.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import studio.performance.postgres.Postgres;
import studio.performance.shared.Input;

import java.util.regex.Pattern;

@Component
public class Analysis {
    private static final Pattern FILTER = Pattern.compile("^\\(*([a-z_][a-z0-9_]*)\\s*(=|>=|<=|>|<|~~\\*?)\\s.*", Pattern.DOTALL);

    private static final Pattern EXPRESSION = Pattern.compile("^\\(*(lower|upper)\\(([a-z_][a-z0-9_]*)\\)\\s*(=|~~\\*?)\\s.*", Pattern.DOTALL);
    private static final Pattern LOOKUP = Pattern.compile("([a-z_][a-z0-9_]*)\\s*=\\s*(?:'[^']*'|[0-9]+|true|false)", Pattern.CASE_INSENSITIVE);

    public ArrayNode recommend(Postgres pg, JsonNode plan) {
        ArrayNode findings = pg.json.createArrayNode();
        visit(plan.path(0).path("Plan"), findings);
        return findings;
    }

    private void visit(JsonNode node, ArrayNode findings) {
        if (node.isMissingNode() || findings.size() >= 12) return;
        long actual = node.path("Actual Rows").asLong();
        long estimate = node.path("Plan Rows").asLong();
        long removed = node.path("Rows Removed by Filter").asLong();
        String type = node.path("Node Type").asText();
        if (removed > 1000 && removed > actual * 4 && type.equals("Seq Scan")) {
            ObjectNode issue = finding(findings, "Selective filter on sequential scan",
                    removed + " rows removed per loop and " + actual + " returned.",
                    "The scan examined substantially more rows than it returned.",
                    "Compare a selective index with the original plan at representative sizes.",
                    "Fewer rows examined for selective predicates.",
                    "Index storage and write maintenance can exceed the benefit for common values.");
            String filter = node.path("Filter").asText();
            String relation = node.path("Relation Name").asText();
            if (!relation.isBlank()) {
                String schema = node.path("Schema").asText("public");
                var expression = EXPRESSION.matcher(filter);
                var simple = FILTER.matcher(filter);
                var conditions = LOOKUP.matcher(filter);
                java.util.LinkedHashSet<String> equality = new java.util.LinkedHashSet<>();
                while (conditions.find()) equality.add(conditions.group(1));
                if (expression.matches()) {
                    String operator = expression.group(3);
                    boolean trigram = operator.startsWith("~~");
                    suggest(issue, schema, relation, java.util.List.of(expression.group(2)), expression.group(1), trigram);
                    issue.put("action", "Compare an index on the expression used by the filter.");
                } else if (filter.contains(" AND ") && equality.size() > 1 && equality.size() <= 3) {
                    suggest(issue, schema, relation, java.util.List.copyOf(equality), "", false);
                    issue.put("action", "Compare a composite index on the equality predicates.");
                } else if (simple.matches()) {
                    boolean trigram = simple.group(2).startsWith("~~");
                    suggest(issue, schema, relation, java.util.List.of(simple.group(1)), "", trigram);
                    if (trigram) issue.put("action", "Compare a GIN trigram index for the text pattern.");
                }
            }
        }
        if (Math.max(actual, estimate) >= 100 && (Math.max(actual, estimate) + 1d) / (Math.min(actual, estimate) + 1d) >= 10) {
            finding(findings, "Cardinality estimate differs from execution", type + " estimated " + estimate + " and produced " + actual + " rows per loop.",
                    "Row estimates inform access and join choices.", "Refresh statistics and test extended statistics for correlated predicates.",
                    "More accurate plan selection.", "Statistics collection consumes resources and can change other plans.")
                    .put("sql", "ANALYZE;");
        }
        if (node.path("Temp Written Blocks").asLong() > 0 && node.path("Plans").isEmpty()) {
            finding(findings, "Temporary blocks written", type + " wrote " + node.path("Temp Written Blocks").asLong() + " temporary blocks.",
                    "The operation spilled temporary data.", "Reduce the input rows or compare a session-specific work_mem setting.",
                    "Reduced temporary file I/O.", "Additional memory can multiply across sessions and plan nodes.");
        }
        if (type.equals("Nested Loop") && node.path("Plans").size() > 1
                && node.path("Plans").path(1).path("Actual Loops").asLong() > 1000) {
            finding(findings, "Repeated inner join work", "The inner node ran " + node.path("Plans").path(1).path("Actual Loops").asLong() + " times.",
                    "Repeated inner access can dominate a join as the outer input grows.", "Test a join-key index and refresh estimates.",
                    "Lower inner access cost for large outer inputs.", "Index maintenance can outweigh improvements for small inputs.");
        }
        node.path("Plans").forEach(child -> visit(child, findings));
    }

    private void suggest(ObjectNode issue, String schema, String relation, java.util.List<String> fields,
                         String expression, boolean trigram) {
        String indexName = "dps_candidate_" + Integer.toUnsignedString(java.util.Objects.hash(schema, relation, fields, expression), 36);
        String keys = String.join(", ", fields.stream().map(Input::identifier).toList());
        if (!expression.isEmpty()) keys = expression + "(" + keys + ")";
        if (trigram) keys += " gin_trgm_ops";
        String sql = "CREATE INDEX " + Input.identifier(indexName) + " ON " + Input.relation(schema, relation)
                + (trigram ? " USING gin" : "") + " (" + keys + ")";
        issue.put("sql", sql);
        ObjectNode spec = issue.putObject("index").put("schema", schema).put("table", relation).put("name", indexName)
                .put("method", trigram ? "gin" : "btree").put("trigram", trigram).put("expression", expression);
        ArrayNode columns = spec.putArray("columns");
        fields.forEach(columns::add);
    }

    public void observeIndexes(JsonNode inventory, ArrayNode findings) {
        java.util.Map<String, JsonNode> equivalent = new java.util.HashMap<>();
        for (JsonNode index : inventory) {
            if (findings.size() >= 12) return;
            if (!index.path("valid").asBoolean()) continue;
            String key = String.join("|", index.path("schema").asText(), index.path("table").asText(),
                    index.path("method").asText(), index.path("columns").toString(), index.path("predicate").toString(),
                    index.path("expressions").toString(), index.path("unique").asText(), index.path("keyColumns").asText());
            JsonNode previous = equivalent.putIfAbsent(key, index);
            if (previous != null) {
                finding(findings, "Potential duplicate indexes", previous.path("name").asText() + " and " + index.path("name").asText()
                        + " share the same access method, visible keys and predicate.",
                        "Overlapping definitions may duplicate write and storage work.",
                        "Check operator classes, collation, constraints, index options and representative workloads before disabling either.",
                        "Less index storage and maintenance if one proves redundant.",
                        "Workloads or dependencies may rely on a specific index.");
            } else if (!index.path("protected").asBoolean() && index.path("scans").asLong() == 0 && index.path("bytes").asLong() >= 1_048_576) {
                finding(findings, "No scans recorded for a sizeable optional index", index.path("name").asText() + " occupies "
                        + index.path("bytes").asLong() + " bytes and has a cumulative scan counter of zero.",
                        "The counter depends on the statistics window and workload.", "Observe a representative workload before testing an exclusion.",
                        "A comparison can quantify maintenance and storage costs.", "Rare queries may depend on the index.");
            }
        }
    }

    private ObjectNode finding(ArrayNode findings, String issue, String evidence, String explanation, String action, String benefit, String drawback) {
        return findings.addObject().put("issue", issue).put("evidence", evidence).put("explanation", explanation).put("action", action)
                .put("benefit", benefit).put("drawback", drawback)
                .put("verification", "Rerun the original and candidate configurations with identical dataset seeds, parameters and iteration counts.");
    }
}
