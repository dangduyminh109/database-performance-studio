'use client';

import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Editor, { loader, type OnMount } from '@monaco-editor/react';
import type * as Monaco from 'monaco-editor';
import { format } from 'sql-formatter';
import Switch from '@mui/material/Switch';
import { Activity, Boxes, FileCode2, Folder, GitFork, Layers3, Pencil, Play, Plus, Plug, RefreshCw, Save, Table2, Trash2 } from 'lucide-react';
import { useTranslate } from './locale';
import { active, api, bytes, jsonObject, num, status, type Catalog, type Index, type Preview, type Project, type Resource, type Table } from './data';
import { Action, Card, Empty, Field, FileButton, Modal, Select, Text, Toggle } from './ui';
import { Erd, PlanGraph } from './Graphs';
import { sandboxOptions, useResources, type WorkspaceContext } from './Studio';

loader.config({ paths: { vs: '/monaco/vs' } });
const input = (e: FormEvent<HTMLFormElement>) => Object.fromEntries(new FormData(e.currentTarget));
const string = (v: unknown, defaultValue = '') => typeof v === 'string' ? v : defaultValue;
const listNames = (value: string) => value.split(',').map(item => item.trim()).filter(Boolean);

function AwaitProject({ ctx, children }: { ctx: WorkspaceContext; children: ReactNode }) {
  return ctx.project ? children : <Card><Empty title="Choose a project" text="This workspace stores resources within a local project."
    action={<Action primary onClick={() => ctx.go('projects')}><Plus size={15}/> Manage projects</Action>}/></Card>;
}

function NativeField({ label, name, initial, type = 'text', required, min, max }: {
  label: string; name: string; initial?: unknown; type?: string; required?: boolean; min?: number; max?: number;
}) {
  const t = useTranslate();
  return <label className="field"><span>{t(label)}</span><input name={name} defaultValue={typeof initial === 'number' || typeof initial === 'string' ? initial : ''}
    type={type} min={min} max={max} maxLength={type === 'number' ? undefined : 1000} required={required} autoComplete={type === 'password' ? 'new-password' : 'off'}/></label>;
}

function NativeSelect({ label, name, initial, options }: { label: string; name: string; initial?: unknown; options: [string, string][] }) {
  const t = useTranslate();
  return <label className="field"><span>{t(label)}</span><div className="select"><select name={name} defaultValue={string(initial)}>
    {options.map(([value, text]) => <option key={value} value={value}>{t(text)}</option>)}</select></div></label>;
}

const removeResource = (ctx: WorkspaceContext, kind: string, record: Resource, refresh: () => void) =>
  ctx.confirm('Delete ' + record.name, 'This removes the saved ' + kind + ' from this project.', () => {
    void ctx.perform(async () => { await api(ctx.prefix + '/resources/' + kind + '/' + record.id, 'DELETE'); refresh(); });
  });

export function ProjectsView({ ctx }: { ctx: WorkspaceContext }) {
  const query = useQueryClient();
  const [editing, setEditing] = useState<Project | null | undefined>();
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const body = input(e);
    const project = await ctx.perform(() => api<Project>('/projects' + (editing ? '/' + editing.id : ''), editing ? 'PUT' : 'POST',
      { name: body.name, description: body.description, configuration: jsonObject(String(body.configuration)) }));
    if (!project) return;
    query.setQueryData<Project[]>(['projects'], old => [project, ...(old ?? []).filter(item => item.id !== project.id)]);
    ctx.setProject(project.id); setEditing(undefined); void query.invalidateQueries();
  }
  function remove(project: Project) {
    ctx.confirm('Delete ' + project.name, 'This deletes project metadata, benchmark history and its Docker sandboxes.', () => {
      void ctx.perform(async () => {
        await api('/projects/' + project.id, 'DELETE');
        if (ctx.project?.id === project.id) ctx.setProject('');
        void query.invalidateQueries();
      });
    });
  }
  return <div className="workspace-grid"><Card title="Projects" note="Projects and their local workspace settings."
    actions={<Action primary onClick={() => setEditing(null)}><Plus size={15}/> New project</Action>}>
    {ctx.projects.length ? <div className="resource-list">{ctx.projects.map(project =>
      <div className="resource-row" key={project.id}><span className="resource-icon"><Folder size={17}/></span>
        <span><b>{project.name}</b><small>{project.description || 'Local PostgreSQL project'}</small></span>
        <Action onClick={() => ctx.setProject(project.id)}>{ctx.project?.id === project.id ? 'Selected' : 'Open'}</Action>
        <button className="icon" aria-label={'Edit ' + project.name} onClick={() => setEditing(project)}><Pencil size={16}/></button>
        <button className="icon" aria-label={'Delete ' + project.name} onClick={() => remove(project)}><Trash2 size={16}/></button>
      </div>)}</div> : <Empty title="No projects yet" text="Create a project to organize your PostgreSQL experiments."
        action={<Action onClick={() => setEditing(null)}><Plus size={15}/> New project</Action>}/>}
  </Card>
  {editing !== undefined && <Modal title={editing ? 'Edit project' : 'New project'} onClose={() => setEditing(undefined)}>
    <form className="form-body" onSubmit={e => void save(e)}>
      <NativeField label="Name" name="name" initial={editing?.name} required/>
      <NativeField label="Description" name="description" initial={editing?.description}/>
      <label className="field"><span>Configuration (JSON)</span><textarea className="mono" name="configuration" rows={3}
        defaultValue={JSON.stringify(editing?.configuration ?? {}, null, 2)}/></label>
      <div className="form-actions"><Action primary type="submit" busy={ctx.busy}><Save size={15}/> Save project</Action></div>
    </form></Modal>}
  </div>;
}

