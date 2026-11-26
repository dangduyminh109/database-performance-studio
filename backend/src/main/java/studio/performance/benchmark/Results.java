package studio.performance.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class Results {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Results(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public void insert(UUID job, int ordinal, JsonNode summary, JsonNode plan, List<SqlRunner.Timing> samples) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into benchmark_scenarios values (?,?,?,?::jsonb,?::jsonb)", id, job, ordinal, summary.toString(), plan.toString());
        jdbc.batchUpdate("insert into benchmark_iterations values (?,?,?,?)", java.util.stream.IntStream.range(0, samples.size())
                .mapToObj(i -> new Object[] { id, i, samples.get(i).ms(), samples.get(i).rows() }).toList());
    }

    public ArrayNode list(UUID job) {
        return jdbc.query("""
                select s.summary, s.plan,
                    (select json_agg(json_build_object('clientMs',i.client_ms,'rows',i.rows) order by i.ordinal)
                    from benchmark_iterations i where i.scenario_id=s.id) as samples
                from benchmark_scenarios s where s.job_id=? order by s.ordinal
                """, rs -> {
            ArrayNode array = json.createArrayNode();
            while (rs.next()) {
                var row = array.addObject();
                try {
                    row.set("summary", json.readTree(rs.getString(1)));
                    row.set("plan", json.readTree(rs.getString(2)));
                    row.set("samples", json.readTree(rs.getString(3)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new java.sql.SQLException("Unable to decode benchmark results.");
                }
            }
            return array;
        }, job);
    }
}
