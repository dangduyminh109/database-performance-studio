package studio.performance.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
public class Docker {
    private final String executable;

    public Docker(@Value("${studio.docker}") String executable) {
        this.executable = executable;
    }

    public String run(Duration timeout, String input, String... arguments) throws Exception {
        Path output = Files.createTempFile("studio-docker-", ".log");
        try {
            invoke(timeout, input, null, output, List.of(arguments));
            if (Files.size(output) > 128_000) throw new IOException("Unexpected Docker output.");
            return Files.readString(output).strip();
        } finally {
            Files.deleteIfExists(output);
        }
    }

    public void dump(String host, String port, String database, String user, String ssl, String password,
                     String image, boolean schemaOnly, Path archive) throws Exception {
        if (password.contains("\n") || password.contains("\r")) throw new IOException("Dump credentials cannot contain line breaks.");
        List<String> args = new ArrayList<>(List.of("run", "--rm", "-i", "--network", "host", "--memory", "256m",
                "--pids-limit", "128", "--security-opt", "no-new-privileges",
                "-e", "PGSSLMODE=" + ssl, "-e", "PGOPTIONS=-c default_transaction_read_only=on",
                "--entrypoint", "/bin/sh", image, "-c",
                "IFS= read -r PGPASSWORD; export PGPASSWORD; exec pg_dump \"$@\"", "studio-dump",
                "--format=custom", "--no-owner", "--no-privileges", "--lock-wait-timeout=5000",
                "--host", host, "--port", port, "--username", user, "--dbname", database));
        if (schemaOnly) args.add("--schema-only");
        invoke(Duration.ofMinutes(5), password + "\n", null, archive, args);
    }

    public void restore(String container, Path archive) throws Exception {
        invoke(Duration.ofMinutes(5), null, archive, null, List.of("exec", "-i", container, "pg_restore",
                "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges", "--no-tablespaces",
                "--username=studio_work", "--dbname=studio"));
    }

    private void invoke(Duration timeout, String input, Path inputFile, Path output, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(output.toFile()));
        if (inputFile != null) builder.redirectInput(inputFile.toFile());
        Process process = builder.start();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        var kill = timer.schedule(process::destroyForcibly, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            if (inputFile == null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    if (input != null) stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (process.waitFor() != 0) throw new IOException("Docker operation failed or exceeded its timeout.");
        } finally {
            kill.cancel(false);
            timer.shutdownNow();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