export function ConnectionsView({ ctx }: { ctx: WorkspaceContext }) {
  const { data = [] } = useResources(ctx.prefix, 'connection');
  const query = useQueryClient();
  const refresh = () => { void query.invalidateQueries({ queryKey: ['resources', ctx.prefix, 'connection'] }); };
  const [editing, setEditing] = useState<Resource | null | undefined>();
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = input(e);
    const password = e.currentTarget.querySelector<HTMLInputElement>('input[type=password]');
    if (password) password.value = '';
    const created = await ctx.perform(() => api<Resource>(ctx.prefix + '/resources/connection' + (editing ? '/' + editing.id : ''),
      editing ? 'PUT' : 'POST', { ...values, port: Number(values.port), timeoutSeconds: Number(values.timeoutSeconds) }));
    if (created) { setEditing(undefined); refresh(); ctx.notify('Connection saved.'); }
  }
  async function test(record: Resource) {
    await ctx.perform(async () => {
      const server = await api<{ version: string; extensions: string[] }>(ctx.prefix + '/connections/' + record.id + '/test', 'POST');
      refresh(); ctx.notify('Connected to PostgreSQL ' + server.version + ' · ' + server.extensions.length + ' extensions');
    });
  }
  return <AwaitProject ctx={ctx}><Card title="PostgreSQL connections" note="Source credentials are encrypted and used for read-only discovery."
    actions={<Action primary onClick={() => setEditing(null)}><Plus size={15}/> Add connection</Action>}>
    {data.length ? <div className="resource-list">{data.map(record => <div className="resource-row" key={record.id}>
      <span className="resource-icon"><Plug size={17}/></span><span><b>{record.name}</b>
        <small>{String(record.data.host)}:{String(record.data.port)} / {String(record.data.database)}</small></span>
      {record.data.server ? <span className="badge completed">Tested</span> : <span className="badge">Untested</span>}
      <Action onClick={() => void test(record)} busy={ctx.busy}>Test</Action>
      <button className="icon" aria-label={'Edit ' + record.name} onClick={() => setEditing(record)}><Pencil size={16}/></button>
      <button className="icon" aria-label={'Delete ' + record.name} onClick={() => removeResource(ctx, 'connection', record, refresh)}><Trash2 size={16}/></button>
    </div>)}</div> : <Empty title="No source connections" text="Add a PostgreSQL connection to discover its schema or clone it into a sandbox."/>}
  </Card>
  {editing !== undefined && <Modal title={editing ? 'Edit connection' : 'Add PostgreSQL connection'} onClose={() => setEditing(undefined)}>
    <form className="form-body" onSubmit={e => void save(e)}>
      <NativeField label="Connection name" name="name" initial={editing?.name} required/>
      <NativeField label="Connection string (optional, without credentials)" name="connectionString"/>
      <div className="fields two"><NativeField label="Host" name="host" initial={editing?.data.host ?? 'localhost'}/>
        <NativeField label="Port" name="port" type="number" min={1} max={65535} initial={editing?.data.port ?? 5432}/></div>
      <div className="fields two"><NativeField label="Database" name="database" initial={editing?.data.database}/>
        <NativeField label="Username" name="username" initial={editing?.data.username} required/></div>
      <NativeField label={editing ? 'Password (blank to keep existing)' : 'Password'} name="password" type="password"/>
      <div className="fields two"><NativeSelect label="SSL mode" name="sslMode" initial={editing?.data.sslMode ?? 'prefer'}
        options={['disable', 'prefer', 'require', 'verify-ca', 'verify-full'].map(mode => [mode, mode])}/>
        <NativeField label="Connect timeout (s)" name="timeoutSeconds" type="number" min={1} max={30} initial={editing?.data.timeoutSeconds ?? 5}/></div>
      <div className="form-actions"><Action primary busy={ctx.busy} type="submit"><Save size={15}/> Save connection</Action></div>
    </form></Modal>}
  </AwaitProject>;
}

export function SandboxView({ ctx }: { ctx: WorkspaceContext }) {
  const { data = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: connections = [] } = useResources(ctx.prefix, 'connection');
  const query = useQueryClient();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState('empty');
  const [importTarget, setImportTarget] = useState<Resource>();
  const [script, setScript] = useState('');
  const refresh = () => { void query.invalidateQueries(); };
  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const values = input(e);
    const result = await ctx.perform(() => api<{ sandbox: Resource; job: Resource }>(ctx.prefix + '/sandboxes', 'POST', {
      ...values, mode, memoryMb: Number(values.memoryMb), cpus: Number(values.cpus), ttlHours: Number(values.ttlHours),
    }));
    if (result) { ctx.setSandbox(result.sandbox.id); ctx.track(result.job); setOpen(false); refresh(); }
  }
  function change(sandbox: Resource, action: string) {
    const apply = () => void ctx.perform(async () => { await api(ctx.prefix + '/sandboxes/' + sandbox.id + '/' + action, 'POST'); refresh(); });
    if (['delete', 'reset'].includes(action)) ctx.confirm(action === 'delete' ? 'Delete sandbox' : 'Reset sandbox',
      'The current sandbox database and its changes will be removed.', apply);
    else apply();
  }
  function importSql() {
    if (!importTarget) return;
    ctx.confirm('Import SQL into sandbox', 'This runs the supplied SQL in the selected sandbox. It can change its schema and data.', () => {
      void ctx.perform(async () => {
        const result = await api<{ statements: number }>(ctx.prefix + '/sandboxes/' + importTarget.id + '/import', 'POST', { sql: script });
        setImportTarget(undefined); setScript(''); refresh(); ctx.notify('Imported ' + result.statements + ' statements.');
      });
    });
  }
  return <AwaitProject ctx={ctx}><Card title="Docker sandboxes" note="Disposable databases for data generation, indexing and testing."
    actions={<Action primary onClick={() => setOpen(true)}><Plus size={15}/> New sandbox</Action>}>
    {data.length ? <div className="resource-list">{data.map(record => <div className="resource-row sandbox-row" key={record.id}>
      <span className="resource-icon"><Boxes size={17}/></span><span><b>{record.name}</b><small>PostgreSQL {String(record.data.version)} · {String(record.data.memoryMb)} MB · {String(record.data.cpus)} CPU</small></span>
      <span className={'badge ' + status(record)}>{status(record)}</span>
      {status(record) === 'running' && <><Action onClick={() => { setImportTarget(record); setScript(''); }}>Import SQL</Action>
        <Action onClick={() => change(record, 'stop')}>Stop</Action><Action onClick={() => change(record, 'restart')}>Restart</Action></>}
      {status(record) === 'stopped' && <Action onClick={() => change(record, 'start')}>Start</Action>}
      <button className="icon" title="Reset sandbox" aria-label={'Reset ' + record.name} onClick={() => change(record, 'reset')}><RefreshCw size={15}/></button>
      <button className="icon" title="Delete sandbox" aria-label={'Delete ' + record.name} onClick={() => change(record, 'delete')}><Trash2 size={16}/></button>
    </div>)}</div> : <Empty title="No sandboxes yet" text="Provision a fresh database, a practice schema, or a clone of a registered PostgreSQL source."/>}
  </Card>
  {open && <Modal title="New PostgreSQL sandbox" onClose={() => setOpen(false)}><form className="form-body" onSubmit={e => void create(e)}>
    <NativeField label="Sandbox name" name="name" initial="performance-sandbox" required/>
    <Select label="Provisioning mode" value={mode} onChange={setMode}
      options={[[ 'empty', 'Empty database' ], [ 'demo', 'Commerce practice schema' ], [ 'schema', 'Clone schema only' ], [ 'clone', 'Clone schema and data' ]]}/>
    {['schema', 'clone'].includes(mode) && <NativeSelect label="Source connection" name="connectionId"
      options={[['', 'Select source'], ...connections.map(item => [item.id, item.name] as [string, string])]}/>}
    <div className="fields two"><NativeSelect label="PostgreSQL version" name="version" initial="17" options={['15','16','17','18'].map(v => [v, v])}/>
      <NativeField label="Automatic cleanup (hours)" name="ttlHours" initial={24} min={1} max={168} type="number"/></div>
    <div className="fields two"><NativeField label="Memory limit (MB)" name="memoryMb" initial={512} min={128} max={4096} type="number"/>
      <NativeField label="CPU limit" name="cpus" initial={1} min={1} max={4} type="number"/></div>
    <div className="form-actions"><Action primary busy={ctx.busy} type="submit"><Plus size={15}/> Create sandbox</Action></div>
  </form></Modal>}
  {importTarget && <Modal title={'Import SQL into ' + importTarget.name} onClose={() => setImportTarget(undefined)}><div className="form-body">
    <p>Paste SQL or upload a plain PostgreSQL dump with COPY FROM stdin. Use source cloning for larger datasets.</p>
    <FileButton label="Choose SQL file" accept=".sql,text/plain" onFile={setScript}/><Text label="SQL script" value={script} onChange={setScript} rows={9} mono/>
    <div className="form-actions"><Action primary disabled={!script.trim()} busy={ctx.busy} onClick={importSql}>Import SQL</Action></div>
  </div></Modal>}
  </AwaitProject>;
}

