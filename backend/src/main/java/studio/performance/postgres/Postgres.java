package studio.performance.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.PGProperty;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;
import org.postgresql.core.SqlCommandType;
import org.springframework.stereotype.Component;
import studio.performance.shared.Input;
import studio.performance.shared.engine.DatabaseEngine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@Component
public class Postgres implements DatabaseEngine<Target> {
    private static final Set<SqlCommandType> QUERIES = EnumSet.of(
            SqlCommandType.SELECT, SqlCommandType.INSERT, SqlCommandType.UPDATE, SqlCommandType.DELETE);
    public final ObjectMapper json;

    public Postgres(ObjectMapper json) { this.json = json; }

    @Override
    public Connection open(Target target) throws SQLException {
        Properties properties = new Properties();
        PGProperty.USER.set(properties, target.username());
        PGProperty.PASSWORD.set(properties, target.password());
        PGProperty.SSL_MODE.set(properties, target.sslMode());
        PGProperty.CONNECT_TIMEOUT.set(properties, target.timeoutSeconds());
        PGProperty.SOCKET_TIMEOUT.set(properties, 300);
        PGProperty.CANCEL_SIGNAL_TIMEOUT.set(properties, 3);
        PGProperty.OPTIONS.set(properties, "-c search_path=public -c timezone=UTC -c lock_timeout=5000 -c statement_timeout=60000 -c standard_conforming_strings=on");
        String host = target.host().contains(":") ? "[" + target.host() + "]" : target.host();
        String database = URLEncoder.encode(target.database(), StandardCharsets.UTF_8).replace("+", "%20");
        Connection connection = DriverManager.getConnection("jdbc:postgresql://" + host + ":" + target.port() + "/" + database, properties);
        try {
            connection.setReadOnly(target.readOnly());
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
    }

    @Override
    public ObjectNode info(Target target) throws Exception {
        try (Connection connection = open(target); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                 select current_setting('server_version'), current_database(),
                        (select coalesce(json_agg(extname order by extname), '[]') from pg_extension)
                 """)) {
            rows.next();
            return json.createObjectNode().put("version", rows.getString(1)).put("database", rows.getString(2))
                    .set("extensions", json.readTree(rows.getString(3)));
        }
    }

    @Override
    public ObjectNode schema(Target target, boolean exact) throws Exception {
        try (Connection c = open(target)) {
            ObjectNode result = json.createObjectNode();
            result.set("schemas", readArray(c, """
                select nspname as name from pg_namespace where nspname !~ '^pg_' and nspname <> 'information_schema' order by 1
                """));
            ArrayNode tables = readArray(c, """
                select n.nspname as schema, t.relname as name, t.relkind::text as kind,
                    greatest(t.reltuples,0)::bigint as "estimatedRows", pg_table_size(t.oid) as "tableBytes",
                    case when t.relkind in ('v','m') then pg_get_viewdef(t.oid,true) end as definition,
                    (select coalesce(json_agg(row_to_json(col) order by col.position),'[]') from (
                        select a.attname as name, format_type(a.atttypid,a.atttypmod) as type, y.typname as "baseType",
                            a.attnum as position, not a.attnotnull as nullable,
                            pg_get_expr(d.adbin,d.adrelid) as "defaultValue", a.attidentity::text as identity,
                            a.attgenerated::text as generated,
                            (select json_agg(e.enumlabel order by e.enumsortorder) from pg_enum e where e.enumtypid=y.oid) as enum,
                            s.null_frac as "nullFraction", s.n_distinct as "distinctEstimate"
                        from pg_attribute a join pg_type y on y.oid=a.atttypid
                        left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                        left join pg_stats s on s.schemaname=n.nspname and s.tablename=t.relname and s.attname=a.attname and not s.inherited
                        where a.attrelid=t.oid and a.attnum>0 and not a.attisdropped
                    ) col) as columns,
                    (select coalesce(json_agg(row_to_json(con) order by con.name),'[]') from (
                        select k.conname as name, k.contype::text as type, pg_get_constraintdef(k.oid,true) as definition,
                            k.condeferrable as deferrable, fn.nspname as "foreignSchema", ft.relname as "foreignTable",
                            (select json_agg(a.attname order by x.ord) from unnest(k.conkey) with ordinality x(num,ord)
                                 join pg_attribute a on a.attrelid=t.oid and a.attnum=x.num) as columns,
                            (select json_agg(a.attname order by x.ord) from unnest(k.confkey) with ordinality x(num,ord)
                                 join pg_attribute a on a.attrelid=ft.oid and a.attnum=x.num) as "foreignColumns"
                        from pg_constraint k left join pg_class ft on ft.oid=k.confrelid
                        left join pg_namespace fn on fn.oid=ft.relnamespace where k.conrelid=t.oid
                    ) con) as constraints
                from pg_class t join pg_namespace n on n.oid=t.relnamespace
                where t.relkind in ('r','p','v','m','f') and n.nspname !~ '^pg_' and n.nspname <> 'information_schema'
                order by 1,2
                """);
            if (exact) {
                for (JsonNode table : tables) {
                    if (!Set.of("r", "p").contains(table.path("kind").asText())) continue;
                    try (Statement st = c.createStatement()) {
                        st.setQueryTimeout(10);
                        try (ResultSet rs = st.executeQuery("select count(*) from " + relation(table))) {
                            rs.next();
                            ((ObjectNode) table).put("exactRows", rs.getLong(1));
                        }
                    }
                }
            }
            result.set("tables", tables);
            result.set("indexes", indexes(c));
            result.set("sequences", readArray(c, """
                select schemaname as schema, sequencename as name, data_type::text as type,
                start_value as start, increment_by as increment from pg_sequences order by 1,2
                """));
            result.set("extensions", readArray(c, "select extname as name, extversion as version from pg_extension order by 1"));
            return result;
        }
    }

    public ArrayNode indexes(Connection c) throws Exception {
        return readArray(c, """
            select n.nspname as schema, i.relname as name, t.relname as table, am.amname as method,
                x.indisunique as unique, x.indisvalid as valid, x.indisprimary as primary,
                (x.indisprimary or x.indisreplident or exists(select 1 from pg_constraint k where k.conindid=i.oid)
                    or exists(select 1 from pg_depend d where d.classid='pg_class'::regclass and d.objid=i.oid
                        and d.deptype in ('e','i','P','S'))) as protected,
                pg_get_indexdef(i.oid) as definition, pg_get_expr(x.indpred,x.indrelid) as predicate,
                pg_get_expr(x.indexprs,x.indrelid) as expressions,
                (select json_agg(pg_get_indexdef(i.oid,col,true) order by col) from generate_series(1,x.indnatts) col) as columns,
                x.indnkeyatts as "keyColumns", pg_relation_size(i.oid) as bytes, coalesce(s.idx_scan,0) as scans
            from pg_index x join pg_class i on i.oid=x.indexrelid join pg_class t on t.oid=x.indrelid
                join pg_namespace n on n.oid=i.relnamespace join pg_am am on am.oid=i.relam
                left join pg_stat_user_indexes s on s.indexrelid=i.oid
            where n.nspname !~ '^pg_' and n.nspname <> 'information_schema' order by 1,2
            """);
    }

    public ArrayNode readArray(Connection connection, String sql) throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rows = st.executeQuery("select row_to_json(r) from (" + sql + ") r")) {
            ArrayNode result = json.createArrayNode();
            while (rows.next()) result.add(json.readTree(rows.getString(1)));
            return result;
        }
    }

    public static String relation(JsonNode table) {
        return Input.relation(table.path("schema").asText(), table.path("name").asText());
    }

    public static SqlCommandType validate(String sql, boolean source) throws SQLException {
        List<NativeQuery> statements = Parser.parseJdbcSql(sql, true, true, true, false, false);
        if (statements.size() != 1 || statements.getFirst().multiStatement) {
            throw Input.invalid("Run one SQL statement at a time.");
        }
        SqlCommandType type = statements.getFirst().command.getType();
        if (!QUERIES.contains(type) || (source && type != SqlCommandType.SELECT)) {
            throw Input.invalid("Use SELECT, INSERT, UPDATE or DELETE in a sandbox. Sources allow SELECT only.");
        }
        return type;
    }

    public PreparedStatement prepare(Connection c, String sql, JsonNode params, int timeout) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeout);
            int position = 1;
            for (JsonNode parameter : params) {
                if (parameter.isNull()) statement.setNull(position, Types.NULL);
                else if (parameter.isBoolean()) statement.setBoolean(position, parameter.asBoolean());
                else if (parameter.isNumber()) statement.setBigDecimal(position, parameter.decimalValue());
                else if (parameter.isTextual()) statement.setObject(position, parameter.asText(), Types.OTHER);
                else statement.setObject(position, parameter.toString(), Types.OTHER);
                position++;
            }
            return statement;
        } catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }
}
