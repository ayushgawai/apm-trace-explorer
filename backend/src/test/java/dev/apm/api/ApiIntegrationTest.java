package dev.apm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apm.ingest.Otlp;
import dev.apm.model.Span;
import dev.apm.sim.TraceGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the app with the in-memory stores (default profile) and exercises the HTTP API. */
@SpringBootTest(properties = "apm.anomaly.interval-ms=3600000")
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void ingestsOtlpAndServesTheTrace() throws Exception {
        // an hour old, so it never falls inside simulateThenTriage's windows whichever test runs first
        List<Span> trace = new TraceGenerator(new Random(9), List.of()).trace(System.currentTimeMillis() - 3_600_000);
        mvc.perform(post("/v1/traces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Otlp.encode(trace))))
                .andExpect(status().isOk());

        String id = trace.get(0).traceId();
        mvc.perform(get("/api/traces/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spanCount").value(trace.size()))
                .andExpect(jsonPath("$.rootService").value("web-frontend"))
                .andExpect(jsonPath("$.rows[0].depth").value(0))
                .andExpect(jsonPath("$.rows[1].depth").value(1));
        assertThat(getJson("/api/traces?limit=5").findValuesAsText("traceId")).contains(id);
        mvc.perform(get("/api/traces/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedOtlp() throws Exception {
        mvc.perform(post("/v1/traces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":[{\"name\":\"x\"}]}]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulateThenTriage() throws Exception {
        mvc.perform(post("/api/simulate?minutes=20&rps=15&incidentMinutes=3")).andExpect(status().isOk());

        JsonNode anomalies = getJson("/api/anomalies");
        assertThat(anomalies.findValuesAsText("service")).contains("payment-service", "inventory-service");

        JsonNode slow = getJson("/api/endpoints?sort=p95&limit=3");
        assertThat(slow).hasSize(3);
        assertThat(slow.get(0).get("p95Ms").asDouble()).isGreaterThanOrEqualTo(slow.get(1).get("p95Ms").asDouble());

        JsonNode cmp = getJson("/api/deploys/compare?service=payment-service");
        assertThat(cmp.get("baselineVersion").asText()).isEqualTo("2.3.0");
        assertThat(cmp.get("candidateVersion").asText()).isEqualTo("2.4.0");
        assertThat(cmp.get("endpoints").get(0).get("p95ChangePct").asDouble()).isGreaterThan(100);

        JsonNode map = getJson("/api/service-map");
        assertThat(map.get("nodes")).hasSize(10);
        assertThat(map.get("edges").size()).isGreaterThanOrEqualTo(9);

        JsonNode heat = getJson("/api/heatmap?service=payment-service&minutes=20");
        assertThat(heat.get("counts").size()).isEqualTo(heat.get("bucketStartMillis").size());

        mvc.perform(get("/api/deploys/compare?service=nope")).andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
