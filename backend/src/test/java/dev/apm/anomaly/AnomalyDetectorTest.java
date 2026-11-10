package dev.apm.anomaly;

import dev.apm.ingest.IngestService;
import dev.apm.ingest.TraceAssembler;
import dev.apm.metrics.MetricsAggregator;
import dev.apm.model.Records.Anomaly;
import dev.apm.model.Records.Deploy;
import dev.apm.model.Records.TraceView;
import dev.apm.sim.TraceGenerator;
import dev.apm.store.InMemoryMetadataStore;
import dev.apm.store.InMemorySpanStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {
    static final long NOW = 1_700_000_000_000L;

    final MetricsAggregator metrics = new MetricsAggregator(60_000, 180);
    final InMemoryMetadataStore metadata = new InMemoryMetadataStore();
    final InMemorySpanStore store = new InMemorySpanStore();
    final IngestService ingest = new IngestService(store, metadata, metrics);
    final AnomalyDetector detector = new AnomalyDetector(metrics, metadata, 2, 15, 30, 1.5, 20, 0.05);

    void simulate(List<TraceGenerator.Fault> faults) {
        new TraceGenerator(new Random(3), faults).generate(NOW - 30 * 60_000L, NOW, 20, ingest::ingest);
    }

    @Test
    void steadyTrafficRaisesNothing() {
        simulate(List.of());
        assertThat(detector.detect(NOW)).isEmpty();
    }

    @Test
    void flagsSlowDeployAndErrorBurstWithExampleTraces() {
        simulate(TraceGenerator.incident(NOW, 3));
        List<Anomaly> found = detector.detect(NOW);

        Anomaly slow = found.stream()
                .filter(a -> a.kind().equals("latency") && a.service().equals("payment-service")).findFirst().orElseThrow();
        assertThat(slow.currentP95Ms()).isGreaterThan(slow.baselineP95Ms() * 3);
        assertThat(slow.exampleTraceIds()).isNotEmpty();
        TraceView example = TraceAssembler.assemble(slow.exampleTraceIds().get(0), store.trace(slow.exampleTraceIds().get(0)));
        assertThat(example.services()).contains("payment-service", "checkout-service");

        // the slowdown propagates up the call chain to the checkout endpoint
        assertThat(found).anyMatch(a -> a.kind().equals("latency") && a.service().equals("checkout-service"));

        Anomaly errors = found.stream()
                .filter(a -> a.kind().equals("errors") && a.service().equals("inventory-service")).findFirst().orElseThrow();
        assertThat(errors.currentErrorRate()).isGreaterThan(0.1);
        assertThat(TraceAssembler.assemble("x", store.trace(errors.exampleTraceIds().get(0))).errorCount()).isPositive();

        // nothing outside the incident's blast radius is flagged
        assertThat(found).allMatch(a -> !a.service().equals("search-service") && !a.service().equals("shipping-service"));

        // the same incident is not re-reported on the next run
        assertThat(detector.detect(NOW + 15_000)).isEmpty();
        assertThat(metadata.anomalies(100)).hasSameSizeAs(found);
    }

    @Test
    void recordsDeployAndComparesVersions() {
        simulate(TraceGenerator.incident(NOW, 3));
        assertThat(metadata.deploys("payment-service")).extracting(Deploy::version).containsExactly("2.3.0", "2.4.0");

        var before = metrics.versionStats("payment-service", "2.3.0", NOW - 60 * 60_000L, NOW + 60_000).get("Charge");
        var after = metrics.versionStats("payment-service", "2.4.0", NOW - 60 * 60_000L, NOW + 60_000).get("Charge");
        assertThat(after.p95Ms()).isGreaterThan(before.p95Ms() * 4);
    }

    @Test
    void serviceMapHasTheTenServiceTopology() {
        simulate(List.of());
        var edges = metrics.edges(NOW - 30 * 60_000L, NOW + 60_000);
        assertThat(metrics.services(NOW - 30 * 60_000L, NOW + 60_000)).hasSize(TraceGenerator.SERVICES.size());
        assertThat(edges).anyMatch(e -> e.from().equals("checkout-service") && e.to().equals("payment-service"));
        assertThat(edges).anyMatch(e -> e.from().equals("web-frontend") && e.to().equals("api-gateway"));
        assertThat(edges).noneMatch(e -> e.from().equals(e.to()));
    }
}
