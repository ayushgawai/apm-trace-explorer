import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { TraceView } from '../api';
import { row } from '../fixtures';
import { Waterfall } from './Waterfall';

describe('Waterfall', () => {
  it('renders one bar per span, indents children and highlights errors', () => {
    const trace: TraceView = {
      traceId: 'abc123', rootService: 'svc-0', rootName: 'op-a', startNanos: 0, durationMicros: 80_000,
      spanCount: 3, errorCount: 1, services: ['svc-0', 'svc-1'],
      rows: [row('a', 0, 0, 80_000), row('b', 1, 10_000, 30_000), row('c', 1, 45_000, 20_000, true)],
    };
    const html = renderToStaticMarkup(<Waterfall trace={trace} />);

    expect(html.match(/<rect/g)).toHaveLength(3);
    expect(html.match(/class="span error"/g)).toHaveLength(1);
    expect(html).toContain('1 errors');
    expect(html).toContain('80 ms');
    expect(html).toContain('x="20"'); // depth-1 label indent: 6 + 14
    expect(html).toContain('aria-label="Trace abc123 waterfall"');
  });
});
