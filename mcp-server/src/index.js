#!/usr/bin/env node
// MCP server (stdio, JSON-RPC 2.0) proxying the APM REST API. No dependencies: Node >=18 fetch + readline.
// Env: APM_BACKEND (default http://localhost:8080)
import { createInterface } from 'node:readline';

const BASE = process.env.APM_BACKEND ?? 'http://localhost:8080';

export const TOOLS = [
  { name: 'find_slow_endpoints', description: 'Endpoints with highest p95 latency (RED metrics) in the window.',
    inputSchema: { type: 'object', properties: { minutes: { type: 'number', default: 15 }, service: { type: 'string' }, limit: { type: 'number', default: 10 } } } },
  { name: 'find_error_endpoints', description: 'Endpoints with highest error rate in the window.',
    inputSchema: { type: 'object', properties: { minutes: { type: 'number', default: 15 }, service: { type: 'string' }, limit: { type: 'number', default: 10 } } } },
  { name: 'get_trace', description: 'Full span waterfall for one trace id.',
    inputSchema: { type: 'object', properties: { traceId: { type: 'string' } }, required: ['traceId'] } },
  { name: 'compare_deploys', description: 'Per-endpoint p95/error diff between two deployed versions of a service (defaults: last two).',
    inputSchema: { type: 'object', properties: { service: { type: 'string' }, baseline: { type: 'string' }, candidate: { type: 'string' }, minutes: { type: 'number', default: 180 } }, required: ['service'] } },
  { name: 'list_anomalies', description: 'Recently detected latency/error anomalies with example trace ids.',
    inputSchema: { type: 'object', properties: { limit: { type: 'number', default: 20 } } } },
  { name: 'get_service_map', description: 'Services (RED summaries) and call edges for the window.',
    inputSchema: { type: 'object', properties: { minutes: { type: 'number', default: 15 } } } },
];

const q = (o) => new URLSearchParams(Object.entries(o).filter(([, v]) => v !== undefined && v !== '')).toString();

export async function callTool(name, args = {}, fetchFn = fetch) {
  const routes = {
    find_slow_endpoints: () => `/api/endpoints?${q({ sort: 'p95', minutes: args.minutes ?? 15, service: args.service, limit: args.limit ?? 10 })}`,
    find_error_endpoints: () => `/api/endpoints?${q({ sort: 'errors', minutes: args.minutes ?? 15, service: args.service, limit: args.limit ?? 10 })}`,
    get_trace: () => `/api/traces/${encodeURIComponent(args.traceId)}`,
    compare_deploys: () => `/api/deploys/compare?${q({ service: args.service, baseline: args.baseline, candidate: args.candidate, minutes: args.minutes ?? 180 })}`,
    list_anomalies: () => `/api/anomalies?${q({ limit: args.limit ?? 20 })}`,
    get_service_map: () => `/api/service-map?${q({ minutes: args.minutes ?? 15 })}`,
  };
  if (!routes[name]) throw new Error(`unknown tool: ${name}`);
  const r = await fetchFn(BASE + routes[name]());
  const text = await r.text();
  if (!r.ok) throw new Error(`${name} -> HTTP ${r.status}: ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

export async function handle(msg, fetchFn = fetch) {
  const reply = (result) => ({ jsonrpc: '2.0', id: msg.id, result });
  const fail = (code, message) => ({ jsonrpc: '2.0', id: msg.id, error: { code, message } });
  try {
    if (msg.method === 'initialize') return reply({ protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'apm-trace-explorer', version: '0.1.0' } });
    if (msg.method === 'tools/list') return reply({ tools: TOOLS });
    if (msg.method === 'tools/call') {
      const data = await callTool(msg.params.name, msg.params.arguments ?? {}, fetchFn);
      return reply({ content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] });
    }
    if (msg.method === 'ping') return reply({});
    if (msg.id === undefined || msg.id === null) return null; // notification
    return fail(-32601, `method not found: ${msg.method}`);
  } catch (e) {
    // tool errors are returned as content with isError, per MCP convention
    if (msg.method === 'tools/call') return reply({ content: [{ type: 'text', text: String(e.message ?? e) }], isError: true });
    return fail(-32603, String(e.message ?? e));
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const rl = createInterface({ input: process.stdin });
  rl.on('line', async (line) => {
    if (!line.trim()) return;
    let out;
    try { out = await handle(JSON.parse(line)); } catch { out = { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }; }
    if (out) process.stdout.write(JSON.stringify(out) + '\n');
  });
}
