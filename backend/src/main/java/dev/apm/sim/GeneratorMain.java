package dev.apm.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apm.ingest.Otlp;
import dev.apm.model.Span;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sends simulated traffic to a running backend over OTLP/HTTP JSON.
 * <pre>
 *   --url http://localhost:8080   backend base url
 *   --minutes 30 --rps 20         backfill this much traffic ending now (with the demo incident)
 *   --incident-minutes 3          0 disables the incident
 *   --live                        then keep sending rps traces per second; incident every 10 min for 3 min
 * </pre>
 */
public final class GeneratorMain {
    private static final int TRACES_PER_REQUEST = 50;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--live")) a.put("live", "true");
            else if (args[i].startsWith("--") && i + 1 < args.length) a.put(args[i].substring(2), args[++i]);
        }
        String url = a.getOrDefault("url", "http://localhost:8080") + "/v1/traces";
        int minutes = Integer.parseInt(a.getOrDefault("minutes", "30"));
        double rps = Double.parseDouble(a.getOrDefault("rps", "20"));
        int incident = Integer.parseInt(a.getOrDefault("incident-minutes", "3"));
        Random rnd = new Random(Long.parseLong(a.getOrDefault("seed", "42")));
        Sender sender = new Sender(url);

        long now = System.currentTimeMillis();
        if (minutes > 0) {
            var gen = new TraceGenerator(rnd, incident > 0 ? TraceGenerator.incident(now, incident) : List.of());
            long n = gen.generate(now - minutes * 60_000L, now, rps, sender::add);
            sender.flush();
            System.out.printf("backfilled %d traces (%d spans) over %d min to %s%n", n, sender.spans, minutes, url);
        }
        while (a.containsKey("live")) {
            long t = System.currentTimeMillis();
            boolean incidentOn = (t / 1000) % 600 >= 420;
            var gen = new TraceGenerator(rnd, incidentOn ? TraceGenerator.incident(t + 1000, 3) : List.of());
            gen.generate(t, t + 1000, rps, sender::add);
            sender.flush();
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - t)));
        }
    }

    private static final class Sender {
        private final HttpClient http = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();
        private final URI uri;
        private final List<Span> pending = new ArrayList<>();
        private int traces;
        long spans;

        Sender(String url) {
            this.uri = URI.create(url);
        }

        void add(List<Span> trace) {
            pending.addAll(trace);
            if (++traces % TRACES_PER_REQUEST == 0) flush();
        }

        void flush() {
            if (pending.isEmpty()) return;
            try {
                byte[] body = json.writeValueAsBytes(Otlp.encode(pending));
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IllegalStateException("ingest failed: " + r.statusCode() + " " + r.body());
                spans += pending.size();
                pending.clear();
            } catch (java.io.IOException | InterruptedException e) {
                throw new IllegalStateException("cannot reach " + uri, e);
            }
        }
    }
}
