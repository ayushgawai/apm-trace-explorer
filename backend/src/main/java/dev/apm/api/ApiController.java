package dev.apm.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apm.anomaly.AnomalyDetector;
import dev.apm.ingest.IngestService;
import dev.apm.ingest.Otlp;
import dev.apm.ingest.TraceAssembler;
import dev.apm.metrics.MetricsAggregator;
import dev.apm.model.Records.Anomaly;
import dev.apm.model.Records.Deploy;
import dev.apm.model.Records.DeployComparison;
import dev.apm.model.Records.EndpointDiff;
import dev.apm.model.Records.Heatmap;
import dev.apm.model.Records.Red;
import dev.apm.model.Records.ServiceMap;
import dev.apm.model.Records.TraceSummary;
import dev.apm.model.Records.TraceView;
import dev.apm.sim.TraceGenerator;
import dev.apm.store.MetadataStore;
import dev.apm.store.SpanStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

@RestController
public class ApiController {
    private final IngestService ingest;
    private final MetricsAggregator metrics;
    private final SpanStore spans;
    private final MetadataStore metadata;
    private final AnomalyDetector detector;

    public ApiController(IngestService ingest, MetricsAggregator metrics, SpanStore spans, MetadataStore metadata,
                         AnomalyDetector detector) {
        this.ingest = ingest;
        this.metrics = metrics;
        this.spans = spans;
        this.metadata = metadata;
        this.detector = detector;
    }

    /** OTLP/HTTP JSON receiver: point an OpenTelemetry Collector otlphttp exporter (encoding: json) here. */
    @PostMapping(path = "/v1/traces", consumes = "application/json")
    public Map<String, Object> otlp(@RequestBody JsonNode body) {
        ingest.ingest(Otlp.decode(body));
        return Map.of();
    }

    @GetMapping("/api/services")
    public List<Red> services(@RequestParam(defaultValue = "15") int minutes) {
        long[] w = window(minutes);
        return metrics.services(w[0], w[1]);
    }

    /** sort=p95 returns the slowest endpoints first (the find_slow_endpoints MCP tool uses this). */
    @GetMapping("/api/endpoints")
    public List<Red> endpoints(@RequestParam(required = false) String service,
                               @RequestParam(defaultValue = "15") int minutes,
                               @RequestParam(defaultValue = "name") String sort,
                               @RequestParam(defaultValue = "500") int limit) {
        long[] w = window(minutes);
        List<Red> out = new ArrayList<>(metrics.endpoints(service, w[0], w[1]));
        if (sort.equals("p95")) out.sort(Comparator.comparingDouble(Red::p95Ms).reversed());
        if (sort.equals("errors")) out.sort(Comparator.comparingDouble(Red::errorRate).reversed());
        return out.stream().limit(limit).toList();
    }

    @GetMapping("/api/service-map")
    public ServiceMap serviceMap(@RequestParam(defaultValue = "15") int minutes) {
        long[] w = window(minutes);
        return new ServiceMap(metrics.services(w[0], w[1]), metrics.edges(w[0], w[1]));
    }

    @GetMapping("/api/heatmap")
    public Heatmap heatmap(@RequestParam(required = false) String service,
                           @RequestParam(required = false) String endpoint,
                           @RequestParam(defaultValue = "30") int minutes) {
        long[] w = window(minutes);
        return metrics.heatmap(service, endpoint, w[0], w[1]);
    }

    @GetMapping("/api/traces/{traceId}")
    public ResponseEntity<TraceView> trace(@PathVariable String traceId) {
        TraceView v = TraceAssembler.assemble(traceId, spans.trace(traceId));
        return v == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(v);
    }

    @GetMapping("/api/traces")
    public List<TraceSummary> recentTraces(@RequestParam(required = false) String service,
                                           @RequestParam(defaultValue = "30") int limit) {
        List<TraceSummary> out = new ArrayList<>();
        // ponytail: loads up to 300 recent traces and filters in memory; add a per-service index if this gets slow
        for (String id : spans.recentTraceIds(service == null ? limit : 300)) {
            TraceView v = TraceAssembler.assemble(id, spans.trace(id));
            if (v != null && (service == null || v.services().contains(service))) out.add(TraceAssembler.summary(v));
            if (out.size() >= limit) break;
        }
        return out;
    }

    @GetMapping("/api/anomalies")
    public List<Anomaly> anomalies(@RequestParam(defaultValue = "50") int limit) {
        return metadata.anomalies(limit);
    }

    @PostMapping("/api/anomalies/detect")
    public List<Anomaly> detect() {
        return detector.detect(System.currentTimeMillis());
    }

    @GetMapping("/api/deploys")
    public List<Deploy> deploys(@RequestParam(required = false) String service) {
        return metadata.deploys(service);
    }

    /** Per-endpoint latency and errors of two versions of a service; defaults to its last two deploys. */
    @GetMapping("/api/deploys/compare")
    public DeployComparison compareDeploys(@RequestParam String service,
                                           @RequestParam(required = false) String baseline,
                                           @RequestParam(required = false) String candidate,
                                           @RequestParam(defaultValue = "180") int minutes) {
        List<Deploy> d = metadata.deploys(service);
        if (candidate == null) {
            if (d.isEmpty()) throw new IllegalArgumentException("no deploys recorded for " + service);
            candidate = d.get(d.size() - 1).version();
        }
        if (baseline == null) {
            if (d.size() < 2) throw new IllegalArgumentException(service + " has only one recorded version");
            baseline = d.get(d.size() - 2).version();
        }
        long[] w = window(minutes);
        Map<String, Red> a = metrics.versionStats(service, baseline, w[0], w[1]);
        Map<String, Red> b = metrics.versionStats(service, candidate, w[0], w[1]);
        List<EndpointDiff> diffs = new ArrayList<>();
        for (String ep : new TreeSet<>(a.keySet())) {
            Red x = a.get(ep), y = b.get(ep);
            if (y == null) continue;
            diffs.add(new EndpointDiff(ep, x, y, x.p95Ms() == 0 ? 0 : (y.p95Ms() / x.p95Ms() - 1) * 100,
                    y.errorRate() - x.errorRate()));
        }
        return new DeployComparison(service, baseline, candidate, diffs);
    }

    /** Generates {@code minutes} of traffic ending now (with the demo incident) in-process, then runs detection. */
    @PostMapping("/api/simulate")
    public Map<String, Object> simulate(@RequestParam(defaultValue = "30") int minutes,
                                        @RequestParam(defaultValue = "20") double rps,
                                        @RequestParam(defaultValue = "3") int incidentMinutes,
                                        @RequestParam(defaultValue = "42") long seed) {
        if (minutes < 1 || minutes > 180 || rps <= 0 || rps > 500) {
            throw new IllegalArgumentException("minutes must be 1..180 and rps 0..500");
        }
        long now = System.currentTimeMillis();
        var gen = new TraceGenerator(new Random(seed), incidentMinutes > 0 ? TraceGenerator.incident(now, incidentMinutes) : List.of());
        long[] spanCount = {0};
        long traces = gen.generate(now - minutes * 60_000L, now, rps, t -> spanCount[0] += ingest.ingest(t));
        return Map.of("traces", traces, "spans", spanCount[0], "anomalies", detector.detect(now));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    /** [from, to) aligned so the bucket in progress is included. */
    private long[] window(int minutes) {
        long b = metrics.bucketMillis();
        long to = Math.floorDiv(System.currentTimeMillis(), b) * b + b;
        return new long[]{to - minutes * 60_000L, to};
    }
}
