import type { Heatmap, TraceRow } from './api';

export interface Bar {
  row: TraceRow;
  x: number;
  y: number;
  width: number;
}

/** Waterfall geometry: x/width proportional to offset/duration within the trace, one row per span. */
export function waterfallLayout(rows: TraceRow[], totalMicros: number, width: number, rowHeight: number): Bar[] {
  const scale = totalMicros > 0 ? width / totalMicros : 0;
  return rows.map((row, i) => ({
    row,
    x: row.offsetMicros * scale,
    y: i * rowHeight,
    width: Math.max(1, (row.span.durationNanos / 1000) * scale),
  }));
}

export interface HeatCell {
  t: number;
  bin: number;
  count: number;
}

/** Non-empty heatmap cells, ready for a d3 data join. */
export function heatmapCells(h: Heatmap): HeatCell[] {
  const cells: HeatCell[] = [];
  h.counts.forEach((row, t) => row.forEach((count, bin) => count > 0 && cells.push({ t, bin, count })));
  return cells;
}

export type Health = 'ok' | 'warn' | 'bad';

export function health(errorRate: number, p95Ms: number): Health {
  if (errorRate >= 0.05 || p95Ms >= 500) return 'bad';
  if (errorRate >= 0.01 || p95Ms >= 200) return 'warn';
  return 'ok';
}

export function fmtMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`;
  if (ms >= 10) return `${ms.toFixed(0)} ms`;
  return `${ms.toFixed(1)} ms`;
}

export const fmtPct = (x: number) => `${(x * 100).toFixed(x < 0.1 ? 2 : 1)}%`;
