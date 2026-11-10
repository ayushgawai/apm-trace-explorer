package dev.apm.model;

import java.util.Map;

/** One span, normalized from OTLP. Times are unix nanos. */
public record Span(
        String traceId,
        String spanId,
        String parentSpanId,
        String service,
        String version,
        String name,
        String kind,
        long startNanos,
        long durationNanos,
        boolean error,
        Map<String, String> attributes) {

    public long durationMicros() {
        return durationNanos / 1_000;
    }

    public boolean root() {
        return parentSpanId == null || parentSpanId.isEmpty();
    }

    /** Spans that represent a request handled by a service: these feed RED metrics. */
    public boolean entry() {
        return root() || "SERVER".equals(kind) || "CONSUMER".equals(kind);
    }
}