export function SchemaView({ ctx }: { ctx: WorkspaceContext }) {
  const { data: connections = [] } = useResources(ctx.prefix, 'connection');
  const { data: sandboxes = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: snapshots = [] } = useResources(ctx.prefix, 'schema');
  const client = useQueryClient();
  const [source, setSource] = useState('');
  const [exact, setExact] = useState(false);
  const [table, setTable] = useState('');
  const [diagram, setDiagram] = useState(false);
  const target = source || (ctx.sandboxId ? 'sandbox/' + ctx.sandboxId : connections[0] ? 'connection/' + connections[0].id : '');
  const snapshot = snapshots.find(item => target === 'snapshot/' + item.id);
  const catalog = useQuery({ queryKey: ['catalog', ctx.prefix, target, exact], queryFn: () => api<Catalog>(ctx.prefix + '/catalog/' + target + '?exact=' + exact),
    enabled: Boolean(ctx.prefix && target && !snapshot && !target.startsWith('snapshot/')) });
  const data = snapshot ? snapshot.data as unknown as Catalog : catalog.data;
  const options: [string, string][] = [['', 'Project default'], ...connections.map(r => ['connection/' + r.id, 'Source · ' + r.name] as [string, string]),
    ...sandboxes.map(r => ['sandbox/' + r.id, 'Sandbox · ' + r.name] as [string, string]),
    ...snapshots.map(r => ['snapshot/' + r.id, 'Snapshot · ' + r.name] as [string, string])];
  const current = data?.tables.find(t => t.schema + '.' + t.name === table) ?? data?.tables[0];
  function choose(t: Table) { setTable(t.schema + '.' + t.name); setDiagram(false); }
  async function saveSnapshot() {
    const name = 'Schema ' + new Date().toISOString().slice(0, 19).replace('T', ' ');
    if (await ctx.perform(() => api(ctx.prefix + '/catalog/' + target + '/snapshot', 'POST', { name, exact }))) {
      void client.invalidateQueries(); ctx.notify('Schema snapshot saved.');
    }
  }
  return <AwaitProject ctx={ctx}><div className="workspace-grid">
    <Card title="Database catalog" actions={<div className="filter-line"><Select label="" options={options} value={source}
      onChange={value => { setSource(value); setTable(''); }}/><Action onClick={() => void catalog.refetch()} disabled={!target || Boolean(snapshot)}><RefreshCw size={15}/> Refresh</Action>
      <Action onClick={() => void saveSnapshot()} disabled={!data || Boolean(snapshot)}>Save snapshot</Action></div>}>
      <div className="toolbar"><Toggle label="Exact counts" checked={exact} onChange={setExact} disabled={Boolean(snapshot)}/>
        <Action onClick={() => setDiagram(!diagram)} disabled={!data}><GitFork size={15}/> {diagram ? 'Catalog' : 'ERD'}</Action></div>
    </Card>
    {!target && <Card><Empty title="Select a database" text="Add a source connection or provision a sandbox to explore the PostgreSQL catalog."/></Card>}
    {catalog.isFetching && <p role="status" className="footnote"><Activity size={16}/> Reading catalog…</p>}
    {catalog.error && <div className="inline-error" role="alert">{catalog.error.message}</div>}
    {data && (diagram ? <Erd catalog={data} dark={ctx.dark} onSelect={choose}/> :
      <div className="split-workspace"><Card title="Schemas" className="tree-card"><div className="schema-tree">
        {data.schemas.map(schema => <details key={schema.name} open><summary><Folder size={15}/> {schema.name}</summary>
          {data.tables.filter(t => t.schema === schema.name).map(t => <button key={t.name} onClick={() => choose(t)}
            className={t === current ? 'active' : ''}><Table2 size={14}/> {t.name}<small>{({ r: 'table', p: 'partitioned', v: 'view', m: 'mat. view', f: 'foreign' } as Record<string, string>)[t.kind]}</small></button>)}</details>)}
        <details><summary><Layers3 size={15}/> Sequences ({data.sequences.length})</summary>{data.sequences.map((s, i) => <p key={i}>{String(s.schema)}.{String(s.name)}</p>)}</details>
        <details><summary><Plug size={15}/> Extensions ({data.extensions.length})</summary>{data.extensions.map(ext => <p key={ext.name}>{ext.name} {ext.version}</p>)}</details>
      </div></Card>
      <Card title={current ? current.schema + '.' + current.name : 'Objects'} note={current ? num(current.exactRows ?? current.estimatedRows, 0)
        + (current.exactRows === undefined ? ' estimated rows · ' : ' rows · ') + bytes(current.tableBytes) : 'No observed relations'}>
        {current && <><div className="table-scroll"><table><thead><tr><th>Column</th><th>Type</th><th>Nullable</th><th>Default</th><th>Statistics</th></tr></thead>
          <tbody>{current.columns.map(c => <tr key={c.name}><td className="mono">{c.name}</td><td>{c.type}</td><td>{c.nullable ? 'Yes' : 'No'}</td>
            <td className="mono">{c.identity ? 'Identity' : c.generated ? 'Generated' : c.defaultValue ?? '—'}</td>
            <td>{c.distinctEstimate == null ? '—' : 'Distinct ' + c.distinctEstimate + ' · NULL ' + num((c.nullFraction ?? 0) * 100) + '%'}</td></tr>)}</tbody></table></div>
          <div className="form-body">{current.definition && <pre>{current.definition}</pre>}
            {current.constraints.map(c => <div className="definition" key={c.name}><b>{c.name}</b><code>{c.definition}</code></div>)}
            {data.indexes.filter(i => i.schema === current.schema && i.table === current.name).map(i =>
              <div className="definition" key={i.name}><b>{i.name}</b><code>{i.definition}</code></div>)}</div></>}
      </Card></div>)}
  </div></AwaitProject>;
}

