package dev.apm.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apm.anomaly.AnomalyDetector;
import dev.apm.ingest.IngestService;
import dev.apm.ingest.Otlp;
import dev.apm.ingest.TraceAssembler;
import dev.apm.metrics.MetricsAggregator;
import dev.apm.model.Records.Anomaly;
import dev.apm.model.Span;
import dev.apm.sim.TraceGenerator;
import dev.apm.store.InMemoryMetadataStore;
import dev.apm.store.InMemorySpanStore;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * In-process benchmark: OTLP JSON parse + ingest throughput, then query latency percentiles, against the in-memory
 * stores. Args: [traces=100000] [threads=cores] [queries=2000]. It does not measure HTTP, Redis or PostgreSQL.
 */
public final class IngestBenchmark {
    private static final int SPANS_PER_REQUEST = 300;

    public static void main(String[] args) throws Exception {
        int traces = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        int queries = args.length > 2 ? Integer.parseInt(args[2]) : 2_000;
        ObjectMapper json = new ObjectMapper();
        long now = System.currentTimeMillis();

        System.out.printf("apm-trace-explorer ingest benchmark: %d traces, %d threads, %d queries/type, JDK %s, %d cores%n",
                traces, threads, queries, System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());

        run(json, now, Math.min(traces, 10_000), threads, 100, false); // JIT warm-up on throwaway state
        run(json, now, traces, threads, queries, true);
    }

    private static void run(ObjectMapper json, long now, int traces, int threads, int queries, boolean report) throws Exception {
        var metrics = new MetricsAggregator(60_000, 180);
        var metadata = new InMemoryMetadataStore();
        var store = new InMemorySpanStore(traces + 1);
        var ingest = new IngestService(store, metadata, metrics);
        var detector = new AnomalyDetector(metrics, metadata, 2, 15, 30, 1.5, 20, 0.05);

        // Pre-encode OTLP JSON requests so the timed part is parse + ingest only.
        int minutes = 30;
        double rps = traces / (minutes * 60.0);
        var gen = new TraceGenerator(new Random(7), TraceGenerator.incident(now, 3));
        List<byte[]> requests = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        List<Span> pending = new ArrayList<>();
        long[] spanCount = {0};
        gen.generate(now - minutes * 60_000L, now, rps, t -> {
            pending.addAll(t);
            spanCount[0] += t.size();
            if (traceIds.size() < 10_000) traceIds.add(t.get(0).traceId());
            if (pending.size() >= SPANS_PER_REQUEST) {
                requests.add(encode(json, pending));
                pending.clear();
            }
        });
        if (!pending.isEmpty()) requests.add(encode(json, pending));
        long bytes = requests.stream().mapToLong(r -> r.length).sum();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (byte[] r : requests) futures.add(pool.submit(() -> {
            try {
                ingest.ingest(Otlp.decode(json.readTree(r)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
        for (Future<?> f : futures) f.get();
        double secs = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();

        Random rnd = new Random(1);
        List<String> services = TraceGenerator.SERVICES;
        Map<String, IntConsumer> qs = new LinkedHashMap<>();
        long from15 = now - 15 * 60_000L, to = now + 60_000;
        qs.put("endpoints (all, 15m)", i -> metrics.endpoints(null, from15, to));
        qs.put("service map (15m)", i -> {
            metrics.services(from15, to);
            metrics.edges(from15, to);
        });
        qs.put("heatmap (1 service, 30m)", i -> metrics.heatmap(services.get(rnd.nextInt(services.size())), null, now - 30 * 60_000L, to));
        qs.put("get trace + assemble", i -> {
            String id = traceIds.get(rnd.nextInt(traceIds.size()));
            TraceAssembler.assemble(id, store.trace(id));
        });

        if (!report) {
            qs.values().forEach(q -> { for (int i = 0; i < queries; i++) q.accept(i); });
            return;
        }
        System.out.printf("%nINGEST  %,d spans in %,d requests (%.1f MB OTLP JSON) in %.2f s%n",
                spanCount[0], requests.size(), bytes / 1e6, secs);
        System.out.printf("        %,.0f spans/s   %,.0f traces/s   %.1f MB/s%n%n",
                spanCount[0] / secs, traces / secs, bytes / 1e6 / secs);
        System.out.printf("%-26s %10s %10s %10s%n", "QUERY", "p50 ms", "p99 ms", "max ms");
        for (var e : qs.entrySet()) {
            Histogram h = new Histogram(3);
            for (int i = 0; i < queries; i++) {
                long q0 = System.nanoTime();
                e.getValue().accept(i);
                h.recordValue((System.nanoTime() - q0) / 1_000);
            }
            System.out.printf("%-26s %10.3f %10.3f %10.3f%n", e.getKey(),
                    h.getValueAtPercentile(50) / 1000.0, h.getValueAtPercentile(99) / 1000.0, h.getMaxValue() / 1000.0);
        }

        long d0 = System.nanoTime();
        List<Anomaly> found = detector.detect(now);
        System.out.printf("%nANOMALY detection pass: %.1f ms, %d findings (incident: payment-service 2.4.0 6x slower, "
                + "inventory-service 25%% errors)%n", (System.nanoTime() - d0) / 1e6, found.size());
        found.stream().filter(a -> a.service().equals("payment-service") || a.service().equals("inventory-service"))
                .forEach(a -> System.out.printf("  %-8s %-18s %-16s p95 %6.1f -> %6.1f ms  err %5.1f%% -> %5.1f%%  e.g. trace %s%n",
                        a.kind(), a.service(), a.endpoint(), a.baselineP95Ms(), a.currentP95Ms(),
                        a.baselineErrorRate() * 100, a.currentErrorRate() * 100,
                        a.exampleTraceIds().isEmpty() ? "-" : a.exampleTraceIds().get(0)));
    }

    private static byte[] encode(ObjectMapper json, List<Span> spans) {
        try {
            return json.writeValueAsBytes(Otlp.encode(spans));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
