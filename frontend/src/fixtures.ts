import type { TraceRow } from './api';

/** Test helper: a waterfall row whose service is named after its depth. */
export function row(spanId: string, depth: number, offsetMicros: number, durationMicros: number, error = false): TraceRow {
  return {
    depth,
    offsetMicros,
    span: {
      traceId: 't', spanId, parentSpanId: '', service: `svc-${depth}`, version: '1', name: `op-${spanId}`,
      kind: 'SERVER', startNanos: 0, durationNanos: durationMicros * 1000, error, attributes: {},
    },
  };
}