export function GeneratorView({ ctx }: { ctx: WorkspaceContext }) {
  const { data: sandboxes = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: configs = [] } = useResources(ctx.prefix, 'dataset');
  const { data: jobs = [] } = useResources(ctx.prefix, 'job');
  const client = useQueryClient();
  const [name, setName] = useState('Dataset configuration');
  const [rows, setRows] = useState('1000');
  const [seed, setSeed] = useState('42');
  const [tables, setTables] = useState('{}');
  const [strategies, setStrategies] = useState('{}');
  const [replace, setReplace] = useState(false);
  const [configuration, setConfiguration] = useState('');
  const refresh = () => { void client.invalidateQueries(); };
  const settings = () => ({ name, rows: Number(rows), seed: Number(seed), tables: jsonObject(tables), strategies: jsonObject(strategies) });
  function load(id: string) {
    setConfiguration(id);
    const record = configs.find(item => item.id === id);
    if (!record) return;
    setName(record.name); setRows(String(record.data.rows)); setSeed(String(record.data.seed));
    setTables(JSON.stringify(record.data.tables, null, 2)); setStrategies(JSON.stringify(record.data.strategies, null, 2));
  }
  async function save() {
    const record = await ctx.perform(() => api<Resource>(ctx.prefix + '/resources/dataset' + (configuration ? '/' + configuration : ''),
      configuration ? 'PUT' : 'POST', settings()));
    if (record) { setConfiguration(record.id); refresh(); ctx.notify('Dataset configuration saved.'); }
  }
  async function generate() {
    const job = await ctx.perform(() => api<Resource>(ctx.prefix + '/generate', 'POST', { sandboxId: ctx.sandboxId, replace, ...settings() }));
    if (job) { ctx.track(job); refresh(); }
  }
  return <AwaitProject ctx={ctx}><div className="workspace-grid">
    <Card title="Dataset configuration" note="Configure per-table counts and column strategies.">
      <div className="form-body"><div className="fields two"><Select label="Sandbox" value={ctx.sandboxId} onChange={ctx.setSandbox} options={sandboxOptions(sandboxes)}/>
        <Select label="Saved configuration" value={configuration} onChange={load} options={[[ '', 'New configuration' ], ...configs.map(item => [item.id, item.name] as [string, string])]}/></div>
        <Field label="Configuration name" value={name} onChange={setName}/>
        <div className="fields two"><Field label="Records per table" type="number" min={0} max={1000000} value={rows} onChange={setRows}/>
          <Field label="Random seed" type="number" value={seed} onChange={setSeed}/></div>
        <div className="quick-sizes">{[1000, 10000, 100000, 1000000].map(n => <button key={n} className={Number(rows) === n ? 'active' : ''}
          onClick={() => setRows(String(n))}>{num(n, 0)}</button>)}</div>
        <Text label={'Table counts (JSON, for example {"public.customers": 1000})'} value={tables} onChange={setTables} mono/>
        <Text label="Column strategies (JSON, keyed by table and column)" value={strategies} onChange={setStrategies} rows={5} mono/>
        <details><summary>Supported strategies and options</summary><pre>{'{"public.customers":{"region":{"strategy":"list","values":["north","south"]}}}'}</pre>
          <p>Use random, sequential, faker, constant, expression, list, foreignKey, dateRange, numericRange or pattern. Set distribution, nullPercent, duplicatePercent, softDeletePercent, cardinality or keywordPercent where applicable.</p></details>
        <Toggle label="Replace selected tables' data and reset identities" checked={replace} onChange={setReplace}/>
        <div className="form-actions">{configuration && <Action onClick={() => { const record = configs.find(item => item.id === configuration);
          if (record) removeResource(ctx, 'dataset', record, () => { setConfiguration(''); refresh(); }); }}>Delete configuration</Action>}
          <Action onClick={() => void save()} busy={ctx.busy}><Save size={15}/> Save configuration</Action>
          <Action primary disabled={!ctx.sandboxId} busy={ctx.busy} onClick={() => {
            if (replace) ctx.confirm('Replace sandbox data', 'This clears the selected tables and restarts their identity sequences.', () => void generate());
            else void generate();
          }}><Layers3 size={15}/> Generate dataset</Action></div></div>
    </Card>
    <Card title="Generation history">{jobs.filter(j => j.data.type === 'dataset').length ? <div className="resource-list">
      {jobs.filter(j => j.data.type === 'dataset').map(j => <button className="resource-row" key={j.id} onClick={() => ctx.track(j)}>
        <Activity size={17}/><span><b>{String(j.data.label)}</b><small>{String(j.data.message)}</small></span>
        <span className={'badge ' + status(j)}>{status(j)}</span></button>)}</div> : <Empty title="No generation jobs" text="Generated datasets will appear in this project’s history."/>}</Card>
  </div></AwaitProject>;
}

