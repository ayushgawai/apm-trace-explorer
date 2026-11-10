package dev.apm.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apm.model.Span;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTLP/HTTP JSON trace codec (ExportTraceServiceRequest). Only the fields this tool uses are read.
 * Ids are expected hex-encoded, which is what the OTLP JSON spec mandates.
 */
public final class Otlp {
    private static final String[] KINDS = {"UNSPECIFIED", "INTERNAL", "SERVER", "CLIENT", "PRODUCER", "CONSUMER"};

    private Otlp() {}

    public static List<Span> decode(JsonNode request) {
        List<Span> out = new ArrayList<>();
        for (JsonNode rs : request.path("resourceSpans")) {
            Map<String, String> resource = attributes(rs.path("resource").path("attributes"));
            String service = resource.getOrDefault("service.name", "unknown_service");
            String version = resource.getOrDefault("service.version", "unknown");
            for (JsonNode ss : rs.path("scopeSpans")) {
                for (JsonNode s : ss.path("spans")) {
                    long start = s.path("startTimeUnixNano").asLong();
                    long end = s.path("endTimeUnixNano").asLong();
                    String traceId = s.path("traceId").asText("");
                    String spanId = s.path("spanId").asText("");
                    if (traceId.isEmpty() || spanId.isEmpty()) {
                        throw new IllegalArgumentException("span without traceId/spanId");
                    }
                    out.add(new Span(traceId, spanId, s.path("parentSpanId").asText(""), service, version,
                            s.path("name").asText(""), kind(s.path("kind")), start, Math.max(0, end - start),
                            isError(s.path("status").path("code")), attributes(s.path("attributes"))));
                }
            }
        }
        return out;
    }

    /** Encodes spans as an OTLP JSON request, one resourceSpans entry per (service, version). */
    public static Map<String, Object> encode(List<Span> spans) {
        Map<String, List<Map<String, Object>>> byResource = new LinkedHashMap<>();
        for (Span s : spans) {
            Map<String, Object> js = new HashMap<>();
            js.put("traceId", s.traceId());
            js.put("spanId", s.spanId());
            if (!s.root()) js.put("parentSpanId", s.parentSpanId());
            js.put("name", s.name());
            js.put("kind", kindNumber(s.kind()));
            js.put("startTimeUnixNano", Long.toString(s.startNanos()));
            js.put("endTimeUnixNano", Long.toString(s.startNanos() + s.durationNanos()));
            js.put("status", Map.of("code", s.error() ? 2 : 1));
            if (!s.attributes().isEmpty()) js.put("attributes", kv(s.attributes()));
            byResource.computeIfAbsent(s.service() + "\u0000" + s.version(), k -> new ArrayList<>()).add(js);
        }
        List<Object> resourceSpans = new ArrayList<>();
        byResource.forEach((key, list) -> {
            String[] sv = key.split("\u0000", 2);
            resourceSpans.add(Map.of(
                    "resource", Map.of("attributes", kv(Map.of("service.name", sv[0], "service.version", sv[1]))),
                    "scopeSpans", List.of(Map.of("scope", Map.of("name", "apm-sim"), "spans", list))));
        });
        return Map.of("resourceSpans", resourceSpans);
    }

    private static Map<String, String> attributes(JsonNode attrs) {
        if (!attrs.isArray() || attrs.isEmpty()) return Map.of();
        Map<String, String> m = new HashMap<>();
        for (JsonNode a : attrs) {
            JsonNode v = a.path("value");
            // AnyValue: exactly one of stringValue/intValue/boolValue/doubleValue is set
            String text = v.has("stringValue") ? v.get("stringValue").asText()
                    : v.has("intValue") ? v.get("intValue").asText()
                    : v.has("boolValue") ? v.get("boolValue").asText()
                    : v.has("doubleValue") ? v.get("doubleValue").asText()
                    : v.toString();
            m.put(a.path("key").asText(), text);
        }
        return m;
    }

    private static List<Map<String, Object>> kv(Map<String, String> m) {
        List<Map<String, Object>> out = new ArrayList<>(m.size());
        m.forEach((k, v) -> out.add(Map.of("key", k, "value", Map.of("stringValue", v))));
        return out;
    }

    private static String kind(JsonNode k) {
        if (k.isNumber()) {
            int i = k.asInt();
            return i >= 0 && i < KINDS.length ? KINDS[i] : "UNSPECIFIED";
        }
        String s = k.asText("");
        return s.startsWith("SPAN_KIND_") ? s.substring(10) : s.isEmpty() ? "UNSPECIFIED" : s;
    }

    private static int kindNumber(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
        return 0;
    }

    private static boolean isError(JsonNode code) {
        return code.isNumber() ? code.asInt() == 2 : "STATUS_CODE_ERROR".equals(code.asText());
    }
}
