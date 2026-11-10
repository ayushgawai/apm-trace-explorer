package dev.apm.store;

import dev.apm.model.Records.Anomaly;
import dev.apm.model.Records.Deploy;

import java.util.List;

/** Low-volume metadata: deploys (first sighting of a service.version) and detected anomalies. PostgreSQL in docker-compose. */
public interface MetadataStore {
    void recordDeploy(Deploy deploy);

    List<Deploy> deploys(String service);

    void saveAnomaly(Anomaly anomaly);

    List<Anomaly> anomalies(int limit);
}
