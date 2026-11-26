package studio.performance.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

public final class Input {
    private Input() {}

    public static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public static String string(JsonNode value, String key, int max) {
        String result = text(value, key, "", max);
        if (result.isBlank()) throw invalid(key + " is required.");
        return result;
    }

    public static String text(JsonNode value, String key, String fallback, int max) {
        JsonNode entry = value.path(key);
        if (entry.isMissingNode() || entry.isNull()) return fallback;
        if (!entry.isTextual() || entry.textValue().length() > max || entry.textValue().contains("\0")) {
            throw invalid("Invalid " + key + ".");
        }
        return entry.textValue();
    }

    public static int number(JsonNode value, String key, int fallback, int min, int max) {
        JsonNode entry = value.path(key);
        if (entry.isMissingNode()) return fallback;
        if (!entry.isIntegralNumber() || !entry.canConvertToInt() || entry.intValue() < min || entry.intValue() > max) {
            throw invalid(key + " must be between " + min + " and " + max + ".");
        }
        return entry.intValue();
    }

    public static UUID uuid(JsonNode value, String key) {
        try {
            return UUID.fromString(string(value, key, 36));
        } catch (IllegalArgumentException ignored) {
            throw invalid("Invalid " + key + ".");
        }
    }

    public static JsonNode object(JsonNode value, String key) {
        JsonNode entry = value.path(key);
        if (entry.isMissingNode()) return JsonNodeFactory.instance.objectNode();
        if (!entry.isObject()) throw invalid(key + " must be an object.");
        return entry;
    }

    public static String identifier(String value) {
        if (value.isEmpty() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63 || value.contains("\0")) {
            throw invalid("Invalid PostgreSQL identifier.");
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static String relation(String schema, String name) {
        return identifier(schema) + "." + identifier(name);
    }
}
