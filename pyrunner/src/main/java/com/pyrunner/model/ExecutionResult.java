package com.pyrunner.model;

public record ExecutionResult(
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        boolean timedOut
) {
    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
