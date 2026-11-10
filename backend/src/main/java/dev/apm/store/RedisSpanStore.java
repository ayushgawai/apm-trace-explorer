package dev.apm.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apm.model.Span;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Each trace is a Redis list of span JSON at trace:{id} with a TTL; traces:recent indexes root spans. */
@Component
@ConditionalOnProperty(name = "apm.stores", havingValue = "external")
public class RedisSpanStore implements SpanStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long ttlSeconds;

    public RedisSpanStore(StringRedisTemplate redis, ObjectMapper json,
                          @Value("${apm.trace-ttl-seconds:7200}") long ttlSeconds) {
        this.redis = redis;
        this.json = json;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void append(List<Span> spans) {
        Map<String, List<String>> byTrace = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (Span s : spans) {
            byTrace.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(write(s));
            if (s.root()) roots.add(s.traceId());
        }
        redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = (StringRedisConnection) conn;
            byTrace.forEach((id, list) -> {
                c.rPush("trace:" + id, list.toArray(String[]::new));
                c.expire("trace:" + id, ttlSeconds);
            });
            if (!roots.isEmpty()) {
                c.lPush("traces:recent", roots.toArray(String[]::new));
                c.lTrim("traces:recent", 0, 9_999);
            }
            return null;
        });
    }

    @Override
    public List<Span> trace(String traceId) {
        List<String> raw = redis.opsForList().range("trace:" + traceId, 0, -1);
        if (raw == null) return List.of();
        List<Span> out = new ArrayList<>(raw.size());
        for (String r : raw) {
            try {
                out.add(json.readValue(r, Span.class));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    @Override
    public List<String> recentTraceIds(int limit) {
        List<String> ids = redis.opsForList().range("traces:recent", 0, limit - 1);
        return ids == null ? List.of() : ids;
    }

    private String write(Span s) {
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
