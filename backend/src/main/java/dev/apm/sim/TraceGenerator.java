package dev.apm.sim;

import dev.apm.model.Span;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Simulates a 10-service e-commerce app. Each request flow is a call tree; span latency is log-normal around a
 * per-call base with a 1% slow tail, and errors propagate to callers. Faults inject slow dependencies (optionally
 * as a new deployed version) and error bursts during a time window.
 */
public final class TraceGenerator {
    public static final List<String> SERVICES = List.of("web-frontend", "api-gateway", "auth-service",
            "catalog-service", "search-service", "inventory-service", "cart-service", "checkout-service",
            "payment-service", "shipping-service");

    public record Fault(String service, long fromMillis, long toMillis, double latencyMultiplier,
                        double extraErrorRate, String version) {}

    record Call(String service, String name, double baseMs, List<Call> children) {
        Call(String service, String name, double baseMs, Call... children) {
            this(service, name, baseMs, List.of(children));
        }
    }

    private static final double BASE_ERROR_RATE = 0.002;
    private static final Call AUTH = new Call("auth-service", "ValidateToken", 3);
    private static final Call STOCK = new Call("inventory-service", "GetStock", 5);
    private static final Call[] FLOWS = {
            new Call("web-frontend", "GET /", 4, new Call("api-gateway", "GET /api/home", 2,
                    AUTH, new Call("catalog-service", "ListFeatured", 12))),
            new Call("web-frontend", "GET /search", 4, new Call("api-gateway", "GET /api/search", 2,
                    AUTH, new Call("search-service", "Query", 25, new Call("catalog-service", "GetProducts", 10)))),
            new Call("web-frontend", "GET /product/:id", 4, new Call("api-gateway", "GET /api/product", 2,
                    new Call("catalog-service", "GetProduct", 8, STOCK))),
            new Call("web-frontend", "POST /cart", 4, new Call("api-gateway", "POST /api/cart", 2,
                    AUTH, new Call("cart-service", "AddItem", 6, STOCK))),
            new Call("web-frontend", "POST /checkout", 5, new Call("api-gateway", "POST /api/checkout", 2,
                    AUTH, new Call("checkout-service", "PlaceOrder", 10,
                    new Call("cart-service", "GetCart", 4),
                    new Call("inventory-service", "Reserve", 8),
                    new Call("payment-service", "Charge", 40),
                    new Call("shipping-service", "Quote", 15)))),
    };
    private static final int[] FLOW_WEIGHTS = {30, 25, 25, 12, 8};
    private static final Map<String, String> VERSIONS = Map.of("payment-service", "2.3.0");
    private static final HexFormat HEX = HexFormat.of();

    private final Random rnd;
    private final List<Fault> faults;

    public TraceGenerator(Random rnd, List<Fault> faults) {
        this.rnd = rnd;
        this.faults = faults;
    }

    /**
     * The demo incident ending at {@code endMillis}: payment-service 2.4.0 rolls out and is 6x slower, and
     * inventory-service has a 25% error burst for the last two minutes.
     */
    public static List<Fault> incident(long endMillis, int incidentMinutes) {
        return List.of(
                new Fault("payment-service", endMillis - incidentMinutes * 60_000L, endMillis, 6.0, 0, "2.4.0"),
                new Fault("inventory-service", endMillis - 120_000L, endMillis, 1.0, 0.25, null));
    }

    /** Emits evenly spaced traces (with jitter) across [fromMillis, toMillis). */
    public long generate(long fromMillis, long toMillis, double tracesPerSec, Consumer<List<Span>> sink) {
        long n = (long) ((toMillis - fromMillis) / 1000.0 * tracesPerSec);
        double step = 1000.0 / tracesPerSec;
        for (long i = 0; i < n; i++) {
            long t = fromMillis + (long) (i * step + rnd.nextDouble() * step);
            sink.accept(trace(Math.min(t, toMillis - 1)));
        }
        return n;
    }

    public List<Span> trace(long startMillis) {
        String traceId = HEX.toHexDigits(rnd.nextLong()) + HEX.toHexDigits(rnd.nextLong());
        Span[] out = new Span[16];
        int[] size = {0};
        emit(pickFlow(), traceId, "", startMillis * 1_000_000, startMillis, out, size);
        return Arrays.asList(Arrays.copyOf(out, size[0]));
    }

    private Call pickFlow() {
        int r = rnd.nextInt(100);
        for (int i = 0; i < FLOWS.length; i++) {
            if ((r -= FLOW_WEIGHTS[i]) < 0) return FLOWS[i];
        }
        return FLOWS[0];
    }

    private Span emit(Call c, String traceId, String parentId, long start, long wallMillis, Span[] out, int[] size) {
        double mult = 1, errorRate = BASE_ERROR_RATE;
        String version = VERSIONS.getOrDefault(c.service(), "1.4.2");
        for (Fault f : faults) {
            if (f.service().equals(c.service()) && wallMillis >= f.fromMillis() && wallMillis < f.toMillis()) {
                mult *= f.latencyMultiplier();
                errorRate += f.extraErrorRate();
                if (f.version() != null) version = f.version();
            }
        }
        String spanId = HEX.toHexDigits(rnd.nextLong());
        double ownMs = c.baseMs() * Math.exp(0.3 * rnd.nextGaussian()) * (rnd.nextDouble() < 0.01 ? 4 : 1) * mult;
        boolean error = rnd.nextDouble() < errorRate;
        int slot = size[0]++; // reserve the parent's slot so it precedes its children
        long cursor = start + (long) (ownMs * 0.3 * 1e6);
        for (Call child : c.children()) {
            cursor += 50_000 + rnd.nextInt(150_000); // 50-200us network gap
            Span cs = emit(child, traceId, spanId, cursor, wallMillis, out, size);
            cursor = cs.startNanos() + cs.durationNanos();
            error |= cs.error();
        }
        long end = cursor + (long) (ownMs * 0.7 * 1e6);
        Span s = new Span(traceId, spanId, parentId, c.service(), version, c.name(), "SERVER", start, end - start,
                error, error ? Map.of("http.status_code", "500") : Map.of());
        out[slot] = s;
        return s;
    }
}
