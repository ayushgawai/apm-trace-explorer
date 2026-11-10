package dev.apm.metrics;

import dev.apm.model.Records.Edge;
import dev.apm.model.Records.Heatmap;
import dev.apm.model.Records.Red;
import dev.apm.model.Span;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramIterationValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Time-bucketed HDR histograms (microseconds, 2 significant digits) keyed by endpoint, edge or endpoint+version.
 * Buckets use span start time (event time), so backfilled traffic lands where it belongs.
 */
@Component
public class MetricsAggregator {
    /** E = endpoint (service, name), L = edge (from, to), V = version (service, name, version). */
    public record Key(char kind, String a, String b, String c) {}

    public static final double[] HEATMAP_BINS_MS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 60000};
    private static final int EXEMPLARS = 5;

    private final long bucketMillis;
    private final int retentionBuckets;
    private final Map<Key, Series> series = new ConcurrentHashMap<>();

    public MetricsAggregator(@Value("${apm.bucket-millis:60000}") long bucketMillis,
                             @Value("${apm.retention-buckets:180}") int retentionBuckets) {
        this.bucketMillis = bucketMillis;
        this.retentionBuckets = retentionBuckets;
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    public void recordEntry(Span s) {
        long t = bucketOf(s);
        series.computeIfAbsent(new Key('E', s.service(), s.name(), ""), k -> new Series()).record(t, s);
        series.computeIfAbsent(new Key('V', s.service(), s.name(), s.version()), k -> new Series()).record(t, s);
    }

    public void recordEdge(String from, Span child) {
        series.computeIfAbsent(new Key('L', from, child.service(), ""), k -> new Series()).record(bucketOf(child), child);
    }

    public List<Key> endpointKeys() {
        return series.keySet().stream().filter(k -> k.kind() == 'E').toList();
    }

    public Window window(Key key, long fromMillis, long toMillis) {
        Window w = new Window();
        Series s = series.get(key);
        if (s != null) s.mergeInto(w, fromMillis, toMillis);
        return w;
    }

    public List<Red> endpoints(String service, long fromMillis, long toMillis) {
        List<Red> out = new ArrayList<>();
        for (Key k : endpointKeys()) {
            if (service != null && !service.equals(k.a())) continue;
            Window w = window(k, fromMillis, toMillis);
            if (w.count() > 0) out.add(w.red(k.a(), k.b(), toMillis - fromMillis));
        }
        out.sort(Comparator.comparing(Red::service).thenComparing(Red::endpoint));
        return out;
    }

    public List<Red> services(long fromMillis, long toMillis) {
        Map<String, Window> byService = new HashMap<>();
        for (Key k : endpointKeys()) {
            Series s = series.get(k);
            s.mergeInto(byService.computeIfAbsent(k.a(), x -> new Window()), fromMillis, toMillis);
        }
        List<Red> out = new ArrayList<>();
        byService.forEach((svc, w) -> {
            if (w.count() > 0) out.add(w.red(svc, null, toMillis - fromMillis));
        });
        out.sort(Comparator.comparing(Red::service));
        return out;
    }

    public List<Edge> edges(long fromMillis, long toMillis) {
        List<Edge> out = new ArrayList<>();
        series.forEach((k, s) -> {
            if (k.kind() != 'L') return;
            Window w = new Window();
            s.mergeInto(w, fromMillis, toMillis);
            if (w.count() > 0) out.add(new Edge(k.a(), k.b(), w.count(), w.errors, w.hist.getMean() / 1000.0));
        });
        return out;
    }

    /** Per-endpoint RED for one deployed version of a service. */
    public Map<String, Red> versionStats(String service, String version, long fromMillis, long toMillis) {
        Map<String, Red> out = new TreeMap<>();
        series.forEach((k, s) -> {
            if (k.kind() != 'V' || !k.a().equals(service) || !k.c().equals(version)) return;
            Window w = new Window();
            s.mergeInto(w, fromMillis, toMillis);
            if (w.count() > 0) out.put(k.b(), w.red(service, k.b(), toMillis - fromMillis));
        });
        return out;
    }

    public Heatmap heatmap(String service, String endpoint, long fromMillis, long toMillis) {
        Predicate<Key> match = k -> k.kind() == 'E'
                && (service == null || service.equals(k.a())) && (endpoint == null || endpoint.equals(k.b()));
        long first = Math.floorDiv(fromMillis, bucketMillis) * bucketMillis;
        int rows = (int) Math.max(1, (toMillis - first + bucketMillis - 1) / bucketMillis);
        List<long[]> counts = new ArrayList<>(rows);
        List<Long> starts = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            counts.add(new long[HEATMAP_BINS_MS.length]);
            starts.add(first + i * bucketMillis);
        }
        series.forEach((k, s) -> {
            if (match.test(k)) s.addHeat(counts, first, rows);
        });
        List<Double> bins = new ArrayList<>();
        for (double b : HEATMAP_BINS_MS) bins.add(b);
        return new Heatmap(starts, bins, counts);
    }

    private long bucketOf(Span s) {
        return Math.floorDiv(s.startNanos() / 1_000_000, bucketMillis) * bucketMillis;
    }

    static int heatBin(double ms) {
        for (int i = 0; i < HEATMAP_BINS_MS.length; i++) if (ms <= HEATMAP_BINS_MS[i]) return i;
        return HEATMAP_BINS_MS.length - 1;
    }

    /** Merged view over a range of buckets. */
    public static final class Window {
        final Histogram hist = new Histogram(2);
        long errors;
        final List<Exemplar> slowest = new ArrayList<>();
        final List<String> errorIds = new ArrayList<>();

        public long count() {
            return hist.getTotalCount();
        }

        public double errorRate() {
            return count() == 0 ? 0 : (double) errors / count();
        }

        public double percentileMs(double p) {
            return count() == 0 ? 0 : hist.getValueAtPercentile(p) / 1000.0;
        }

        /** Slowest traces first. */
        public List<String> slowTraceIds() {
            return slowest.stream().sorted(Comparator.comparingLong(Exemplar::micros).reversed())
                    .map(Exemplar::traceId).distinct().limit(EXEMPLARS).toList();
        }

        public List<String> errorTraceIds() {
            return errorIds.stream().limit(EXEMPLARS).toList();
        }

        Red red(String service, String endpoint, long windowMillis) {
            List<String> examples = errors > 0 && errorRate() > 0.05 ? errorTraceIds() : slowTraceIds();
            return new Red(service, endpoint, count(), count() / Math.max(1.0, windowMillis / 1000.0), errorRate(),
                    percentileMs(50), percentileMs(95), percentileMs(99), examples);
        }
    }

    record Exemplar(long micros, String traceId) {}

    private final class Series {
        private final NavigableMap<Long, Bucket> buckets = new TreeMap<>();

        synchronized void record(long bucketStart, Span s) {
            Bucket b = buckets.get(bucketStart);
            if (b == null) {
                b = new Bucket();
                buckets.put(bucketStart, b);
                buckets.headMap(buckets.lastKey() - retentionBuckets * bucketMillis).clear();
            }
            b.add(s);
        }

        /** A bucket belongs to the window when it starts in [fromMillis, toMillis). */
        synchronized void mergeInto(Window w, long fromMillis, long toMillis) {
            for (Bucket b : buckets.subMap(fromMillis, true, toMillis, false).values()) {
                w.hist.add(b.hist);
                w.errors += b.errors;
                for (int i = 0; i < b.slowCount; i++) w.slowest.add(new Exemplar(b.slowMicros[i], b.slowIds[i]));
                for (String id : b.errorIds) if (w.errorIds.size() < EXEMPLARS) w.errorIds.add(id);
            }
        }

        synchronized void addHeat(List<long[]> counts, long first, int rows) {
            for (var e : buckets.subMap(first, true, first + rows * bucketMillis, false).entrySet()) {
                long[] row = counts.get((int) ((e.getKey() - first) / bucketMillis));
                for (HistogramIterationValue v : e.getValue().hist.recordedValues()) {
                    row[heatBin(v.getValueIteratedTo() / 1000.0)] += v.getCountAtValueIteratedTo();
                }
            }
        }
    }

    private static final class Bucket {
        final Histogram hist = new Histogram(2);
        long errors;
        final long[] slowMicros = new long[EXEMPLARS];
        final String[] slowIds = new String[EXEMPLARS];
        int slowCount;
        final List<String> errorIds = new ArrayList<>(EXEMPLARS);

        void add(Span s) {
            long us = s.durationMicros();
            hist.recordValue(us);
            if (s.error()) {
                errors++;
                if (errorIds.size() < EXEMPLARS) errorIds.add(s.traceId());
            }
            // keep the EXEMPLARS slowest traces of this bucket
            if (slowCount < EXEMPLARS) {
                slowMicros[slowCount] = us;
                slowIds[slowCount++] = s.traceId();
            } else {
                int min = 0;
                for (int i = 1; i < EXEMPLARS; i++) if (slowMicros[i] < slowMicros[min]) min = i;
                if (us > slowMicros[min]) {
                    slowMicros[min] = us;
                    slowIds[min] = s.traceId();
                }
            }
        }
    }
}
