package dev.apm.store;

import dev.apm.model.Span;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "apm.stores", havingValue = "memory", matchIfMissing = true)
public class InMemorySpanStore implements SpanStore {
    private final int maxTraces;
    private final Map<String, List<Span>> traces;
    private final ArrayList<String> recent = new ArrayList<>();

    public InMemorySpanStore() {
        this(200_000);
    }

    public InMemorySpanStore(int maxTraces) {
        this.maxTraces = maxTraces;
        // insertion-ordered map evicts the oldest trace once full
        this.traces = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Span>> e) {
                return size() > InMemorySpanStore.this.maxTraces;
            }
        };
    }

    // ponytail: one lock for the whole store; shard by traceId hash if ingest threads contend
    @Override
    public synchronized void append(List<Span> spans) {
        for (Span s : spans) {
            traces.computeIfAbsent(s.traceId(), k -> new ArrayList<>(8)).add(s);
            if (s.root()) recent.add(s.traceId());
        }
        if (recent.size() > 20_000) recent.subList(0, 10_000).clear();
    }

    @Override
    public synchronized List<Span> trace(String traceId) {
        List<Span> t = traces.get(traceId);
        return t == null ? List.of() : List.copyOf(t);
    }

    @Override
    public synchronized List<String> recentTraceIds(int limit) {
        List<String> out = new ArrayList<>(recent.subList(Math.max(0, recent.size() - limit), recent.size()));
        Collections.reverse(out);
        return out;
    }
}
