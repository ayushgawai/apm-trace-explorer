import * as d3 from 'd3';
import type { TraceView } from '../api';
import { fmtMs, waterfallLayout } from '../lib';

const ROW = 22;
const LABEL = 300;
const WIDTH = 900;

export function Waterfall({ trace }: { trace: TraceView }) {
  const color = d3.scaleOrdinal<string, string>(d3.schemeTableau10).domain(trace.services);
  const bars = waterfallLayout(trace.rows, trace.durationMicros, WIDTH - LABEL - 70, ROW);
  const ticks = d3.scaleLinear().domain([0, trace.durationMicros / 1000]).range([0, WIDTH - LABEL - 70]).ticks(6);
  const pxPerMs = (WIDTH - LABEL - 70) / Math.max(1e-9, trace.durationMicros / 1000);

  return (
    <div className="waterfall">
      <div className="trace-head">
        <strong>{trace.rootService}</strong> {trace.rootName} · {fmtMs(trace.durationMicros / 1000)} · {trace.spanCount} spans
        {trace.errorCount > 0 && <span className="badge bad">{trace.errorCount} errors</span>}
        <code>{trace.traceId}</code>
      </div>
      <svg width={WIDTH} height={bars.length * ROW + 24} role="img" aria-label={`Trace ${trace.traceId} waterfall`}>
        <g transform="translate(0,20)">
          {ticks.map((t) => (
            <g key={t} transform={`translate(${LABEL + t * pxPerMs},0)`}>
              <line y1={-4} y2={bars.length * ROW} className="grid" />
              <text y={-8} className="tick">{fmtMs(t)}</text>
            </g>
          ))}
          {bars.map((b) => (
            <g key={b.row.span.spanId} transform={`translate(0,${b.y})`} className={b.row.span.error ? 'span error' : 'span'}>
              <title>{`${b.row.span.service} ${b.row.span.name} (${b.row.span.version}) ${fmtMs(b.row.span.durationNanos / 1e6)}`}</title>
              <text x={6 + b.row.depth * 14} y={ROW / 2 + 4} className="label">
                {b.row.span.service} <tspan className="muted">{b.row.span.name}</tspan>
              </text>
              <rect x={LABEL + b.x} y={4} width={b.width} height={ROW - 8} rx={2}
                fill={b.row.span.error ? 'var(--bad)' : color(b.row.span.service)} />
              <text x={LABEL + b.x + b.width + 4} y={ROW / 2 + 4} className="dur">{fmtMs(b.row.span.durationNanos / 1e6)}</text>
            </g>
          ))}
        </g>
      </svg>
    </div>
  );
}
