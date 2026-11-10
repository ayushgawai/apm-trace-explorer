package dev.apm.ingest;

import dev.apm.metrics.MetricsAggregator;
import dev.apm.model.Records.Deploy;
import dev.apm.model.Span;
import dev.apm.store.MetadataStore;
import dev.apm.store.SpanStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stores spans, updates RED histograms and service-map edges, and notes new service versions as deploys. */
@Service
public class IngestService {
    private static final int SPAN_SERVICE_CACHE = 500_000;

    private final SpanStore spans;
    private final MetadataStore metadata;
    private final MetricsAggregator metrics;
    private final Set<String> knownVersions = ConcurrentHashMap.newKeySet();
    // spanId -> service, so a child can find which service called it (edge detection)
    private final Map<String, String> spanService = new ConcurrentHashMap<>();

    public IngestService(SpanStore spans, MetadataStore metadata, MetricsAggregator metrics) {
        this.spans = spans;
        this.metadata = metadata;
        this.metrics = metrics;
    }

    public int ingest(List<Span> batch) {
        // ponytail: clear-on-full cache; edges are lost for parents evicted before their children arrive
        if (spanService.size() > SPAN_SERVICE_CACHE) spanService.clear();
        for (Span s : batch) spanService.put(s.spanId(), s.service());

        spans.append(batch);
        for (Span s : batch) {
            if (s.entry()) metrics.recordEntry(s);
            if (!s.root()) {
                String parentService = spanService.get(s.parentSpanId());
                if (parentService != null && !parentService.equals(s.service())) metrics.recordEdge(parentService, s);
            }
            if (knownVersions.add(s.service() + "@" + s.version())) {
                metadata.recordDeploy(new Deploy(s.service(), s.version(), s.startNanos() / 1_000_000));
            }
        }
        return batch.size();
    }
}
