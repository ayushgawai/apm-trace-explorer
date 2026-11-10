package dev.apm.anomaly;

import dev.apm.metrics.MetricsAggregator;
import dev.apm.metrics.MetricsAggregator.Key;
import dev.apm.metrics.MetricsAggregator.Window;
import dev.apm.model.Records.Anomaly;
import dev.apm.store.MetadataStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compares each endpoint's most recent buckets against a rolling baseline: the median of the per-bucket values over
 * the buckets just before them. Latency: current p95 > ratio * baseline p95 and at least minDeltaMs slower.
 * Errors: error rate up by at least errorDelta and at least doubled. Each finding carries example trace ids from
 * the current window.
 */
@Component
public class AnomalyDetector {
    private final MetricsAggregator metrics;
    private final MetadataStore metadata;
    private final int currentBuckets;
    private final int baselineBuckets;
    private final long minSamples;
    private final double latencyRatio;
    private final double minDeltaMs;
    private final double errorDelta;
    private final Map<String, Long> lastFlagged = new ConcurrentHashMap<>();

    public AnomalyDetector(MetricsAggregator metrics, MetadataStore metadata,
                           @Value("${apm.anomaly.current-buckets:2}") int currentBuckets,
                           @Value("${apm.anomaly.baseline-buckets:15}") int baselineBuckets,
                           @Value("${apm.anomaly.min-samples:30}") long minSamples,
                           @Value("${apm.anomaly.latency-ratio:1.5}") double latencyRatio,
                           @Value("${apm.anomaly.min-delta-ms:20}") double minDeltaMs,
                           @Value("${apm.anomaly.error-delta:0.05}") double errorDelta) {
        this.metrics = metrics;
        this.metadata = metadata;
        this.currentBuckets = currentBuckets;
        this.baselineBuckets = baselineBuckets;
        this.minSamples = minSamples;
        this.latencyRatio = latencyRatio;
        this.minDeltaMs = minDeltaMs;
        this.errorDelta = errorDelta;
    }

    @Scheduled(fixedDelayString = "${apm.anomaly.interval-ms:15000}", initialDelayString = "${apm.anomaly.interval-ms:15000}")
    public void scheduled() {
        detect(System.currentTimeMillis());
    }

    /** Runs detection as of {@code nowMillis}; returns and stores only newly flagged anomalies. */
    public List<Anomaly> detect(long nowMillis) {
        long b = metrics.bucketMillis();
        long curEnd = Math.floorDiv(nowMillis, b) * b + b; // include the bucket in progress
        long curStart = curEnd - currentBuckets * b;
        long baseStart = curStart - baselineBuckets * b;
        long cooldown = baselineBuckets * b;

        List<Anomaly> found = new ArrayList<>();
        for (Key k : metrics.endpointKeys()) {
            Window cur = metrics.window(k, curStart, curEnd);
            if (cur.count() < minSamples) continue;

            // Baseline = median of per-bucket p95 / error rate, so an incident that began a few buckets
            // before the current window does not drag the baseline up with it.
            List<Double> p95s = new ArrayList<>(), errs = new ArrayList<>();
            long baseCount = 0;
            for (int i = 0; i < baselineBuckets; i++) {
                Window w = metrics.window(k, baseStart + i * b, baseStart + (i + 1) * b);
                if (w.count() == 0) continue;
                baseCount += w.count();
                p95s.add(w.percentileMs(95));
                errs.add(w.errorRate());
            }
            if (baseCount < minSamples || p95s.size() < baselineBuckets / 2) continue;

            double bp95 = median(p95s), cp95 = cur.percentileMs(95);
            double be = median(errs), ce = cur.errorRate();
            if (cp95 > bp95 * latencyRatio && cp95 - bp95 > minDeltaMs) {
                add(found, nowMillis, cooldown, new Anomaly(k.a(), k.b(), "latency", nowMillis, bp95, cp95,
                        be, ce, cur.slowTraceIds()));
            }
            if (ce - be > errorDelta && ce > 2 * be) {
                add(found, nowMillis, cooldown, new Anomaly(k.a(), k.b(), "errors", nowMillis, bp95, cp95,
                        be, ce, cur.errorTraceIds()));
            }
        }
        return found;
    }

    static double median(List<Double> xs) {
        List<Double> s = xs.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private void add(List<Anomaly> found, long now, long cooldown, Anomaly a) {
        String id = a.service() + "|" + a.endpoint() + "|" + a.kind();
        Long last = lastFlagged.get(id);
        if (last != null && now - last < cooldown) return; // one finding per incident, not one per run
        lastFlagged.put(id, now);
        metadata.saveAnomaly(a);
        found.add(a);
    }
}
