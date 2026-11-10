import { describe, expect, it } from 'vitest';
import { row } from './fixtures';
import { fmtMs, fmtPct, health, heatmapCells, waterfallLayout } from './lib';

describe('waterfallLayout', () => {
  it('scales offsets and durations to the available width', () => {
    const bars = waterfallLayout([row('a', 0, 0, 100_000), row('b', 1, 25_000, 50_000)], 100_000, 400, 20);
    expect(bars.map((b) => [b.x, b.width, b.y])).toEqual([[0, 400, 0], [100, 200, 20]]);
  });

  it('keeps zero-length spans visible and survives an empty trace', () => {
    expect(waterfallLayout([row('a', 0, 0, 0)], 1000, 400, 20)[0].width).toBe(1);
    expect(waterfallLayout([row('a', 0, 0, 0)], 0, 400, 20)[0]).toMatchObject({ x: 0, width: 1 });
  });
});

describe('heatmapCells', () => {
  it('returns only non-empty cells with their coordinates', () => {
    const cells = heatmapCells({ bucketStartMillis: [0, 60_000], binUpperMs: [1, 10, 100], counts: [[0, 3, 0], [1, 0, 7]] });
    expect(cells).toEqual([{ t: 0, bin: 1, count: 3 }, { t: 1, bin: 0, count: 1 }, { t: 1, bin: 2, count: 7 }]);
  });
});

describe('health and formatting', () => {
  it('classifies by error rate and p95', () => {
    expect(health(0, 20)).toBe('ok');
    expect(health(0.02, 20)).toBe('warn');
    expect(health(0, 250)).toBe('warn');
    expect(health(0.1, 20)).toBe('bad');
    expect(health(0, 900)).toBe('bad');
  });

  it('formats durations and percentages', () => {
    expect(fmtMs(2.345)).toBe('2.3 ms');
    expect(fmtMs(240)).toBe('240 ms');
    expect(fmtMs(1500)).toBe('1.50 s');
    expect(fmtPct(0.0123)).toBe('1.23%');
    expect(fmtPct(0.25)).toBe('25.0%');
  });
});
