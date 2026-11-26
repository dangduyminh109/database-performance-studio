import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';

const base = process.env.STUDIO_SMOKE_URL ?? 'http://127.0.0.1:8080';
async function request(path, method = 'GET', body, code = 200) {
  const response = await fetch(base + '/api' + path, {
    method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  assert.equal(response.status, code, method + ' ' + path + ' returned ' + text);
  if (!text) return null;
  return response.headers.get('content-type')?.includes('json') ? JSON.parse(text) : text;
}

async function wait(prefix, job, expected = 'completed') {
  const expires = Date.now() + 210_000;
  let current = job;
  while (Date.now() < expires && ['queued', 'running'].includes(current.data.status)) {
    await delay(550);
    current = await request(prefix + '/jobs/' + job.id);
  }
  assert.equal(current.data.status, expected, JSON.stringify(current.data));
  return current;
}

await request('/health');
const setup = await request('/demo', 'POST', undefined, 202);
const prefix = '/projects/' + setup.project.id;
const sandbox = prefix + '/sandboxes/' + setup.sandbox.id;
try {
  await wait(prefix, setup.job);
  const resources = await request(prefix + '/resources/sandbox');
  assert.equal(resources[0].data.status, 'running');
  assert.ok(!Object.hasOwn(resources[0], 'secret'));
  const catalog = await request(prefix + '/catalog/sandbox/' + setup.sandbox.id);
  assert.equal(catalog.tables.length, 2);
  assert.ok(catalog.tables.some(table => table.constraints.some(item => item.type === 'f')));
  const optional = { name: 'orders_customer_id_idx', schema: 'public' };
  let inventory = await request(sandbox + '/indexes');
  assert.ok(inventory.some(i => i.name === optional.name && i.enabled));
  await request(sandbox + '/indexes/toggle', 'POST', { ...optional, enabled: false });
  inventory = await request(sandbox + '/indexes');
  assert.ok(inventory.some(i => i.name === optional.name && !i.enabled));
  await request(sandbox + '/indexes/toggle', 'POST', { ...optional, enabled: true });
  await request(sandbox + '/indexes/toggle', 'POST', { name: 'customers_pkey', schema: 'public', enabled: false }, 400);

  await request(sandbox + '/indexes/build', 'POST', { schema: 'public', table: 'orders',
    name: 'orders_status_smoke_idx', columns: ['status'] });
  inventory = await request(sandbox + '/indexes/remove', 'POST', { schema: 'public', name: 'orders_status_smoke_idx' });
  assert.ok(!inventory.some(i => i.name === 'orders_status_smoke_idx'));

  const run = await request(prefix + '/benchmarks', 'POST', setup.benchmark, 202);
  const events = await fetch(base + '/api' + prefix + '/jobs/' + run.id + '/events');
  assert.match(events.headers.get('Content-Type') ?? '', /text\/event-stream/);
  const reader = events.body.getReader();
  const event = await reader.read();
  assert.match(new TextDecoder().decode(event.value), /event:progress/);
  await reader.cancel();
  await wait(prefix, run);
  const report = await request(prefix + '/reports/' + run.id);
  assert.equal(report.scenarios.length, 6);
  assert.equal(report.scenarios.reduce((n, item) => n + item.samples.length, 0), 72);
  assert.ok(report.scenarios.some(item => item.summary.planNode.includes('Index')));
  assert.ok(report.scenarios.some(item => item.summary.planNode === 'Seq Scan'));
  assert.ok(report.scenarios.every(item => item.samples.every(sample => sample.rows === 1)));
  const csv = await request(prefix + '/reports/' + run.id + '/export?format=csv');
  assert.equal(csv.trimEnd().split('\r\n').length, 73);
  inventory = await request(sandbox + '/indexes');
  assert.ok(!inventory.some(i => i.name === 'customers_lower_email_idx'));

  const job = await request(prefix + '/execute', 'POST', { sandboxId: setup.sandbox.id,
    sql: 'SELECT count(*) FROM customers WHERE id > ?', parameters: [0] }, 202);
  await wait(prefix, job);
  const result = await request(prefix + '/jobs/' + job.id + '/preview');
  assert.equal(result[0].rows[0][0], '100000');

  const pending = await request(prefix + '/execute', 'POST', { sandboxId: setup.sandbox.id, sql: 'SELECT pg_sleep(15)' }, 202);
  await delay(600);
  await request(prefix + '/jobs/' + pending.id + '/cancel', 'POST', undefined, 202);
  await wait(prefix, pending, 'cancelled');


  const load = await request(prefix + '/loads', 'POST', {
    sandboxId: setup.sandbox.id, queryIds: setup.benchmark.queryIds, concurrency: 2, duration: 3, ramp: 1,
  }, 202);
  await wait(prefix, load);
  const loadReport = await request(prefix + '/loads/' + load.id);
  assert.ok(loadReport.successful > 0);
  assert.equal(loadReport.errors, 0);
  assert.ok(loadReport.p95 >= loadReport.p50);

  const html = await request(prefix + '/reports/' + run.id + '/export?format=html');
  assert.match(html, /<th>Mean client ms<\/th>/);
  const dump = [
    '\\restrict SampleToken', 'SET statement_timeout = 0;',
    'CREATE TABLE public.import_smoke(id integer PRIMARY KEY, note text);',
    'COPY public.import_smoke (id, note) FROM stdin;', '1\tfirst', '2\tsecond', '\\.',
    'ALTER TABLE public.import_smoke OWNER TO old_owner;', '\\unrestrict SampleToken',
  ].join('\n');
  await request(sandbox + '/import', 'POST', { sql: dump });
  const imported = await request(prefix + '/catalog/sandbox/' + setup.sandbox.id + '?exact=true');
  assert.equal(imported.tables.find(t => t.name === 'import_smoke').exactRows, 2);

  const failed = await request(prefix + '/resources/connection', 'POST', { name: 'disallowed', host: 'example.test',
    database: 'sample', username: 'sample', password: 'ephemeral-test-secret' }, 400);
  assert.ok(!JSON.stringify(failed).includes('ephemeral-test-secret'));
  const origin = await fetch(base + '/api/projects', { headers: { Origin: 'https://untrusted.example' } });
  assert.equal(origin.status, 403);
  await request(sandbox + '/stop', 'POST');
  await request(sandbox + '/start', 'POST');
  const restarted = await request(prefix + '/catalog/sandbox/' + setup.sandbox.id + '?exact=true');
  assert.equal(restarted.tables.find(t => t.name === 'customers').exactRows, 100000);

  if (process.env.STUDIO_VERIFY_LARGE_DATASET) {
    const generated = await request(prefix + '/generate', 'POST', { sandboxId: setup.sandbox.id, rows: 1000000, seed: 42,
      tables: { 'public.customers': 1000000, 'public.orders': 1000000 }, replace: true }, 202);
    await wait(prefix, generated);
    const large = await request(prefix + '/catalog/sandbox/' + setup.sandbox.id + '?exact=true');
    for (const name of ['customers', 'orders']) assert.equal(large.tables.find(t => t.name === name).exactRows, 1000000);
    console.log('Streaming COPY generated one million customers and one million foreign-key-linked orders.');
  }

  console.table(report.scenarios.map(({ summary }) => ({ rows: summary.size, configuration: summary.indexConfig, plan: summary.planNode,
    meanMs: summary.mean.toFixed(3), samples: summary.count })));
  console.log('PostgreSQL integration checks passed.');
} finally {
  if (!process.env.STUDIO_KEEP_SMOKE_PROJECT) await request(prefix, 'DELETE', undefined, 204);
  else console.log('Retained project ' + setup.project.id);
}
