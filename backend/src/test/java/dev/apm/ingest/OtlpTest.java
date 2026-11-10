package dev.apm.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apm.model.Span;
import dev.apm.sim.TraceGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void encodeDecodeRoundTrip() throws Exception {
        List<Span> spans = new TraceGenerator(new Random(1), List.of()).trace(1_700_000_000_000L);
        String wire = json.writeValueAsString(Otlp.encode(spans));
        List<Span> back = Otlp.decode(json.readTree(wire));
        assertThat(back).containsExactlyInAnyOrderElementsOf(spans);
    }

    @Test
    void decodesCollectorStyleJson() throws Exception {
        String body = """
                {"resourceSpans":[{"resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"cart"}},
                    {"key":"service.version","value":{"stringValue":"9.1"}}]},
                  "scopeSpans":[{"spans":[{
                    "traceId":"5b8efff798038103d269b633813fc60c","spanId":"eee19b7ec3c1b174",
                    "parentSpanId":"eee19b7ec3c1b173","name":"AddItem","kind":"SPAN_KIND_SERVER",
                    "startTimeUnixNano":1544712660000000000,"endTimeUnixNano":"1544712661000000000",
                    "attributes":[{"key":"http.status_code","value":{"intValue":"503"}}],
                    "status":{"code":"STATUS_CODE_ERROR"}}]}]}]}""";
        Span s = Otlp.decode(json.readTree(body)).get(0);
        assertThat(s.service()).isEqualTo("cart");
        assertThat(s.version()).isEqualTo("9.1");
        assertThat(s.kind()).isEqualTo("SERVER");
        assertThat(s.durationMicros()).isEqualTo(1_000_000);
        assertThat(s.error()).isTrue();
        assertThat(s.attributes()).containsEntry("http.status_code", "503");
        assertThat(s.root()).isFalse();
    }

    @Test
    void missingServiceNameAndIdsAreHandled() throws Exception {
        String ok = """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"ab","spanId":"cd","kind":2}]}]}]}""";
        Span s = Otlp.decode(json.readTree(ok)).get(0);
        assertThat(s.service()).isEqualTo("unknown_service");
        assertThat(s.root()).isTrue();

        String bad = """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{"spanId":"cd"}]}]}]}""";
        assertThatThrownBy(() -> Otlp.decode(json.readTree(bad))).isInstanceOf(IllegalArgumentException.class);
    }
}
