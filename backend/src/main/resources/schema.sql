CREATE TABLE IF NOT EXISTS deploys (
    service    TEXT   NOT NULL,
    version    TEXT   NOT NULL,
    first_seen BIGINT NOT NULL,
    PRIMARY KEY (service, version)
);

CREATE TABLE IF NOT EXISTS anomalies (
    id                  BIGSERIAL PRIMARY KEY,
    service             TEXT             NOT NULL,
    endpoint            TEXT             NOT NULL,
    kind                TEXT             NOT NULL,
    detected_at         BIGINT           NOT NULL,
    baseline_p95_ms     DOUBLE PRECISION NOT NULL,
    current_p95_ms      DOUBLE PRECISION NOT NULL,
    baseline_error_rate DOUBLE PRECISION NOT NULL,
    current_error_rate  DOUBLE PRECISION NOT NULL,
    example_trace_ids   TEXT             NOT NULL
);

CREATE INDEX IF NOT EXISTS anomalies_detected_at ON anomalies (detected_at DESC);
