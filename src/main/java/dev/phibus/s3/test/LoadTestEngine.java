package dev.phibus.s3.test;

/**
 * Common execution boundary for workload engines.
 *
 * <p>The current TestRun model is retained for S3 compatibility in PR 97.
 * Subsequent engines can extend the common request/result model behind this
 * boundary without coupling TestRunService to a concrete implementation.</p>
 */
public interface LoadTestEngine {
    TestType type();

    void execute(TestRun run);
}
