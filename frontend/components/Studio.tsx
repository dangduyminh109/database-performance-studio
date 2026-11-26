'use client';

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import {
  Activity, ArrowDownFromLine, Boxes, ChartNoAxesCombined, Check, ChevronDown, ChevronRight, ChevronsUpDown,
  CircleHelp, CodeXml, Database, FlaskConical, FolderKanban, GitFork, Grid2X2, Layers3,
  PanelLeftClose, PanelLeftOpen, Play, Plug, Settings2, Sparkles, Sun, Moon, Waypoints, X,
} from 'lucide-react';
import { active, api, navEn, navVi, sampleReport, status, type Project, type Report, type Resource, type View } from './data';
import { Action, Card, Empty, Modal } from './ui';
import Reports from './Reports';
import { LocaleProvider } from './locale';
import { ConnectionsView, GeneratorView, IndexView, LoadView, ProjectsView, SandboxView, SchemaView, SqlView } from './Workspace';
import BenchmarkForm from './BenchmarkForm';

const navigation: { title: string; vi: string; items: [View, typeof Database][] }[] = [
  { title: 'WORKSPACE', vi: 'KHÔNG GIAN', items: [['overview', Grid2X2], ['projects', FolderKanban], ['connections', Plug], ['schema', GitFork], ['sandboxes', Boxes]] },
  { title: 'EXPERIMENTS', vi: 'THỬ NGHIỆM', items: [['generator', Layers3], ['sql', CodeXml], ['indexes', Waypoints], ['benchmark', FlaskConical], ['load', Activity]] },
  { title: 'ANALYSIS', vi: 'PHÂN TÍCH', items: [['plans', GitFork], ['optimization', Sparkles], ['reports', ChartNoAxesCombined]] },
];

export type WorkspaceContext = {
  project?: Project; prefix: string; projects: Project[]; sandbox?: Resource; sandboxId: string;
  setSandbox: (id: string) => void; setProject: (id: string) => void; go: (view: View) => void;
  notify: (message: string) => void; track: (job: Resource) => void; busy: boolean;
  perform: <T>(task: () => Promise<T>) => Promise<T | undefined>;
  confirm: (title: string, text: string, action: () => void) => void;
  dark: boolean;
};

export function useResources(prefix: string, kind: string) {
  return useQuery({ queryKey: ['resources', prefix, kind], queryFn: () => api<Resource[]>(prefix + '/resources/' + kind), enabled: Boolean(prefix) });
}

export function sandboxOptions(resources: Resource[]): [string, string][] {
  return [['', 'Select sandbox'], ...resources.map(s => [s.id, s.name + (s.data.status === 'running' ? '' : ' · ' + s.data.status)] as [string, string])];
}

