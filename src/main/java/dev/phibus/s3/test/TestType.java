package dev.phibus.s3.test;

/**
 * Identifies the workload engine used to execute a test.
 *
 * <p>S3 is the existing production engine. CLICKHOUSE is reserved by the
 * platform contract so the next implementation can be added without changing
 * coordinator/agent engine selection semantics.</p>
 */
public enum TestType {
    S3,
    CLICKHOUSE
}
