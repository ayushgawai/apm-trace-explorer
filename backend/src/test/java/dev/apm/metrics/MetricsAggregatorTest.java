package dev.apm.metrics;

import dev.apm.model.Records.Heatmap;
import dev.apm.model.Records.Red;
import dev.apm.model.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsAggregatorTest {
    static final long T0 = 1_700_000_000_000L - (1_700_000_000_000L % 60_000); // bucket aligned

    static Span req(String id, long startMs, double durMs, boolean error, String version) {
        return new Span(id, "s" + id, "", "checkout", version, "PlaceOrder", "SERVER",
                startMs * 1_000_000, (long) (durMs * 1_000_000), error, Map.of());
    }

    @Test
    void percentilesRateAndErrorsOverWindow() {
        var m = new MetricsAggregator(60_000, 180);
        for (int i = 1; i <= 1000; i++) {
            m.recordEntry(req("t" + i, T0 + i * 50L, i, i % 20 == 0, "1")); // 1..1000 ms over 50 s
        }
        Red r = m.endpoints("checkout", T0, T0 + 60_000).get(0);

        assertThat(r.count()).isEqualTo(1000);
        assertThat(r.errorRate()).isCloseTo(0.05, within(1e-9));
        assertThat(r.ratePerSec()).isCloseTo(1000 / 60.0, within(1e-6));
        // 2 significant digits: within 1% of exact
        assertThat(r.p50Ms()).isCloseTo(500, within(5.0));
        assertThat(r.p95Ms()).isCloseTo(950, within(10.0));
        assertThat(r.p99Ms()).isCloseTo(990, within(10.0));
        assertThat(m.endpoints("checkout", T0 + 60_000, T0 + 120_000)).isEmpty();
    }

    @Test
    void exemplarsAreTheSlowestTraces() {
        var m = new MetricsAggregator(60_000, 180);
        for (int i = 1; i <= 200; i++) m.recordEntry(req("t" + i, T0 + i, i, false, "1"));
        var w = m.window(new MetricsAggregator.Key('E', "checkout", "PlaceOrder", ""), T0, T0 + 60_000);
        assertThat(w.slowTraceIds()).containsExactly("t200", "t199", "t198", "t197", "t196");
    }

    @Test
    void heatmapBinsByLatencyAndBucket() {
        var m = new MetricsAggregator(60_000, 180);
        m.recordEntry(req("a", T0 + 1, 3, false, "1"));          // bucket 0, bin <=5ms
        m.recordEntry(req("b", T0 + 60_001, 150, false, "1"));   // bucket 1, bin <=200ms
        m.recordEntry(req("c", T0 + 60_002, 150, false, "1"));
        Heatmap h = m.heatmap("checkout", null, T0, T0 + 120_000);

        assertThat(h.bucketStartMillis()).containsExactly(T0, T0 + 60_000);
        assertThat(h.counts().get(0)[MetricsAggregator.heatBin(3)]).isEqualTo(1);
        assertThat(h.counts().get(1)[MetricsAggregator.heatBin(150)]).isEqualTo(2);
        assertThat(MetricsAggregator.heatBin(1e9)).isEqualTo(MetricsAggregator.HEATMAP_BINS_MS.length - 1);
    }

    @Test
    void versionsAndEdgesAreTrackedSeparately() {
        var m = new MetricsAggregator(60_000, 180);
        for (int i = 0; i < 100; i++) {
            m.recordEntry(req("v1-" + i, T0 + i, 10, false, "1.0"));
            m.recordEntry(req("v2-" + i, T0 + i, 40, i % 10 == 0, "2.0"));
        }
        m.recordEdge("api-gateway", req("e", T0, 20, true, "1.0"));

        assertThat(m.versionStats("checkout", "1.0", T0, T0 + 60_000).get("PlaceOrder").p95Ms()).isCloseTo(10, within(0.2));
        assertThat(m.versionStats("checkout", "2.0", T0, T0 + 60_000).get("PlaceOrder").errorRate()).isCloseTo(0.1, within(1e-9));
        assertThat(m.edges(T0, T0 + 60_000)).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo("api-gateway");
            assertThat(e.to()).isEqualTo("checkout");
            assertThat(e.errors()).isEqualTo(1);
        });
        assertThat(m.services(T0, T0 + 60_000)).extracting(Red::count).containsExactly(200L);
    }

    @Test
    void oldBucketsAreDroppedAfterRetention() {
        var m = new MetricsAggregator(60_000, 5);
        m.recordEntry(req("old", T0, 5, false, "1"));
        m.recordEntry(req("new", T0 + 10 * 60_000, 5, false, "1"));
        assertThat(m.endpoints(null, T0, T0 + 60_000)).isEmpty();
        assertThat(m.endpoints(null, T0, T0 + 11 * 60_000)).extracting(Red::count).containsExactly(1L);
    }

    @Test
    void emptyServiceListWhenNothingRecorded() {
        assertThat(new MetricsAggregator(60_000, 10).services(0, 60_000)).isEqualTo(List.of());
    }
}
