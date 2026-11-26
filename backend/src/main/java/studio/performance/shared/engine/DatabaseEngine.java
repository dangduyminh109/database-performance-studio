package studio.performance.shared.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;

/** The connection and catalog capabilities used by the metadata workspace. */
public interface DatabaseEngine<T> {
    Connection open(T target) throws SQLException;
    ObjectNode info(T target) throws Exception;
    ObjectNode schema(T target, boolean exact) throws Exception;
}
