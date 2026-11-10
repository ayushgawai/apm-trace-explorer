export interface Red {
  service: string;
  endpoint: string | null;
  count: number;
  ratePerSec: number;
  errorRate: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  exampleTraceIds: string[];
}

export interface Edge {
  from: string;
  to: string;
  calls: number;
  errors: number;
  avgMs: number;
}

export interface ServiceMapData {
  nodes: Red[];
  edges: Edge[];
}

export interface Heatmap {
  bucketStartMillis: number[];
  binUpperMs: number[];
  counts: number[][];
}

export interface Span {
  traceId: string;
  spanId: string;
  parentSpanId: string;
  service: string;
  version: string;
  name: string;
  kind: string;
  startNanos: number;
  durationNanos: number;
  error: boolean;
  attributes: Record<string, string>;
}

export interface TraceRow {
  span: Span;
  depth: number;
  offsetMicros: number;
}

export interface TraceView {
  traceId: string;
  rootService: string;
  rootName: string;
  startNanos: number;
  durationMicros: number;
  spanCount: number;
  errorCount: number;
  services: string[];
  rows: TraceRow[];
}

export type TraceSummary = Omit<TraceView, 'rows' | 'services'>;

export interface Anomaly {
  service: string;
  endpoint: string;
  kind: 'latency' | 'errors';
  detectedAtMillis: number;
  baselineP95Ms: number;
  currentP95Ms: number;
  baselineErrorRate: number;
  currentErrorRate: number;
  exampleTraceIds: string[];
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, init);
  if (!r.ok) throw new Error(`${init?.method ?? 'GET'} ${path} -> ${r.status}`);
  return r.json() as Promise<T>;
}

const q = (params: Record<string, string | number | undefined>) =>
  new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]),
  ).toString();

export const api = {
  serviceMap: (minutes: number) => req<ServiceMapData>(`/api/service-map?${q({ minutes })}`),
  endpoints: (minutes: number, service?: string) => req<Red[]>(`/api/endpoints?${q({ minutes, service, sort: 'p95' })}`),
  heatmap: (minutes: number, service?: string) => req<Heatmap>(`/api/heatmap?${q({ minutes, service })}`),
  trace: (id: string) => req<TraceView>(`/api/traces/${encodeURIComponent(id)}`),
  traces: (service?: string) => req<TraceSummary[]>(`/api/traces?${q({ service, limit: 25 })}`),
  anomalies: () => req<Anomaly[]>('/api/anomalies'),
  simulate: () => req<{ traces: number; spans: number }>('/api/simulate?minutes=30&rps=20&incidentMinutes=3', { method: 'POST' }),
};
