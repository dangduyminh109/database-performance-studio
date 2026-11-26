package studio.performance.sandbox;

import org.postgresql.PGConnection;
import org.postgresql.core.Parser;
import studio.performance.shared.Input;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Pattern;

final class SqlImport {
    private static final String IDENTIFIER = "(?:[a-zA-Z_][a-zA-Z_0-9$]*|\"(?:[^\"]|\"\")+\")";
    private static final Pattern COPY = Pattern.compile("^COPY\\s+" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")?"
            + "\\s*\\(" + IDENTIFIER + "(?:\\s*,\\s*" + IDENTIFIER + ")*\\)\\s+FROM\\s+stdin\\s*;\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER = Pattern.compile("(?is)^ALTER\\s+.+\\s+OWNER\\s+TO\\s+" + IDENTIFIER + "\\s*;?\\s*$");
    private static final Pattern DUMP_SETTING = Pattern.compile("(?is)^SET\\s+([a-z_]+)\\s*=.+");
    private static final Set<String> SETTINGS = Set.of("statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout",
            "transaction_timeout", "client_encoding", "standard_conforming_strings", "check_function_bodies", "xmloption",
            "client_min_messages", "row_security", "default_tablespace", "default_table_access_method");

    int apply(Connection connection, String script) throws Exception {
        StringBuilder sql = new StringBuilder();
        StringBuilder rows = new StringBuilder();
        String copy = null;
        int count = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            for (String line : script.split("\\r?\\n", -1)) {
                if (copy != null) {
                    if (line.equals("\\.")) {
                        connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copy, new StringReader(rows.toString()));
                        copy = null;
                        rows.setLength(0);
                        count++;
                    } else rows.append(line).append('\n');
                } else if (COPY.matcher(line).matches()) {
                    count = statements(statement, sql.toString(), count);
                    sql.setLength(0);
                    copy = line;
                } else if (!line.matches("^\\\\(?:un)?restrict [A-Za-z0-9]+\\s*$")) {
                    sql.append(line).append('\n');
                }
                if (count > 5000) throw Input.invalid("SQL import allows up to 5,000 statements.");
            }
            if (copy != null) throw Input.invalid("The COPY block is missing its PostgreSQL end marker.");
            count = statements(statement, sql.toString(), count);
        }
        return count;
    }

    private int statements(Statement statement, String sql, int count) throws Exception {
        for (var command : Parser.parseJdbcSql(sql, true, false, true, false, false)) {
            String token = command.nativeSql.strip().replaceFirst("(?s)^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*", "");
            if (token.isBlank()) continue;
            if (++count > 5000) throw Input.invalid("SQL import allows up to 5,000 statements.");
            var setting = DUMP_SETTING.matcher(token);
            if (setting.matches() && SETTINGS.contains(setting.group(1).toLowerCase(java.util.Locale.ROOT))) continue;
            if (OWNER.matcher(token).matches()) continue;
            if (token.matches("(?is)SELECT\\s+pg_catalog\\.set_config\\('search_path',\\s*'',\\s*false\\)\\s*;?")) continue;
            if (!token.matches("(?is)(CREATE|ALTER|COMMENT|INSERT|UPDATE|DELETE|SELECT|GRANT|REVOKE|DROP|TRUNCATE|WITH)\\b.*")) {
                throw Input.invalid("The dump contains an unsupported SQL or psql command.");
            }
            statement.execute(token);
        }
        return count;
    }
}
