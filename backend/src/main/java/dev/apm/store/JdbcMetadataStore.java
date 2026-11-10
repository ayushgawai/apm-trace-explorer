package dev.apm.store;

import dev.apm.model.Records.Anomaly;
import dev.apm.model.Records.Deploy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** PostgreSQL metadata store; schema lives in src/main/resources/schema.sql. */
@Component
@ConditionalOnProperty(name = "apm.stores", havingValue = "external")
public class JdbcMetadataStore implements MetadataStore {
    private final JdbcTemplate jdbc;

    public JdbcMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordDeploy(Deploy d) {
        jdbc.update("INSERT INTO deploys(service, version, first_seen) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                d.service(), d.version(), d.firstSeenMillis());
    }

    @Override
    public List<Deploy> deploys(String service) {
        String sql = "SELECT service, version, first_seen FROM deploys"
                + (service == null ? "" : " WHERE service = ?") + " ORDER BY first_seen";
        Object[] args = service == null ? new Object[0] : new Object[]{service};
        return jdbc.query(sql, (rs, i) -> new Deploy(rs.getString(1), rs.getString(2), rs.getLong(3)), args);
    }

    @Override
    public void saveAnomaly(Anomaly a) {
        jdbc.update("""
                INSERT INTO anomalies(service, endpoint, kind, detected_at, baseline_p95_ms, current_p95_ms,
                                      baseline_error_rate, current_error_rate, example_trace_ids)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                a.service(), a.endpoint(), a.kind(), a.detectedAtMillis(), a.baselineP95Ms(), a.currentP95Ms(),
                a.baselineErrorRate(), a.currentErrorRate(), String.join(",", a.exampleTraceIds()));
    }

    @Override
    public List<Anomaly> anomalies(int limit) {
        return jdbc.query("""
                SELECT service, endpoint, kind, detected_at, baseline_p95_ms, current_p95_ms,
                       baseline_error_rate, current_error_rate, example_trace_ids
                FROM anomalies ORDER BY id DESC LIMIT ?""",
                (rs, i) -> new Anomaly(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                        Arrays.stream(rs.getString(9).split(",")).filter(s -> !s.isEmpty()).toList()),
                limit);
    }
}
