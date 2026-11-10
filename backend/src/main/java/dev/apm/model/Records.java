package dev.apm.model;

import java.util.List;

/** Small value types shared by the API, stores and MCP server. */
public final class Records {
    private Records() {}

    public record Deploy(String service, String version, long firstSeenMillis) {}

    public record Anomaly(String service, String endpoint, String kind, long detectedAtMillis,
                          double baselineP95Ms, double currentP95Ms,
                          double baselineErrorRate, double currentErrorRate,
                          List<String> exampleTraceIds) {}

    /** RED metrics for a service or endpoint over a window. */
    public record Red(String service, String endpoint, long count, double ratePerSec, double errorRate,
                      double p50Ms, double p95Ms, double p99Ms, List<String> exampleTraceIds) {}

    public record Edge(String from, String to, long calls, long errors, double avgMs) {}

    public record ServiceMap(List<Red> nodes, List<Edge> edges) {}

    /** Rows are time buckets, columns are latency bins; counts[t][b]. */
    public record Heatmap(List<Long> bucketStartMillis, List<Double> binUpperMs, List<long[]> counts) {}

    public record TraceRow(Span span, int depth, long offsetMicros) {}

    public record TraceView(String traceId, String rootService, String rootName, long startNanos,
                            long durationMicros, int spanCount, int errorCount,
                            List<String> services, List<TraceRow> rows) {}

    public record TraceSummary(String traceId, String rootService, String rootName, long startNanos,
                               long durationMicros, int spanCount, int errorCount) {}

    public record EndpointDiff(String endpoint, Red baseline, Red candidate, double p95ChangePct,
                               double errorRateChange) {}

    public record DeployComparison(String service, String baselineVersion, String candidateVersion,
                                   List<EndpointDiff> endpoints) {}
}