type Sheet = { key: string; saved?: string; name: string; sql: string; folder: string; parameters: string };
const firstSheet: Sheet = { key: 'first', name: 'Untitled query', sql: 'SELECT * FROM public.customers LIMIT 100;', folder: '', parameters: '[]' };

export function SqlView({ ctx }: { ctx: WorkspaceContext }) {
  const { data: sandboxes = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: queries = [] } = useResources(ctx.prefix, 'query');
  const { data: jobs = [] } = useResources(ctx.prefix, 'job');
  const [sheets, setSheets] = useState<Sheet[]>([firstSheet]);
  const [key, setKey] = useState('first');
  const [timeout, setTimeoutValue] = useState('30');
  const [mode, setMode] = useState('execute');
  const [commit, setCommit] = useState(false);
  const [marked, setMarked] = useState<string[]>([]);
  const [previewId, setPreview] = useState('');
  const editor = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const completion = useRef<Monaco.IDisposable | null>(null);
  useEffect(() => () => completion.current?.dispose(), []);
  const current = sheets.find(sheet => sheet.key === key) ?? firstSheet;
  const client = useQueryClient();
  const catalog = useQuery({ queryKey: ['editor-catalog', ctx.prefix, ctx.sandboxId], queryFn: () => api<Catalog>(ctx.prefix + '/catalog/sandbox/' + ctx.sandboxId),
    enabled: Boolean(ctx.prefix && ctx.sandboxId && ctx.sandbox?.data.status === 'running') });
  const catalogRef = useRef<Catalog | undefined>(catalog.data);
  useEffect(() => { catalogRef.current = catalog.data; }, [catalog.data]);
  const previewJob = useQuery({ queryKey: ['sql-preview-job', ctx.prefix, previewId],
    queryFn: () => api<Resource>(ctx.prefix + '/jobs/' + previewId), enabled: Boolean(ctx.prefix && previewId),
    refetchInterval: query => query.state.data && !active(query.state.data) ? false : 1000 });
  const preview = useQuery({ queryKey: ['sql-preview', ctx.prefix, previewId],
    queryFn: () => api<Preview[]>(ctx.prefix + '/jobs/' + previewId + '/preview'),
    enabled: Boolean(ctx.prefix && previewId && previewJob.data?.data.status === 'completed') });
  const folders = [...new Set(queries.map(q => string(q.data.folder)))].sort();
  function update(patch: Partial<Sheet>) { setSheets(old => old.map(sheet => sheet.key === key ? { ...sheet, ...patch } : sheet)); }
  function add(record?: Resource) {
    const id = record?.id ?? window.crypto.randomUUID();
    if (!sheets.some(sheet => sheet.key === id)) setSheets(old => [...old, {
      key: id, saved: record?.id, name: record?.name ?? 'Untitled query', sql: string(record?.data.sql, ''),
      folder: string(record?.data.folder), parameters: JSON.stringify(record?.data.parameters ?? []),
    }]);
    setKey(id);
  }
  function close(id: string) {
    const left = sheets.filter(sheet => sheet.key !== id);
    setSheets(left.length ? left : [firstSheet]);
    if (key === id) setKey(left[0]?.key ?? 'first');
  }
  async function save() {
    const record = await ctx.perform(() => api<Resource>(ctx.prefix + '/resources/query' + (current.saved ? '/' + current.saved : ''),
      current.saved ? 'PUT' : 'POST', { name: current.name, folder: current.folder, sql: current.sql, parameters: JSON.parse(current.parameters) }));
    if (record) { update({ saved: record.id }); void client.invalidateQueries(); ctx.notify('Query saved.'); }
  }
  function execute(batch = false) {
    const doRun = async () => {
      const selection = editor.current?.getSelection();
      const selected = selection && !selection.isEmpty() ? editor.current?.getModel()?.getValueInRange(selection) : undefined;
      const job = await ctx.perform(() => api<Resource>(ctx.prefix + '/execute', 'POST', {
        sandboxId: ctx.sandboxId, sql: selected || current.sql, parameters: batch ? [] : JSON.parse(current.parameters),
        timeout: Number(timeout), mode, commit, queryIds: batch ? marked : [],
      }));
      if (job) { setPreview(job.id); ctx.track(job); void client.invalidateQueries(); }
    };
    if (commit && mode === 'execute') ctx.confirm('Commit sandbox changes', 'SQL writes will be committed to the selected sandbox.', () => void doRun());
    else void doRun();
  }
  const mounted: OnMount = (instance, monaco) => {
    editor.current = instance;
    completion.current?.dispose();
    completion.current = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model: Monaco.editor.ITextModel, pos: Monaco.Position) => {
        const word = model.getWordUntilPosition(pos);
        const range = { startLineNumber: pos.lineNumber, endLineNumber: pos.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
        const names = new Set<string>();
        for (const table of catalogRef.current?.tables ?? []) {
          names.add(table.name);
          table.columns.forEach(col => names.add(col.name));
        }
        return { suggestions: [...names].map(label => ({ label, insertText: label, kind: monaco.languages.CompletionItemKind.Field, range })) };
      },
    });
  };
  return <AwaitProject ctx={ctx}><div className="split-workspace">
    <Card title="Saved queries" className="tree-card" actions={<button className="icon" aria-label="New query tab" onClick={() => add()}><Plus size={17}/></button>}>
      <div className="schema-tree">{folders.length ? folders.map(folder => <details key={folder} open><summary><Folder size={15}/> {folder || 'General'}</summary>
        {queries.filter(q => string(q.data.folder) === folder).map(q => <div className="query-list-row" key={q.id}>
          <input type="checkbox" aria-label={'Select ' + q.name + ' for batch'} checked={marked.includes(q.id)}
            onChange={e => setMarked(old => e.target.checked ? [...old, q.id] : old.filter(id => id !== q.id))}/>
          <button onClick={() => add(q)}><FileCode2 size={14}/>{q.name}</button>
          <button className="icon small" aria-label={'Delete ' + q.name} onClick={() => removeResource(ctx, 'query', q, () => void client.invalidateQueries())}><Trash2 size={13}/></button>
        </div>)}</details>) : <p>No saved SQL queries.</p>}
        <Action disabled={!marked.length || !ctx.sandboxId} onClick={() => execute(true)}>Run selected queries</Action></div>
      <div className="schema-tree"><h3>Execution history</h3>{jobs.filter(job => job.data.type === 'query').slice(0, 10).map(job =>
        <button key={job.id} onClick={() => { ctx.track(job); setPreview(job.id); }}><Activity size={14}/>{String(job.data.label)}<small>{status(job)}</small></button>)}</div>
    </Card>
    <div className="workspace-grid">
      <Card title="SQL editor" actions={<Select label="" value={ctx.sandboxId} onChange={ctx.setSandbox} options={sandboxOptions(sandboxes)}/>}>
        <div className="worksheet-tabs">{sheets.map(sheet => <div key={sheet.key} className={sheet.key === key ? 'selected' : ''}>
          <button onClick={() => setKey(sheet.key)}>{sheet.name}</button><button className="tab-close" aria-label={'Close ' + sheet.name} onClick={() => close(sheet.key)}>×</button>
        </div>)}<button className="icon" aria-label="New worksheet" onClick={() => add()}><Plus size={15}/></button></div>
        <div className="editor-wrap"><Editor height="290px" path={current.key + '.sql'} defaultLanguage="sql" theme={ctx.dark ? 'vs-dark' : 'light'}
          value={current.sql} onChange={value => update({ sql: value ?? '' })} onMount={mounted}
          loading={<p className="footnote">Loading SQL editor…</p>}
          options={{ minimap: { enabled: false }, automaticLayout: true, fontSize: 14, scrollBeyondLastLine: false, padding: { top: 10 } }}/></div>
        <div className="toolbar sql-toolbar"><FileButton label="Import SQL" accept=".sql,text/plain" onFile={sql => update({ sql })}/>
          <Action onClick={() => { try { update({ sql: format(current.sql, { language: 'postgresql' }) }); }
            catch { ctx.notify('The formatter could not parse this SQL.'); } }}>Format</Action>
          <Action onClick={() => void save()} busy={ctx.busy}><Save size={15}/> Save</Action>
          <Select label="" value={mode} onChange={setMode} options={[[ 'execute', 'Execute' ], [ 'explain', 'EXPLAIN' ],
            [ 'analyze', 'EXPLAIN ANALYZE' ], [ 'buffers', 'EXPLAIN ANALYZE BUFFERS' ]]}/>
          <Action primary disabled={!ctx.sandboxId || !current.sql.trim()} busy={ctx.busy} onClick={() => execute()}><Play size={15}/> Run</Action></div>
        <div className="form-body"><div className="fields three"><Field label="Query name" value={current.name} onChange={name => update({ name })}/>
          <Field label="Folder" value={current.folder} onChange={folder => update({ folder })}/>
          <Field label="Timeout (s)" value={timeout} onChange={setTimeoutValue} type="number" min={1} max={60}/></div>
          <Field label="JDBC parameters (?) as JSON array" value={current.parameters} onChange={parameters => update({ parameters })}/>
          <Toggle label="Commit sandbox writes" checked={commit} onChange={setCommit}/></div>
      </Card>
      <Card title="Query results" note={previewJob.data ? String(previewJob.data.data.message) : 'Run a worksheet or a selected statement.'}>
        {(previewJob.error || preview.error) && <div className="inline-error">{previewJob.error?.message ?? preview.error?.message}</div>}
        {preview.data?.map((result, i) => <div key={i} className="preview"><div className="toolbar"><b>{result.name}</b>
          {result.clientMs !== undefined && <span>{num(result.clientMs)} ms · {result.columns?.length ? result.rows?.length + ' rows' : result.updated + ' affected'}{result.truncated ? ' · preview limited' : ''}</span>}</div>
          {result.plan ? <PlanGraph root={result.plan[0]?.Plan} dark={ctx.dark}/> : Boolean(result.columns?.length) &&
            <div className="table-scroll"><table><thead><tr>{result.columns?.map((column, j) => <th key={j}>{column}</th>)}</tr></thead>
              <tbody>{result.rows?.map((row, j) => <tr key={j}>{row.map((cell, k) => <td key={k} className="mono">{cell ?? 'NULL'}</td>)}</tr>)}</tbody></table></div>}</div>)}
      </Card>
    </div></div></AwaitProject>;
}

