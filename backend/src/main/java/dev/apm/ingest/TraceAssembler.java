package dev.apm.ingest;

import dev.apm.model.Records.TraceRow;
import dev.apm.model.Records.TraceSummary;
import dev.apm.model.Records.TraceView;
import dev.apm.model.Span;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a bag of spans into a depth-first ordered tree for the waterfall view. */
public final class TraceAssembler {
    private TraceAssembler() {}

    public static TraceView assemble(String traceId, List<Span> raw) {
        Map<String, Span> byId = new LinkedHashMap<>();
        for (Span s : raw) byId.putIfAbsent(s.spanId(), s); // retried exports can duplicate spans
        if (byId.isEmpty()) return null;

        Map<String, List<Span>> children = new HashMap<>();
        List<Span> roots = new ArrayList<>();
        for (Span s : byId.values()) {
            // a span whose parent never arrived is shown as an extra root rather than dropped
            if (s.root() || !byId.containsKey(s.parentSpanId())) roots.add(s);
            else children.computeIfAbsent(s.parentSpanId(), k -> new ArrayList<>()).add(s);
        }
        Comparator<Span> byStart = Comparator.comparingLong(Span::startNanos);
        if (roots.isEmpty()) roots.add(byId.values().stream().min(byStart).orElseThrow()); // malformed parent cycle
        roots.sort(byStart);
        children.values().forEach(l -> l.sort(byStart));

        long start = byId.values().stream().mapToLong(Span::startNanos).min().orElse(0);
        long end = byId.values().stream().mapToLong(s -> s.startNanos() + s.durationNanos()).max().orElse(0);

        List<TraceRow> rows = new ArrayList<>(byId.size());
        Deque<TraceRow> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(row(roots.get(i), 0, start));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            TraceRow r = stack.pop();
            if (!seen.add(r.span().spanId())) continue;
            rows.add(r);
            List<Span> kids = children.getOrDefault(r.span().spanId(), List.of());
            for (int i = kids.size() - 1; i >= 0; i--) stack.push(row(kids.get(i), r.depth() + 1, start));
        }

        Span root = roots.get(0);
        int errors = (int) byId.values().stream().filter(Span::error).count();
        List<String> services = byId.values().stream().map(Span::service).distinct().sorted().toList();
        return new TraceView(traceId, root.service(), root.name(), start, (end - start) / 1_000,
                rows.size(), errors, services, rows);
    }

    public static TraceSummary summary(TraceView v) {
        return new TraceSummary(v.traceId(), v.rootService(), v.rootName(), v.startNanos(), v.durationMicros(),
                v.spanCount(), v.errorCount());
    }

    private static TraceRow row(Span s, int depth, long traceStart) {
        return new TraceRow(s, depth, (s.startNanos() - traceStart) / 1_000);
    }
}
