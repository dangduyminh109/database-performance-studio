package studio.performance.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> input(IllegalArgumentException ex) {
        String message = ex.getClass().equals(IllegalArgumentException.class) && ex.getMessage() != null
                && !ex.getMessage().startsWith("Invalid UUID string:") ? ex.getMessage() : "Invalid request.";
        return response(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<Map<String, String>> database(SQLException ex) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "PostgreSQL could not complete the request (SQLSTATE " + ex.getSQLState() + ").");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<Map<String, String>> conflict() {
        return response(HttpStatus.CONFLICT, "The name is already in use or the resource changed. Refresh and retry.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> missing(ResponseStatusException ex) {
        return response(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason() == null ? "Request failed." : ex.getReason());
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> malformed() { return response(HttpStatus.BAD_REQUEST, "Invalid request."); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The operation failed. Check service and sandbox availability.");
    }

    private ResponseEntity<Map<String, String>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
