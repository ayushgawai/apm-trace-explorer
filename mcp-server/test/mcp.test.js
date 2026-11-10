import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TOOLS, callTool, handle } from '../src/index.js';

const fakeFetch = (payload, ok = true, status = 200) => async (url) => {
  fakeFetch.lastUrl = url;
  return { ok, status, text: async () => JSON.stringify(payload) };
};

test('tools/list exposes triage tools', async () => {
  const r = await handle({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert.deepEqual(r.result.tools.map(t => t.name), TOOLS.map(t => t.name));
  assert.ok(TOOLS.some(t => t.name === 'find_slow_endpoints' && t.inputSchema));
});

test('find_slow_endpoints hits sorted endpoint API', async () => {
  const f = fakeFetch([{ service: 'payment-service', p95Ms: 900 }]);
  const out = await callTool('find_slow_endpoints', { minutes: 30, limit: 5 }, f);
  assert.equal(out[0].service, 'payment-service');
  assert.match(fakeFetch.lastUrl, /\/api\/endpoints\?.*sort=p95/);
  assert.match(fakeFetch.lastUrl, /limit=5/);
});

test('compare_deploys requires routing to compare API', async () => {
  const f = fakeFetch({ service: 'payment-service' });
  await callTool('compare_deploys', { service: 'payment-service' }, f);
  assert.match(fakeFetch.lastUrl, /\/api\/deploys\/compare\?.*service=payment-service/);
});

test('unknown tool and backend errors surface', async () => {
  await assert.rejects(() => callTool('nope', {}, fakeFetch({})), /unknown tool/);
  const r = await handle({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'get_trace', arguments: { traceId: 'x' } } },
    fakeFetch({ error: 'no' }, false, 404));
  assert.equal(r.result.isError, true);
});