const indexMethods: [string, string][] = [['btree','B-tree'],['hash','Hash'],['gin','GIN'],['gist','GiST'],['spgist','SP-GiST'],['brin','BRIN']];

export function IndexView({ ctx }: { ctx: WorkspaceContext }) {
  const { data: sandboxes = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: configs = [] } = useResources(ctx.prefix, 'index-config');
  const [build, setBuild] = useState(false);
  const [editing, setEditing] = useState<Resource | null | undefined>();
  const client = useQueryClient();
  const root = ctx.prefix + '/sandboxes/' + ctx.sandboxId + '/indexes';
  const { data: indexes = [], error, isFetching } = useQuery({ queryKey: ['indexes', root], queryFn: () => api<Index[]>(root),
    enabled: Boolean(ctx.prefix && ctx.sandboxId && ctx.sandbox?.data.status === 'running') });
  const catalog = useQuery({ queryKey: ['index-catalog', ctx.prefix, ctx.sandboxId],
    queryFn: () => api<Catalog>(ctx.prefix + '/catalog/sandbox/' + ctx.sandboxId),
    enabled: Boolean(ctx.prefix && ctx.sandboxId && ctx.sandbox?.data.status === 'running') });
  const [relation, setRelation] = useState('');
  const [method, setMethod] = useState('btree');
  const [columns, setColumns] = useState<string[]>([]);
  const [configName, setConfigName] = useState('');
  const [definitions, setDefinitions] = useState('[]');
  const [disabled, setDisabled] = useState<string[]>([]);
  const [removeOptional, setRemoveOptional] = useState(false);
  const table = catalog.data?.tables.find(t => t.schema + '.' + t.name === relation);
  const refresh = () => { void client.invalidateQueries(); };

  function toggle(index: Index) {
    const change = () => void ctx.perform(async () => {
      const result = await api<Index[]>(root + '/toggle', 'POST', { schema: index.schema, name: index.name, enabled: !index.enabled });
      client.setQueryData(['indexes', root], result);
      ctx.notify(index.enabled ? 'Index disabled in sandbox.' : 'Index restored in sandbox.');
    });
    if (index.enabled) ctx.confirm('Disable ' + index.name, 'The index will be dropped in this sandbox. Its definition will be saved for restoration.', change);
    else change();
  }
  function remove(index: Index) {
    ctx.confirm('Remove ' + index.name, 'This drops the index from the sandbox and deletes its saved restoration definition.', () => {
      void ctx.perform(async () => {
        const result = await api<Index[]>(root + '/remove', 'POST', { schema: index.schema, name: index.name });
        client.setQueryData(['indexes', root], result);
        ctx.notify('Index removed from sandbox.');
      });
    });
  }

  async function buildIndex(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = input(e);
    if (!table) return;
    const result = await ctx.perform(() => api<{ indexBuildMs: number }>(root + '/build', 'POST', {
      schema: table.schema, table: table.name, name: values.name, method, columns, include: listNames(String(values.include ?? '')),
      unique: values.unique === 'on', trigram: values.trigram === 'on', expression: values.expression,
      predicate: values.predicateColumn ? { column: values.predicateColumn, operator: values.predicateOperator, value: values.predicateValue } : {},
    }));
    if (result) { setBuild(false); refresh(); ctx.notify('Index built in ' + num(result.indexBuildMs) + ' ms.'); }
  }
  function editConfig(record: Resource | null) {
    setConfigName(record?.name ?? '');
    setDefinitions(JSON.stringify(record?.data.definitions ?? [], null, 2));
    setDisabled(Array.isArray(record?.data.disabled) ? record.data.disabled.map(String) : []);
    setRemoveOptional(Boolean(record?.data.removeOptional));
    setEditing(record);
  }
  async function saveConfig() {
    const response = await ctx.perform(() => api(ctx.prefix + '/resources/index-config' + (editing ? '/' + editing.id : ''),
      editing ? 'PUT' : 'POST', { name: configName, definitions: JSON.parse(definitions), disabled, removeOptional }));
    if (response) { setEditing(undefined); refresh(); }
  }

  return <AwaitProject ctx={ctx}><div className="workspace-grid">
    <Card title="Sandbox indexes" note="Enabled state reflects the actual PostgreSQL catalog."
      actions={<div className="filter-line"><Select label="" value={ctx.sandboxId} onChange={ctx.setSandbox} options={sandboxOptions(sandboxes)}/>
        <Action primary onClick={() => { setRelation(''); setColumns([]); setBuild(true); }} disabled={ctx.sandbox?.data.status !== 'running'}><Plus size={15}/> Build index</Action></div>}>
      {error && <div className="inline-error">{error.message}</div>}
      {isFetching && <p className="footnote">Reading indexes…</p>}
      {indexes.length ? <div className="table-scroll"><table><thead><tr><th>Index</th><th>Table</th><th>Type</th><th>Size</th><th>Scans</th><th>Enabled</th><th><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{indexes.map(index => <tr key={index.schema + '.' + index.name}><td><details>
          <summary className="mono">{index.name} {index.protected ? ' · protected' : index.unique ? ' · unique' : ''}</summary><pre>{index.definition}</pre>
        </details></td><td className="mono">{index.schema}.{index.table}</td><td>{index.method}</td><td>{bytes(index.bytes)}</td>
          <td>{num(index.scans, 0)}</td><td><Switch size="small" checked={index.enabled} onChange={() => toggle(index)}
            disabled={index.protected || ctx.busy} inputProps={{ 'aria-label': 'Enable ' + index.name }}/></td><td>{!index.protected && <button className="icon small"
            aria-label={'Remove ' + index.name} onClick={() => remove(index)}><Trash2 size={15}/></button>}</td></tr>)}</tbody></table></div>
        : <Empty title="No indexes to show" text="Select a running sandbox with tables or create an index using the builder."/>}
    </Card>
    <Card title="Named configurations" note="Use named variants in the benchmark matrix."
      actions={<Action onClick={() => editConfig(null)}><Plus size={15}/> New configuration</Action>}>
      {configs.length ? <div className="resource-list">{configs.map(item => <div className="resource-row" key={item.id}>
        <span className="resource-icon"><Layers3 size={17}/></span><span><b>{item.name}</b><small>{item.data.removeOptional ? 'Drops optional indexes' : 'Keeps baseline indexes unless excluded'}</small></span>
        <button className="icon" aria-label={'Edit ' + item.name} onClick={() => editConfig(item)}><Pencil size={16}/></button>
        <button className="icon" aria-label={'Delete ' + item.name} onClick={() => removeResource(ctx, 'index-config', item, refresh)}><Trash2 size={16}/></button>
      </div>)}</div> : <Empty title="No saved configurations" text="Save baseline exclusions and candidate definitions for comparison."/>}
    </Card>
  </div>
  {build && <Modal title="Build index" onClose={() => setBuild(false)}><form className="form-body" onSubmit={e => void buildIndex(e)}>
    <NativeField label="Index name" name="name" required/>
    <div className="fields two"><Select label="Table" value={relation} onChange={value => { setRelation(value); setColumns([]); }}
      options={[[ '', 'Select table' ], ...(catalog.data?.tables ?? []).filter(t => ['r','p','m'].includes(t.kind))
        .map(t => [t.schema + '.' + t.name, t.schema + '.' + t.name] as [string, string])]}/>
      <Select label="Index method" value={method} onChange={setMethod} options={indexMethods}/></div>
    {table && <fieldset><legend>Index columns in selected order</legend>{table.columns.map(col => <Toggle key={col.name} label={col.name}
      checked={columns.includes(col.name)} onChange={checked => setColumns(old => checked ? [...old, col.name] : old.filter(name => name !== col.name))}/>)}
      <p className="mono">{columns.join(' → ')}</p></fieldset>}
    <div className="fields two"><NativeField label="Included columns (comma separated)" name="include"/>
      <NativeSelect label="Expression" name="expression" options={[[ '', 'None' ], [ 'lower', 'lower(column)' ], [ 'upper', 'upper(column)' ]]}/></div>
    <label className="toggle-line"><input type="checkbox" name="unique"/>Unique index</label>
    {method === 'gin' && <label className="toggle-line"><input type="checkbox" name="trigram"/>Trigram operator class (pg_trgm)</label>}
    <details><summary>Partial index predicate</summary><div className="detail-fields">
      <NativeSelect label="Predicate column" name="predicateColumn" options={[[ '', 'None' ], ...(table?.columns ?? []).map(c => [c.name, c.name] as [string, string])]}/>
      <NativeSelect label="Operator" name="predicateOperator" options={[[ 'is not null', 'IS NOT NULL' ], [ 'is null', 'IS NULL' ], [ '=', 'Equals' ]]}/>
      <NativeField label="Predicate value for Equals" name="predicateValue"/></div></details>
    <div className="form-actions"><Action primary busy={ctx.busy} type="submit" disabled={!columns.length}>Create index</Action></div>
  </form></Modal>}
  {editing !== undefined && <Modal title={editing ? 'Edit index configuration' : 'New index configuration'} onClose={() => setEditing(undefined)}>
    <form className="form-body" onSubmit={e => { e.preventDefault(); void saveConfig(); }}>
      <Field label="Configuration name" value={configName} onChange={setConfigName} required/>
      <Toggle label="Disable all optional baseline indexes" checked={removeOptional} onChange={setRemoveOptional}/>
      {!removeOptional && Boolean(indexes.length) && <fieldset><legend>Excluded optional indexes</legend>
        {indexes.filter(i => !i.protected).map(index => { const id = index.schema + '.' + index.name; return <Toggle key={id} label={id}
          checked={disabled.includes(id)} onChange={checked => setDisabled(old => checked ? [...old, id] : old.filter(s => s !== id))}/>; })}</fieldset>}
      <Text label='Candidate definitions (JSON array)' value={definitions} onChange={setDefinitions} rows={5} mono/>
      <p className="footnote mono">{'[{"name":"customer_name_idx","table":"customers","method":"btree","columns":["name"]}]'}</p>
      <div className="form-actions"><Action primary type="submit" busy={ctx.busy}><Save size={15}/> Save configuration</Action></div>
    </form></Modal>}
  </AwaitProject>;
}

