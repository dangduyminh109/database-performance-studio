'use client';

import { useState } from 'react';
import { Play } from 'lucide-react';
import { api, type Resource } from './data';
import { sandboxOptions, useResources, type WorkspaceContext } from './Studio';
import { Action, Field, Select, Text, Toggle } from './ui';

function chosen(value: unknown): string[] { return Array.isArray(value) ? value.map(String) : []; }

export default function BenchmarkForm({ ctx, onDone, preset }: {
  ctx: WorkspaceContext; onDone: () => void; preset?: Record<string, unknown>;
}) {
  const sandboxes = useResources(ctx.prefix, 'sandbox').data ?? [];
  const queries = useResources(ctx.prefix, 'query').data ?? [];
  const configs = useResources(ctx.prefix, 'index-config').data ?? [];
  const [sandbox, setSandbox] = useState(String(preset?.sandboxId ?? ctx.sandboxId));
  const [name, setName] = useState(String(preset?.name ?? 'Benchmark run'));
  const [sizes, setSizes] = useState(chosen(preset?.sizes).join(', ') || '1000, 10000, 100000');
  const [warmups, setWarmups] = useState(String(preset?.warmups ?? 3));
  const [iterations, setIterations] = useState(String(preset?.iterations ?? 10));
  const [timeout, setTimeoutValue] = useState(String(preset?.timeout ?? 30));
  const [concurrency, setConcurrency] = useState(String(preset?.concurrency ?? 1));
  const [seed, setSeed] = useState(String(preset?.seed ?? 42));
  const [tables, setTables] = useState(Object.keys((preset?.tables ?? {}) as Record<string, unknown>).join(', '));
  const [strategies, setStrategies] = useState(JSON.stringify(preset?.strategies ?? {}, null, 2));
  const [parameters, setParameters] = useState(JSON.stringify(preset?.parameterOverrides ?? {}, null, 2));
  const [queryIds, setQueryIds] = useState(chosen(preset?.queryIds));
  const [configIds, setConfigIds] = useState(chosen(preset?.indexConfigIds));
  const [random, setRandom] = useState(Boolean(preset?.randomOrder));
  const [replace, setReplace] = useState(false);
  const update = (ids: string[], id: string, checked: boolean) => checked ? [...ids, id] : ids.filter(value => value !== id);

  async function submit() {
    const job = await ctx.perform(() => api<Resource>(ctx.prefix + '/benchmarks', 'POST', {
      name, sandboxId: sandbox || ctx.sandboxId, sizes: sizes.split(',').map(s => Number(s.trim())), warmups: Number(warmups),
      iterations: Number(iterations), timeout: Number(timeout), concurrency: Number(concurrency), seed: Number(seed),
      queryIds, indexConfigIds: configIds, randomOrder: random, replaceData: replace,
      strategies: JSON.parse(strategies), parameterOverrides: JSON.parse(parameters),
      tables: Object.fromEntries(tables.split(',').map(t => t.trim()).filter(Boolean).map(t => [t, true])),
    }));
    if (job) { ctx.track(job); onDone(); }
  }

  return <form className="form-body benchmark-form" onSubmit={e => { e.preventDefault(); void submit(); }}>
    <div className="fields two"><Field label="Run name" value={name} onChange={setName} required/>
      <Select label="Sandbox" value={sandbox || ctx.sandboxId} onChange={setSandbox} options={sandboxOptions(sandboxes)}/></div>
    <Field label="Dataset sizes (rows per table, comma separated)" value={sizes} onChange={setSizes} required/>
    <div className="quick-sizes">{[1000, 10000, 100000, 1000000].map(s => <button className={sizes.split(',').map(Number).includes(s) ? 'active' : ''}
      type="button" key={s} onClick={() => { const values = sizes.split(',').map(Number).filter(Boolean);
        setSizes((values.includes(s) ? values.filter(v => v !== s) : [...values, s]).join(', ')); }}>{s.toLocaleString()}</button>)}</div>
    <div className="fields two selection-columns"><fieldset><legend>Saved SQL queries</legend>
      {queries.length ? queries.map(item => <Toggle label={item.name} key={item.id} checked={queryIds.includes(item.id)}
        onChange={checked => setQueryIds(update(queryIds, item.id, checked))}/>) : <p>Save a query in Query Workspace first.</p>}</fieldset>
      <fieldset><legend>Index configurations</legend>{configs.length ? configs.map(item => <Toggle label={item.name} key={item.id}
        checked={configIds.includes(item.id)} onChange={checked => setConfigIds(update(configIds, item.id, checked))}/>) : <p>Uses existing indexes.</p>}</fieldset></div>
    <div className="fields four"><Field label="Warm-ups" value={warmups} onChange={setWarmups} type="number" min={0} max={100}/>
      <Field label="Iterations" value={iterations} onChange={setIterations} type="number" min={1} max={200}/>
      <Field label="Timeout (s)" value={timeout} onChange={setTimeoutValue} type="number" min={1} max={60}/>
      <Field label="Concurrency" value={concurrency} onChange={setConcurrency} type="number" min={1} max={8}/></div>
    <details><summary>Dataset and execution options</summary><div className="detail-fields">
      <div className="fields two"><Field label="Random seed" value={seed} onChange={setSeed} type="number"/>
        <Field label="Tables (schema.table, blank for all)" value={tables} onChange={setTables} placeholder="public.customers"/></div>
      <Text label="Column strategies (JSON)" value={strategies} onChange={setStrategies} rows={3} mono/>
      <Text label="Query parameter overrides keyed by query ID (JSON)" value={parameters} onChange={setParameters} rows={3} mono/>
      <Toggle label="Randomize configuration and query order" checked={random} onChange={setRandom}/>
    </div></details>
    <Toggle label="Replace selected tables' data in this sandbox" checked={replace} onChange={setReplace}/>
    <div className="form-actions"><Action primary busy={ctx.busy} disabled={!replace || !(sandbox || ctx.sandboxId) || !queryIds.length} type="submit">
      <Play size={15} fill="currentColor"/> Run benchmark</Action></div>
  </form>;
}
