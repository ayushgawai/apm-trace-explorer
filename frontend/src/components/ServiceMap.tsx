import * as d3 from 'd3';
import { useEffect, useRef } from 'react';
import type { Red, ServiceMapData } from '../api';
import { fmtMs, fmtPct, health } from '../lib';

type Node = d3.SimulationNodeDatum & Red;
type Link = d3.SimulationLinkDatum<Node> & { calls: number; errors: number; avgMs: number };

const W = 900;
const H = 520;

export function ServiceMap({ data, selected, onSelect }: {
  data: ServiceMapData;
  selected?: string;
  onSelect: (service: string) => void;
}) {
  const ref = useRef<SVGSVGElement>(null);

  useEffect(() => {
    const svg = d3.select(ref.current!);
    svg.selectAll('*').remove();
    const nodes: Node[] = data.nodes.map((n) => ({ ...n }));
    const byName = new Map(nodes.map((n) => [n.service, n]));
    const links: Link[] = data.edges
      .filter((e) => byName.has(e.from) && byName.has(e.to))
      .map((e) => ({ source: byName.get(e.from)!, target: byName.get(e.to)!, calls: e.calls, errors: e.errors, avgMs: e.avgMs }));

    const maxRate = d3.max(nodes, (n) => n.ratePerSec) ?? 1;
    const radius = d3.scaleSqrt().domain([0, maxRate]).range([10, 30]);
    const width = d3.scaleLinear().domain([0, d3.max(links, (l) => l.calls) ?? 1]).range([1, 6]);

    // static layout: run the force simulation to rest instead of animating it
    d3.forceSimulation(nodes)
      .force('link', d3.forceLink<Node, Link>(links).distance(110))
      .force('charge', d3.forceManyBody().strength(-700))
      .force('center', d3.forceCenter(W / 2, H / 2))
      .force('collide', d3.forceCollide<Node>((n) => radius(n.ratePerSec) + 30))
      .stop()
      .tick(300);

    svg.append('defs').append('marker')
      .attr('id', 'arrow').attr('viewBox', '0 -5 10 10').attr('refX', 10).attr('markerWidth', 6).attr('markerHeight', 6)
      .attr('orient', 'auto').append('path').attr('d', 'M0,-5L10,0L0,5').attr('class', 'arrowhead');

    svg.append('g').selectAll('line').data(links).join('line')
      .attr('class', (l) => (l.errors / l.calls > 0.05 ? 'edge bad' : 'edge'))
      .attr('stroke-width', (l) => width(l.calls))
      .attr('marker-end', 'url(#arrow)')
      .each(function (l) {
        const s = l.source as Node, t = l.target as Node;
        const dx = t.x! - s.x!, dy = t.y! - s.y!, len = Math.hypot(dx, dy) || 1;
        const rs = radius(s.ratePerSec), rt = radius(t.ratePerSec) + 4;
        d3.select(this)
          .attr('x1', s.x! + (dx / len) * rs).attr('y1', s.y! + (dy / len) * rs)
          .attr('x2', t.x! - (dx / len) * rt).attr('y2', t.y! - (dy / len) * rt);
      })
      .append('title').text((l) => `${(l.source as Node).service} → ${(l.target as Node).service}: ${l.calls} calls, ${fmtPct(l.errors / l.calls)} errors, avg ${fmtMs(l.avgMs)}`);

    const g = svg.append('g').selectAll('g').data(nodes).join('g')
      .attr('class', (n) => `node ${health(n.errorRate, n.p95Ms)}${n.service === selected ? ' selected' : ''}`)
      .attr('transform', (n) => `translate(${n.x},${n.y})`)
      .attr('tabindex', 0)
      .attr('role', 'button')
      .on('click', (_, n) => onSelect(n.service))
      .on('keydown', (ev: KeyboardEvent, n) => ev.key === 'Enter' && onSelect(n.service));
    g.append('circle').attr('r', (n) => radius(n.ratePerSec));
    g.append('text').attr('class', 'node-name').attr('y', (n) => radius(n.ratePerSec) + 14).text((n) => n.service);
    g.append('text').attr('class', 'node-stats').attr('y', (n) => radius(n.ratePerSec) + 27)
      .text((n) => `${n.ratePerSec.toFixed(1)}/s · p95 ${fmtMs(n.p95Ms)} · ${fmtPct(n.errorRate)}`);
  }, [data, selected, onSelect]);

  return <svg ref={ref} viewBox={`0 0 ${W} ${H}`} className="service-map" role="img" aria-label="Service dependency map" />;
}
