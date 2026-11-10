import * as d3 from 'd3';
import { useEffect, useRef } from 'react';
import type { Heatmap } from '../api';
import { fmtMs, heatmapCells } from '../lib';

const W = 900;
const H = 320;
const M = { top: 10, right: 10, bottom: 28, left: 64 };

export function LatencyHeatmap({ data }: { data: Heatmap }) {
  const ref = useRef<SVGSVGElement>(null);

  useEffect(() => {
    const svg = d3.select(ref.current!);
    svg.selectAll('*').remove();
    const cells = heatmapCells(data);
    const x = d3.scaleBand<number>().domain(d3.range(data.bucketStartMillis.length)).range([M.left, W - M.right]).padding(0.05);
    const y = d3.scaleBand<number>().domain(d3.range(data.binUpperMs.length)).range([H - M.bottom, M.top]).padding(0.05);
    const max = d3.max(cells, (c) => c.count) ?? 1;
    // log color scale: one slow request should still be visible next to thousands of fast ones
    const color = d3.scaleSequential(d3.interpolateInferno).domain([0, Math.log1p(max)]);

    svg.append('g').selectAll('rect').data(cells).join('rect')
      .attr('x', (c) => x(c.t)!).attr('y', (c) => y(c.bin)!)
      .attr('width', x.bandwidth()).attr('height', y.bandwidth())
      .attr('fill', (c) => color(Math.log1p(c.count)))
      .append('title')
      .text((c) => `${new Date(data.bucketStartMillis[c.t]).toLocaleTimeString()} · ≤ ${fmtMs(data.binUpperMs[c.bin])}: ${c.count} requests`);

    const time = d3.timeFormat('%H:%M');
    svg.append('g').attr('transform', `translate(0,${H - M.bottom})`)
      .call(d3.axisBottom(x).tickValues(x.domain().filter((i) => i % 5 === 0)).tickFormat((i) => time(new Date(data.bucketStartMillis[i]))));
    svg.append('g').attr('transform', `translate(${M.left},0)`)
      .call(d3.axisLeft(y).tickFormat((i) => `≤ ${fmtMs(data.binUpperMs[i])}`));
  }, [data]);

  return <svg ref={ref} viewBox={`0 0 ${W} ${H}`} className="heatmap" role="img" aria-label="Latency heatmap: time by latency bucket" />;
}
