import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';

const base = process.env.STUDIO_SMOKE_URL ?? 'http://127.0.0.1:8080';
const password = process.env.STUDIO_TEST_SOURCE_PASSWORD;
const port = Number(process.env.STUDIO_TEST_SOURCE_PORT);
assert.ok(password && port, 'Set a disposable test source port and password.');
async function request(path, method = 'GET', body, status = 200) {
  const response = await fetch(base + '/api' + path, {
    method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await response.text();
  assert.equal(response.status, status, method + ' ' + path + ' failed: ' + raw);
  return raw ? JSON.parse(raw) : null;
}

const project = await request('/projects', 'POST', { name: 'Clone smoke ' + Date.now() }, 201);
const root = '/projects/' + project.id;
try {
  const connection = await request(root + '/resources/connection', 'POST', { name: 'Disposable source',
    host: '127.0.0.1', port, database: 'smoke_source', username: process.env.STUDIO_TEST_SOURCE_USER ?? 'postgres', password, sslMode: 'disable' }, 201);
  assert.ok(!JSON.stringify(connection).includes(password));
  const check = await request(root + '/connections/' + connection.id + '/test', 'POST');
  assert.ok(check.version.startsWith('17'));
  const source = await request(root + '/catalog/connection/' + connection.id + '?exact=true');
  assert.equal(source.tables.find(table => table.name === 'inventory').exactRows, 2);
  for (const [mode, count] of [['schema', 0], ['clone', 2]]) {
    const { sandbox, job } = await request(root + '/sandboxes', 'POST', { name: mode, mode, connectionId: connection.id }, 202);
    const path = root + '/jobs/' + job.id;
    let state;
    for (let n = 0; n < 150; n++) {
      state = await request(path);
      if (['completed', 'failed', 'cancelled'].includes(state.data.status)) break;
      await delay(750);
    }
    assert.equal(state.data.status, 'completed', JSON.stringify(state.data));
    const clone = await request(root + '/catalog/sandbox/' + sandbox.id + '?exact=true');
    assert.equal(clone.tables.find(table => table.name === 'inventory').exactRows, count);
    await request(root + '/sandboxes/' + sandbox.id + '/delete', 'POST');
  }
  const after = await request(root + '/catalog/connection/' + connection.id + '?exact=true');
  assert.equal(after.tables.find(table => table.name === 'inventory').exactRows, 2);
  console.log('Encrypted connection, source catalog, schema-only clone and schema-and-data clone checks passed.');
} finally {
  await request(root, 'DELETE', undefined, 204);
}
