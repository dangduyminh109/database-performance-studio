package studio.performance.postgres;

public record Target(String host, int port, String database, String username, String password,
                     String sslMode, int timeoutSeconds, boolean readOnly) {
    @Override
    public String toString() {
        return "PostgreSQL target";
    }
}
