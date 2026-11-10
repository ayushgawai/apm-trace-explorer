package dev.apm.store;

import dev.apm.model.Span;

import java.util.List;

/** Raw span storage keyed by trace. Redis in docker-compose, in-memory for tests and local runs. */
public interface SpanStore {
    void append(List<Span> spans);

    List<Span> trace(String traceId);

    /** Most recent root-span trace ids, newest first. */
    List<String> recentTraceIds(int limit);
}
