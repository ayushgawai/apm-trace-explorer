package dev.apm.store;

import dev.apm.model.Records.Anomaly;
import dev.apm.model.Records.Deploy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "apm.stores", havingValue = "memory", matchIfMissing = true)
public class InMemoryMetadataStore implements MetadataStore {
    private final List<Deploy> deploys = new ArrayList<>();
    private final List<Anomaly> anomalies = new ArrayList<>();

    @Override
    public synchronized void recordDeploy(Deploy d) {
        boolean known = deploys.stream().anyMatch(x -> x.service().equals(d.service()) && x.version().equals(d.version()));
        if (!known) deploys.add(d);
    }

    @Override
    public synchronized List<Deploy> deploys(String service) {
        return deploys.stream().filter(d -> service == null || d.service().equals(service))
                .sorted(Comparator.comparingLong(Deploy::firstSeenMillis)).toList();
    }

    @Override
    public synchronized void saveAnomaly(Anomaly a) {
        anomalies.add(a);
    }

    @Override
    public synchronized List<Anomaly> anomalies(int limit) {
        return anomalies.reversed().stream().limit(limit).toList();
    }
}
