import { useCallback, useEffect, useState } from 'react';
import { api, type Anomaly, type Heatmap, type Red, type ServiceMapData, type TraceSummary, type TraceView } from './api';
import { LatencyHeatmap } from './components/LatencyHeatmap';
import { ServiceMap } from './components/ServiceMap';
import { Waterfall } from './components/Waterfall';
import { fmtMs, fmtPct, health } from './lib';

type Tab = 'map' | 'heatmap' | 'traces' | 'anomalies';

export function App() {
  const [tab, setTab] = useState<Tab>('map');
  const [minutes, setMinutes] = useState(30);
  const [service, setService] = useState<string>();
  const [map, setMap] = useState<ServiceMapData>({ nodes: [], edges: [] });
  const [endpoints, setEndpoints] = useState<Red[]>([]);
  const [heatmap, setHeatmap] = useState<Heatmap>();
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [anomalies, setAnomalies] = useState<Anomaly[]>([]);
  const [trace, setTrace] = useState<TraceView>();
  const [traceInput, setTraceInput] = useState('');
  const [status, setStatus] = useState('');

  const refresh = useCallback(async () => {
    try {
      const [m, e, h, t, a] = await Promise.all([
        api.serviceMap(minutes), api.endpoints(minutes, service), api.heatmap(minutes, service),
        api.traces(service), api.anomalies(),
      ]);
      setMap(m); setEndpoints(e); setHeatmap(h); setTraces(t); setAnomalies(a);
      setStatus('');
    } catch (err) {
      setStatus(`Backend unreachable: ${(err as Error).message}`);
    }
  }, [minutes, service]);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 10_000);
    return () => clearInterval(id);
  }, [refresh]);

  const openTrace = async (id: string) => {
    try {
      setTrace(await api.trace(id.trim()));
      setTab('traces');
    } catch {
      setStatus(`Trace ${id} not found (it may have expired)`);
    }
  };

  const simulate = async () => {
    setStatus('Generating 30 minutes of traffic with an incident…');
    const r = await api.simulate();
    setStatus(`Ingested ${r.traces.toLocaleString()} traces / ${r.spans.toLocaleString()} spans`);
    refresh();
  };

  const selectService = useCallback((s: string) => setService((cur) => (cur === s ? undefined : s)), []);

  return (
    <div className="app">
      <header>
        <h1>APM Trace Explorer</h1>
        <label>
          Window{' '}
          <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
            {[15, 30, 60, 180].map((m) => <option key={m} value={m}>{m} min</option>)}
          </select>
        </label>
        {service && <button className="chip" onClick={() => setService(undefined)}>{service} ✕</button>}
        <span className="status" role="status">{status}</span>
        <button onClick={simulate}>Simulate incident</button>
      </header>

      <nav role="tablist">
        {(['map', 'heatmap', 'traces', 'anomalies'] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {{ map: 'Service map', heatmap: 'Latency heatmap', traces: 'Traces', anomalies: `Anomalies (${anomalies.length})` }[t]}
          </button>
        ))}
      </nav>

      <main>
        <section className="panel">
          {tab === 'map' && <ServiceMap data={map} selected={service} onSelect={selectService} />}
          {tab === 'heatmap' && heatmap && <LatencyHeatmap data={heatmap} />}
          {tab === 'traces' && (
            <div>
              <form onSubmit={(e) => { e.preventDefault(); openTrace(traceInput); }}>
                <input aria-label="Trace id" placeholder="trace id" value={traceInput} onChange={(e) => setTraceInput(e.target.value)} />
                <button type="submit">Open</button>
              </form>
              {trace && <Waterfall trace={trace} />}
              <table>
                <thead><tr><th>Root</th><th>Duration</th><th>Spans</th><th>Errors</th><th>Trace</th></tr></thead>
                <tbody>
                  {traces.map((t) => (
                    <tr key={t.traceId}>
                      <td>{t.rootService} <span className="muted">{t.rootName}</span></td>
                      <td>{fmtMs(t.durationMicros / 1000)}</td>
                      <td>{t.spanCount}</td>
                      <td className={t.errorCount ? 'bad-text' : ''}>{t.errorCount}</td>
                      <td><button className="link" onClick={() => openTrace(t.traceId)}>{t.traceId.slice(0, 12)}…</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {tab === 'anomalies' && (
            <table>
              <thead><tr><th>Detected</th><th>Kind</th><th>Service / endpoint</th><th>p95 base → now</th><th>Errors base → now</th><th>Examples</th></tr></thead>
              <tbody>
                {anomalies.map((a, i) => (
                  <tr key={i}>
                    <td>{new Date(a.detectedAtMillis).toLocaleTimeString()}</td>
                    <td><span className={`badge ${a.kind === 'errors' ? 'bad' : 'warn'}`}>{a.kind}</span></td>
                    <td>{a.service} <span className="muted">{a.endpoint}</span></td>
                    <td>{fmtMs(a.baselineP95Ms)} → <strong>{fmtMs(a.currentP95Ms)}</strong></td>
                    <td>{fmtPct(a.baselineErrorRate)} → <strong>{fmtPct(a.currentErrorRate)}</strong></td>
                    <td>{a.exampleTraceIds.slice(0, 3).map((id) => (
                      <button key={id} className="link" onClick={() => openTrace(id)}>{id.slice(0, 8)}</button>
                    ))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <aside className="panel">
          <h2>{service ? `${service} endpoints` : 'Slowest endpoints'} <span className="muted">by p95</span></h2>
          <table>
            <thead><tr><th>Endpoint</th><th>Rate</th><th>Err</th><th>p50</th><th>p95</th><th>p99</th></tr></thead>
            <tbody>
              {endpoints.slice(0, 25).map((e) => (
                <tr key={e.service + e.endpoint} className={health(e.errorRate, e.p95Ms)}
                  onClick={() => e.exampleTraceIds[0] && openTrace(e.exampleTraceIds[0])} title="Open the slowest example trace">
                  <td>{!service && <span className="muted">{e.service} </span>}{e.endpoint}</td>
                  <td>{e.ratePerSec.toFixed(1)}/s</td>
                  <td>{fmtPct(e.errorRate)}</td>
                  <td>{fmtMs(e.p50Ms)}</td>
                  <td><strong>{fmtMs(e.p95Ms)}</strong></td>
                  <td>{fmtMs(e.p99Ms)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {endpoints.length === 0 && <p className="muted">No data yet. Click “Simulate incident” or run the generator.</p>}
        </aside>
      </main>
    </div>
  );
}