function App() {
  const query = useQueryClient();
  const [view, setView] = useState<View>('benchmark');
  const [projectId, setProjectId] = useState('');
  const [sandboxId, setSandbox] = useState('');
  const [selectedRun, setSelectedRun] = useState('');
  const [job, setJob] = useState<Resource>();
  const [dark, setDark] = useState(true);
  const [language, setLanguage] = useState('en');
  const [collapsed, setCollapsed] = useState(false);
  const [switcher, setSwitcher] = useState(false);
  const [modal, setModal] = useState<'run' | 'help'>();
  const [confirmation, setConfirmation] = useState<{ title: string; text: string; action: () => void }>();
  const [notification, setNotification] = useState('');
  const [busy, setBusy] = useState(false);
  const [demoConfig, setDemoConfig] = useState<Record<string, unknown>>();
  const [comparePreset, setComparePreset] = useState<Record<string, unknown>>();
  const projectsQuery = useQuery({ queryKey: ['projects'], queryFn: () => api<Project[]>('/projects'), retry: false });
  const healthQuery = useQuery({ queryKey: ['health'], queryFn: () => api<{ status: string }>('/health'), retry: false, refetchInterval: 15000 });
  const projects = projectsQuery.data ?? [];
  const project = projects.find(item => item.id === projectId);
  const prefix = project ? '/projects/' + project.id : '';
  const sandboxes = useResources(prefix, 'sandbox').data ?? [];
  const sandbox = sandboxes.find(item => item.id === sandboxId);
  const jobs = useResources(prefix, 'job').data ?? [];
  const runs = jobs.filter(item => item.data.type === 'benchmark');
  const reportId = selectedRun || runs.find(item => ['completed', 'failed', 'cancelled'].includes(status(item)))?.id;
  const reportQuery = useQuery({ queryKey: ['report', prefix, reportId], queryFn: () => api<Report>(prefix + '/reports/' + reportId),
    enabled: Boolean(prefix && reportId) });
  const report = project ? reportQuery.data : sampleReport;
  const labels = language === 'vi' ? navVi : navEn;
  const vi = language === 'vi';
  const theme = useMemo(() => createTheme({ palette: { mode: dark ? 'dark' : 'light', primary: { main: '#997bf5' } },
    typography: { fontFamily: 'Inter, system-ui, sans-serif' } }), [dark]);

  useEffect(() => {
    setDark(window.localStorage.getItem('dps-theme') !== 'light');
    setLanguage(window.localStorage.getItem('dps-language') ?? 'en');
    setProjectId(window.localStorage.getItem('dps-project') ?? '');
    const hash = window.location.hash.slice(1) as View;
    if (Object.hasOwn(navEn, hash)) setView(hash);
    const change = () => {
      const hash = window.location.hash.slice(1) as View;
      if (Object.hasOwn(navEn, hash)) setView(hash);
    };
    window.addEventListener('hashchange', change);
    return () => window.removeEventListener('hashchange', change);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  }, [dark]);

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  useEffect(() => {
    if (sandboxId && sandboxes.some(s => s.id === sandboxId)) return;
    if (sandboxes.length) setSandbox((sandboxes.find(s => s.data.status === 'running') ?? sandboxes[0]).id);
  }, [sandboxId, sandboxes]);

  useEffect(() => {
    if (!notification) return;
    const timer = window.setTimeout(() => setNotification(''), 6500);
    return () => window.clearTimeout(timer);
  }, [notification]);

  const jobId = job?.id;
  const jobIsActive = job ? active(job) : false;
  useEffect(() => {
    if (!jobId || !jobIsActive || !prefix) return;
    const path = prefix + '/jobs/' + jobId;
    const stream = new EventSource('/api' + path + '/events');
    let finished = false;
    const update = (item: Resource) => {
      if (finished) return;
      setJob(item);
      if (active(item)) return;
      finished = true;
      stream.close();
      void query.invalidateQueries();
      if (item.data.type === 'benchmark') setSelectedRun(item.id);
      if (item.data.status === 'failed' || item.data.status === 'cancelled') {
        setNotification(String(item.data.message));
        setDemoConfig(undefined);
      }
    };
    stream.addEventListener('progress', (event: MessageEvent<string>) => update(JSON.parse(event.data) as Resource));
    const check = window.setInterval(() => { void api<Resource>(path).then(update).catch(() => {}); }, 2500);
    return () => { finished = true; stream.close(); window.clearInterval(check); };
  }, [jobId, jobIsActive, prefix, query]);

  useEffect(() => {
    if (!demoConfig || !prefix || job?.data.type !== 'sandbox' || job.data.status !== 'completed') return;
    const config = demoConfig;
    setDemoConfig(undefined);
    void api<Resource>(prefix + '/benchmarks', 'POST', config).then(setJob).catch((error: Error) => setNotification(error.message));
  }, [demoConfig, job, prefix]);

  function go(next: View) { setView(next); window.location.hash = next; }
  function chooseProject(id: string) {
    setProjectId(id); setSandbox(''); setSelectedRun(''); setJob(undefined); setDemoConfig(undefined); setSwitcher(false);
    window.localStorage.setItem('dps-project', id);
  }
  function changeTheme(isDark: boolean) { setDark(isDark); window.localStorage.setItem('dps-theme', isDark ? 'dark' : 'light'); }
  function changeLanguage(value: string) { setLanguage(value); window.localStorage.setItem('dps-language', value); }
  async function perform<T>(task: () => Promise<T>): Promise<T | undefined> {
    setBusy(true);
    try { return await task(); }
    catch (error) { setNotification(error instanceof Error ? error.message : 'Operation failed.'); return undefined; }
    finally { setBusy(false); }
  }
  async function demo() {
    const created = await perform(() => api<{ project: Project; sandbox: Resource; job: Resource; benchmark: Record<string, unknown> }>('/demo', 'POST'));
    if (!created) return;
    query.setQueryData<Project[]>(['projects'], old => [created.project, ...(old ?? [])]);
    chooseProject(created.project.id); setSandbox(created.sandbox.id); setJob(created.job); setDemoConfig(created.benchmark);
    setModal(undefined); go('benchmark'); void query.invalidateQueries();
  }
  function startBenchmark() { setComparePreset(undefined); setModal('run'); }
  async function verify(index: Record<string, unknown>) {
    if (!report || !project) return;
    const config = report.job.data.configuration as Record<string, unknown> | undefined;
    if (!config) return;
    const variant = await perform(() => api<Resource>(prefix + '/resources/index-config', 'POST', {
      name: 'Candidate ' + new Date().toISOString().slice(11, 23), definitions: [index],
    }));
    if (!variant) return;
    const ids = Array.isArray(config.indexConfigIds) ? [...config.indexConfigIds] : [];
    if (!ids.length) {
      const baseline = await perform(() => api<Resource>(prefix + '/resources/index-config', 'POST', { name: 'Original ' + new Date().toISOString().slice(11, 23) }));
      if (!baseline) return;
      ids.push(baseline.id);
    }
    setComparePreset({ ...config, name: 'Optimization verification', indexConfigIds: [...ids, variant.id] });
    void query.invalidateQueries();
    setModal('run');
  }

  const ctx: WorkspaceContext = { project, prefix, projects, sandbox, sandboxId, setSandbox, setProject: chooseProject, go,
    notify: setNotification, track: setJob, perform, busy, dark, confirm: (title, text, action) => setConfirmation({ title, text, action }) };
  let content: ReactNode;
  if (view === 'benchmark' || view === 'reports' || view === 'plans' || view === 'optimization') {
    content = <Reports key={view + (report?.job.id ?? '')} view={view} report={report} dark={dark} sample={!project} prefix={prefix} runs={runs}
      reportId={reportId} onRun={startBenchmark} onRunChange={setSelectedRun} onVerify={index => void verify(index)} vi={vi} />;
  } else if (view === 'projects') content = <ProjectsView ctx={ctx} />;
  else if (view === 'connections') content = <ConnectionsView ctx={ctx} />;
  else if (view === 'sandboxes') content = <SandboxView ctx={ctx} />;
  else if (view === 'schema') content = <SchemaView ctx={ctx} />;
  else if (view === 'generator') content = <GeneratorView ctx={ctx} />;
  else if (view === 'sql') content = <SqlView ctx={ctx} />;
  else if (view === 'load') content = <LoadView ctx={ctx} />;
  else if (view === 'indexes') content = <IndexView ctx={ctx} />;
  else if (view === 'settings') content = <div className="workspace-grid">
    <Card title={vi ? 'Giao diện' : 'Appearance'} note={vi ? 'Thay đổi giao diện và ngôn ngữ.' : 'Customize the local workspace.'}>
      <div className="form-body"><div className="setting"><div><b>{vi ? 'Chủ đề' : 'Theme'}</b><p>{vi ? 'Được lưu trên trình duyệt này.' : 'Saved in this browser.'}</p></div>
        <Action onClick={() => changeTheme(!dark)}>{dark ? <Sun size={15}/> : <Moon size={15}/>} {dark ? 'Light' : 'Dark'}</Action></div>
      <div className="setting"><div><b>{vi ? 'Ngôn ngữ' : 'Language'}</b><p>English / Tiếng Việt</p></div>
        <Action onClick={() => changeLanguage(vi ? 'en' : 'vi')}>{vi ? 'English' : 'Tiếng Việt'}</Action></div></div>
    </Card>
  </div>;
  else content = <div className="workspace-grid"><Card title="Recent activity" note="Jobs and benchmarks in this project.">
    {jobs.length ? <div className="resource-list">{jobs.slice(0, 12).map(item => <button key={item.id} className="resource-row"
      onClick={() => { setJob(item); if (item.data.type === 'benchmark') { setSelectedRun(item.id); go('reports'); } }}>
      <Activity size={17}/><span><b>{String(item.data.label)}</b><small>{new Date(item.createdAt).toLocaleString()}</small></span>
      <span className={'badge ' + status(item)}>{status(item)}</span></button>)}</div>
      : <Empty title="No recent activity" text="Create a project or run a benchmark to populate this workspace."
        action={<Action onClick={startBenchmark}><Play size={15}/> Run benchmark</Action>}/>}
  </Card></div>;

  const descriptions: Partial<Record<View, string>> = {
    benchmark: vi ? 'Chạy và so sánh hiệu năng truy vấn theo dữ liệu và cấu hình chỉ mục.' : 'Run and compare query performance across dataset sizes and index configurations.',
    load: 'Run concurrent saved SQL workloads and review contention and latency.',
    overview: 'Track recent runs and activity across your project.',
    projects: 'Organize connections, sandboxes and experiments into projects.',
    connections: 'Register and inspect PostgreSQL sources with read-only access.',
    schema: 'Browse PostgreSQL objects, relationships and catalog statistics.',
    sandboxes: 'Provision and manage isolated PostgreSQL environments.',
    generator: 'Create reproducible synthetic datasets in an isolated sandbox.',
    sql: 'Write, save and explain SQL against your sandbox.',
    indexes: 'Inspect, build and switch PostgreSQL indexes in your sandbox.',
    plans: 'Inspect or compare PostgreSQL execution plans.',
    optimization: 'Review evidence from measured runs and verify candidate indexes.',
    reports: 'Review historical benchmark results and export raw measurements.',
    settings: vi ? 'Tùy chỉnh không gian làm việc cục bộ.' : 'Manage local workspace preferences.',
  };

  return <LocaleProvider language={language}><ThemeProvider theme={theme}><div className={'studio ' + (collapsed ? 'collapsed' : '')}>
    <aside className="sidebar">
      <div className="brand"><div className="brand-icon"><Database size={22} strokeWidth={1.8}/></div>
        <div className="brand-copy"><b>Performance Studio</b><small>DATABASE ENGINEERING</small></div></div>
      <div className="project-wrap">
        <button className="project-switch" aria-expanded={switcher} aria-label="Switch project" onClick={() => setSwitcher(!switcher)}>
          <span className="project-icon"><Boxes size={17}/></span><span className="project-copy"><b>{project?.name ?? 'Commerce Lab'}</b>
          <small>{project ? (vi ? 'Dự án cục bộ' : 'Local project') : (vi ? 'Không gian mẫu' : 'Sample workspace')}</small></span><ChevronsUpDown size={15} className="project-chevron"/>
        </button>
        {switcher && <div className="switch-menu"><button onClick={() => chooseProject('')}>Commerce Lab <small>Sample</small></button>
          {projects.map(item => <button key={item.id} onClick={() => chooseProject(item.id)}>{item.name}{item.id === projectId && <Check size={14}/>}</button>)}
          <button onClick={() => { setSwitcher(false); go('projects'); }}>Manage projects</button></div>}
      </div>
      <nav aria-label="Workspace">{navigation.map(group => <div className="nav-group" key={group.title}>
        <h2>{vi ? group.vi : group.title}</h2>{group.items.map(([item, Icon]) =>
          <a href={'#' + item} onClick={e => { e.preventDefault(); go(item); }} key={item} title={labels[item]} aria-current={item === view ? 'page' : undefined}>
            <Icon size={17} strokeWidth={1.65}/><span>{labels[item]}</span></a>)}</div>)}</nav>
      <div className="sidebar-bottom"><a href="#settings" onClick={e => { e.preventDefault(); go('settings'); }}
        className={view === 'settings' ? 'current' : ''} title={labels.settings}><Settings2 size={17}/><span>{labels.settings}</span></a>
        <div className="environment"><span className={'status-dot ' + (healthQuery.isSuccess || !project ? 'green' : '')}/><span>{healthQuery.isSuccess ? 'Local API online' : project ? 'Local API offline' : 'Sample workspace'}</span><span className="version">v0.1</span></div>
      </div>
    </aside>
    <div className="surface">
      <header className="topbar">
        <div className="breadcrumbs"><button className="icon panel-toggle" title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => setCollapsed(!collapsed)}>
          {collapsed ? <PanelLeftOpen size={18}/> : <PanelLeftClose size={18}/>}</button>
          <span className="breadcrumb-project">{project?.name ?? 'Commerce Lab'}</span><ChevronRight size={14}/><b>{labels[view]}</b></div>
        <div className="top-right"><button className="lang-button" title="Change language" onClick={() => changeLanguage(vi ? 'en' : 'vi')}>{vi ? 'VI' : 'EN'} <ChevronDown size={12}/></button>
          <span className="top-divider"/><button className="icon" aria-label="Toggle theme" title="Toggle theme" onClick={() => changeTheme(!dark)}>{dark ? <Sun size={17}/> : <Moon size={17}/>}</button>
          <button className="icon" aria-label="Help" title="Help" onClick={() => setModal('help')}><CircleHelp size={17}/></button>
          <div className="avatar" title="Local workspace">DS</div></div>
      </header>
      <main className="main">
        <div className="page-heading">
          <div><div className="eyebrow"><span/> {view === 'benchmark' ? (vi ? 'THỬ NGHIỆM' : 'EXPERIMENTS') : vi ? 'KHÔNG GIAN' : 'WORKSPACE'}</div>
            <h1>{labels[view]}</h1><p>{descriptions[view]}</p></div>
          {(view === 'benchmark' || view === 'reports') && <div className="heading-actions">
            {view === 'benchmark' && <Action onClick={() => go('reports')}><ArrowDownFromLine size={16}/> {vi ? 'Kết quả' : 'View reports'}</Action>}
            <Action primary onClick={startBenchmark}><Play size={15} fill="currentColor"/> {vi ? 'Chạy đo hiệu năng' : 'Run benchmark'}</Action>
          </div>}
        </div>
        {job && <div className={'job-banner ' + status(job)}><Activity size={18}/><div><b>{String(job.data.label)}</b><span>{String(job.data.message)}</span></div>
          {active(job) ? <><progress max={100} value={Number(job.data.progress)} aria-label="Job progress"/><Action onClick={() => void perform(() => api(prefix + '/jobs/' + job.id + '/cancel', 'POST'))}>Cancel</Action></>
            : <button className="icon" aria-label="Dismiss job" onClick={() => setJob(undefined)}><X size={17}/></button>}</div>}
        {content}
      </main>
    </div>
    {modal === 'run' && <Modal title={project ? 'Configure benchmark' : 'Run a PostgreSQL benchmark'} onClose={() => setModal(undefined)}>
      {project ? <BenchmarkForm ctx={ctx} preset={comparePreset} onDone={() => { setModal(undefined); go('benchmark'); }}/>
        : <div className="form-body demo-dialog"><div className="empty-icon"><FlaskConical size={22}/></div><p>Start a disposable Commerce Lab sandbox, generate customers and orders, and measure an email lookup with and without a B-tree expression index.</p>
          <div className="form-actions"><Action onClick={() => { setModal(undefined); go('projects'); }}>Manage projects</Action>
            <Action primary busy={busy} onClick={() => void demo()}><Play size={15}/> Run demo benchmark</Action></div></div>}
    </Modal>}
    {modal === 'help' && <Modal title="Database Performance Studio" onClose={() => setModal(undefined)}><div className="form-body">
      <p>Choose a project, connect to PostgreSQL, and provision a sandbox. Save queries and index configurations, then compare them in Benchmark Lab.</p>
      <p>The sample workspace contains illustrative values. Running the demo creates a real, isolated PostgreSQL benchmark.</p>
      <div className="form-actions"><Action primary onClick={() => setModal(undefined)}>Done</Action></div></div></Modal>}
    {confirmation && <Modal title={confirmation.title} onClose={() => setConfirmation(undefined)}>
      <div className="form-body"><p>{confirmation.text}</p><div className="form-actions"><Action onClick={() => setConfirmation(undefined)}>Cancel</Action>
        <Action primary onClick={() => { const action = confirmation.action; setConfirmation(undefined); action(); }}>Confirm</Action></div></div></Modal>}
    {notification && <div role="status" className="toast"><span>{notification}</span><button className="icon" aria-label="Dismiss message" onClick={() => setNotification('')}><X size={16}/></button></div>}
  </div></ThemeProvider></LocaleProvider>;
}

export default function Studio() {
  const [query] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 8000 } } }));
  return <QueryClientProvider client={query}><App/></QueryClientProvider>;
}
