package dev.apm.ingest;

import dev.apm.model.Records.TraceRow;
import dev.apm.model.Records.TraceView;
import dev.apm.model.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAssemblerTest {
    static Span span(String id, String parent, String service, long startMs, long durMs, boolean error) {
        return new Span("t1", id, parent, service, "1", "op-" + id, "SERVER",
                startMs * 1_000_000, durMs * 1_000_000, error, Map.of());
    }

    @Test
    void buildsDepthFirstTreeOrderedByStart() {
        // arrival order is scrambled, as with real exporters
        List<Span> spans = List.of(
                span("c2", "b", "db", 30, 5, false),
                span("b", "a", "api", 10, 40, false),
                span("a", "", "web", 0, 60, false),
                span("c1", "b", "auth", 12, 5, true),
                span("c1", "b", "auth", 12, 5, true)); // duplicate from a retried export

        TraceView v = TraceAssembler.assemble("t1", spans);

        assertThat(v.rows()).extracting(r -> r.span().spanId()).containsExactly("a", "b", "c1", "c2");
        assertThat(v.rows()).extracting(TraceRow::depth).containsExactly(0, 1, 2, 2);
        assertThat(v.rows().get(1).offsetMicros()).isEqualTo(10_000);
        assertThat(v.durationMicros()).isEqualTo(60_000);
        assertThat(v.errorCount()).isEqualTo(1);
        assertThat(v.rootService()).isEqualTo("web");
        assertThat(v.services()).containsExactly("api", "auth", "db", "web");
    }

    @Test
    void orphanSpansBecomeRootsAndCyclesTerminate() {
        TraceView orphan = TraceAssembler.assemble("t1", List.of(
                span("a", "", "web", 0, 10, false),
                span("x", "missing", "worker", 5, 1, false)));
        assertThat(orphan.rows()).extracting(TraceRow::depth).containsExactly(0, 0);

        TraceView cycle = TraceAssembler.assemble("t1", List.of(
                span("p", "q", "a", 0, 10, false),
                span("q", "p", "b", 1, 5, false)));
        assertThat(cycle.rows()).hasSize(2);

        assertThat(TraceAssembler.assemble("t1", List.of())).isNull();
    }
}