export function LoadView({ ctx }: { ctx: WorkspaceContext }) {
  const { data: sandboxes = [] } = useResources(ctx.prefix, 'sandbox');
  const { data: jobs = [] } = useResources(ctx.prefix, 'job');
  const { data: queries = [] } = useResources(ctx.prefix, 'query');
  const client = useQueryClient();
  const [queryIds, setQueryIds] = useState<string[]>([]);
  const [commit, setCommit] = useState(false);
  const [selected, setSelected] = useState('');
  const testJobs = jobs.filter(job => job.data.type === 'load');
  const current = testJobs.find(job => job.id === selected) ?? testJobs.find(job => job.data.status === 'completed');
  const report = useQuery({ queryKey: ['load-result', ctx.prefix, current?.id],
    queryFn: () => api<Record<string, number | boolean>>(ctx.prefix + '/loads/' + current?.id),
    enabled: Boolean(ctx.prefix && current?.data.status === 'completed') });
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fields = input(e);
    const launch = async () => {
      const job = await ctx.perform(() => api<Resource>(ctx.prefix + '/loads', 'POST', {
        name: fields.name, sandboxId: ctx.sandboxId, queryIds, commit, duration: Number(fields.duration),
        ramp: Number(fields.ramp), concurrency: Number(fields.concurrency), timeout: Number(fields.timeout),
      }));
      if (job) { setSelected(job.id); ctx.track(job); void client.invalidateQueries(); }
    };
    if (commit) ctx.confirm('Commit load-test writes', 'Write queries will commit changes in the selected sandbox for the duration of this test.', () => void launch());
    else void launch();
  }
  const metrics: [string, string, string?][] = [
    ['throughput', 'Successful queries per second'], ['successful', 'Successful queries'], ['errors', 'Errors'], ['errorPercent', 'Error rate', '%'],
    ['p50', 'P50 client latency', ' ms'], ['p95', 'P95 client latency', ' ms'], ['p99', 'P99 client latency', ' ms'],
    ['deadlocks', 'Deadlocks'], ['lockTimeouts', 'Lock timeouts'], ['peakWaitingForLocks', 'Peak sampled sessions waiting for locks'],
    ['sampleSize', 'Latency sample size'], ['concurrency', 'Clients'],
  ];
  return <AwaitProject ctx={ctx}><div className="workspace-grid">
    <Card title="Load configuration" note="Clients rotate through selected queries. Ramp-up is excluded from timing summaries.">
      <form className="form-body" onSubmit={submit}>
        <div className="fields two"><NativeField label="Run name" name="name" initial="Sandbox load test" required/>
          <Select label="Sandbox" value={ctx.sandboxId} onChange={ctx.setSandbox} options={sandboxOptions(sandboxes)}/></div>
        <div className="fields four"><NativeField label="Clients" name="concurrency" type="number" initial={4} min={1} max={8}/>
          <NativeField label="Duration (s)" name="duration" type="number" initial={15} min={2} max={120}/>
          <NativeField label="Ramp-up (s)" name="ramp" type="number" initial={3} min={0} max={30}/>
          <NativeField label="Query timeout (s)" name="timeout" type="number" initial={5} min={1} max={30}/></div>
        <fieldset><legend>Saved SQL queries</legend>{queries.length ? queries.map(query =>
          <Toggle key={query.id} label={query.name} checked={queryIds.includes(query.id)}
            onChange={checked => setQueryIds(old => checked ? [...old, query.id] : old.filter(id => id !== query.id))}/>) : <p>Save SQL in Query Workspace first.</p>}</fieldset>
        <Toggle label="Commit write queries to the sandbox" checked={commit} onChange={setCommit}/>
        <div className="form-actions"><Action primary type="submit" busy={ctx.busy} disabled={!ctx.sandboxId || !queryIds.length}><Play size={15}/> Start load test</Action></div>
      </form>
    </Card>
    <Card title="Load results" note={current ? String(current.data.message) : 'Measurements from completed load tests.'}
      actions={testJobs.length > 0 && <Select label="" value={current?.id ?? ''} onChange={setSelected}
        options={[[ '', 'Select a run' ], ...testJobs.map(job => [job.id, String(job.data.label) + ' · ' + status(job)] as [string, string])]}/>}>
      {report.data ? <div className="metric-grid">{metrics.map(([key, label, unit]) =>
        <div key={key}><span>{label}</span><b>{typeof report.data[key] === 'number' ? num(Number(report.data[key])) + (unit ?? '') : '—'}</b></div>)}</div>
        : <Empty title="No completed load results selected" text="Start a concurrent workload or select a previous run."/>}
    </Card>
  </div></AwaitProject>;
}
