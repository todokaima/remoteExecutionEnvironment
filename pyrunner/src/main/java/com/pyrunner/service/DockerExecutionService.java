package com.pyrunner.service;

import com.pyrunner.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class DockerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionService.class);

    @Value("${runner.docker.image:python:3.11-slim}")
    private String dockerImage;

    @Value("${runner.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${runner.memory-limit:64m}")
    private String memoryLimit;

    @Value("${runner.cpu-quota:50000}")
    private String cpuQuota;

    /**
     * Executes the given Python source code inside an ephemeral Docker container.
     * The script is written to a temp file, bind-mounted read-only into the container,
     * then executed with `python script.py`. The container is automatically removed after
     * execution (--rm flag).
     */
    public ExecutionResult execute(String code) throws IOException, InterruptedException {
        // Write the script to a temporary file so we can bind-mount it
        Path tempDir = Files.createTempDirectory("pyrunner-");
        Path scriptPath = tempDir.resolve("script.py");
        Files.writeString(scriptPath, code, StandardCharsets.UTF_8);

        String containerId = "pyrunner-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> command = List.of(
                "docker", "run",
                "--rm",
                "--name", containerId,
                "--network", "none",           // no network access
                "--memory", memoryLimit,        // memory cap
                "--cpu-quota", cpuQuota,        // ~50% of one CPU
                "--read-only",                  // read-only root FS
                "--tmpfs", "/tmp:size=8m",      // writable /tmp only
                "--security-opt", "no-new-privileges",
                "-v", scriptPath.toAbsolutePath() + ":/sandbox/script.py:ro",
                "--workdir", "/sandbox",
                dockerImage,
                "python", "script.py"
        );

        log.info("Launching container [{}] for script execution", containerId);

        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Capture stdout and stderr concurrently to avoid blocking
        ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = ioExecutor.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = ioExecutor.submit(() -> readStream(process.getErrorStream()));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - start;

        if (!finished) {
            log.warn("Container [{}] timed out after {}s — killing", containerId, timeoutSeconds);
            process.destroyForcibly();
            // Also force-kill the container in case --rm hasn't fired yet
            new ProcessBuilder("docker", "kill", containerId)
                    .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS);
            ioExecutor.shutdownNow();
            cleanup(tempDir);
            return new ExecutionResult("", "Execution timed out after " + timeoutSeconds + " seconds.", -1, durationMs, true);
        }

        int exitCode = process.exitValue();
        String stdout = "";
        String stderr = "";

        try {
            stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            stderr = stderrFuture.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Could not fully read process streams: {}", e.getMessage());
        }

        ioExecutor.shutdown();
        cleanup(tempDir);

        log.info("Container [{}] exited with code {} in {}ms", containerId, exitCode, durationMs);
        return new ExecutionResult(stdout, stderr, exitCode, durationMs, false);
    }

    private String readStream(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 1000) {
                sb.append(line).append("\n");
                lineCount++;
            }
            if (lineCount == 1000) {
                sb.append("\n[Output truncated at 1000 lines]");
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Failed to cleanup temp dir {}: {}", dir, e.getMessage());
        }
    }
}
