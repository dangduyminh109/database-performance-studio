'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, ArrowDown, ArrowUpRight, ChartNoAxesCombined, Check, ChevronRight, Clock3, Database, Download, FlaskConical, GitFork, Info, Layers3, Play, Sparkles, Zap } from 'lucide-react';
import { api, bytes, compact, num, status, type Report, type Resource, type Scenario } from './data';
import Chart, { colors } from './Chart';
import { PlanGraph } from './Graphs';
import { Action, Card, Empty, Select } from './ui';

const modes = ['Overview', 'Latency distribution', 'Index storage', 'Throughput'] as const;
type Mode = typeof modes[number];
type ReportView = 'benchmark' | 'reports' | 'plans' | 'optimization';

function Stat({ icon, className, label, value, unit, children }: {
  icon: ReactNode; className: string; label: string; value: string; unit?: string; children: ReactNode;
}) {
  return <div className="stat card"><div className="stat-top"><span className={'stat-icon ' + className}>{icon}</span><span>{label}</span></div>
    <div className="stat-number">{value}{unit && <small>{unit}</small>}</div><div className="stat-caption">{children}</div></div>;
}

export default function Reports({ view, report, dark, sample, prefix, runs, reportId, onRun, onRunChange, onVerify, vi }: {
  view: ReportView; report?: Report; dark: boolean; sample: boolean; prefix: string; runs: Resource[]; reportId?: string;
  onRun: () => void; onRunChange: (id: string) => void; onVerify: (index: Record<string, unknown>) => void; vi: boolean;
}) {
  const [mode, setMode] = useState<Mode>('Overview');
  const [query, setQuery] = useState('');
  const [scale, setScale] = useState('');
  const [selected, setSelected] = useState<Scenario>();
  const [left, setLeft] = useState('0');
  const [right, setRight] = useState('1');
  const [raw, setRaw] = useState(false);
  const [previousRun, setPreviousRun] = useState('');
  const past = useQuery({ queryKey: ['comparison', prefix, previousRun],
    queryFn: () => api<Report>(prefix + '/reports/' + previousRun),
    enabled: Boolean(view === 'reports' && prefix && previousRun) });
  const scenarios = useMemo(() => report?.scenarios ?? [], [report]);
  const queries = [...new Set(scenarios.map(s => s.summary.query))];
  const currentQuery = queries.includes(query) ? query : queries[0];
  const data = useMemo(() => scenarios.filter(s => s.summary.query === currentQuery), [scenarios, currentQuery]);
  const sizes = [...new Set(data.map(s => s.summary.size))].sort((a, b) => a - b);
  const names = [...new Set(data.map(s => s.summary.indexConfig))];
  const largest = sizes[sizes.length - 1] ?? 0;
  const high = data.filter(s => s.summary.size === largest);
  const best = high.reduce<Scenario | undefined>((value, next) => !value || next.summary.mean < value.summary.mean ? next : value, undefined);
  const reference = high.find(s => /^baseline$/i.test(s.summary.indexConfig)) ?? high[0];
  const factor = best && reference && best.summary.mean > 0 ? reference.summary.mean / best.summary.mean : 1;
  const improvement = factor > 0 ? (1 - 1 / factor) * 100 : 0;
  const queryTabs: [string, string][] = queries.map(name => [name, name]);
  const measured = scenarios.reduce((sum, s) => sum + s.summary.count, 0);
  const label = report ? String(report.job.data.label) : '';
  const ordered = useMemo(() => [...data].sort((a, b) => b.summary.size - a.summary.size || names.indexOf(a.summary.indexConfig) - names.indexOf(b.summary.indexConfig)), [data, names]);
  const rows = scale ? ordered.filter(s => String(s.summary.size) === scale) : ordered;
  const reportChoice = runs.length > 0 && <Select label="" value={reportId ?? ''} onChange={onRunChange} options={[
    ['', 'Select run'], ...runs.map(run => [run.id, String(run.data.label) + ' · ' + status(run) + ' · ' + new Date(run.createdAt).toLocaleDateString()] as [string, string]),
  ]}/>;
  const sampleNotice = sample && <div className="sample-banner"><div className="sample-icon"><FlaskConical size={17}/></div>
    <div><b>{vi ? 'Kết quả đo mẫu' : 'Sample benchmark'}</b><span>{vi ? 'Dữ liệu minh họa cho quy trình thử nghiệm.' : 'Illustrative results for the Commerce Lab workspace.'}</span></div>
    <button onClick={onRun}>{vi ? 'Chạy thử nghiệm' : 'Run this experiment'} <ChevronRight size={15}/></button></div>;

  if (!scenarios.length) return <div className="report-stack">
    {reportChoice && <div className="filter-line">{reportChoice}</div>}
    <Card><Empty title={report ? 'No recorded scenarios' : 'No benchmark results yet'}
      text={report ? `This run is ${status(report.job)}. Completed scenarios will appear here.` : 'Select a sandbox, saved query and dataset size to run your first benchmark.'}
      action={<Action primary onClick={onRun}><Play size={15}/> Run benchmark</Action>}/></Card>
  </div>;

  if (view === 'plans' || selected) {
    const options: [string, string][] = data.map((s, i) => [String(i), s.summary.indexConfig + ' · ' + num(s.summary.size, 0) + ' rows']);
    const selectA = selected ? String(Math.max(data.indexOf(selected), 0)) : left;
    return <div className="report-stack">
      {sampleNotice}
      <div className="filter-line">{selected && <Action onClick={() => setSelected(undefined)}>Back to results</Action>}{reportChoice}
        {queries.length > 1 && <Select label="" value={currentQuery} onChange={value => { setQuery(value); setLeft('0'); setRight('1'); }} options={queryTabs}/>}</div>
      <div className="plans-grid"><Card title="Plan A" actions={<Select label="" options={options} value={selectA}
        onChange={value => { setSelected(undefined); setLeft(value); }}/>}>
        <PlanGraph key={selectA} root={data[Number(selectA)]?.plan?.[0]?.Plan} dark={dark}/></Card>
        <Card title="Plan B" actions={<Select label="" options={options} value={data[Number(right)] ? right : '0'} onChange={setRight}/>}>
          <PlanGraph key={right} root={(data[Number(right)] ?? data[0])?.plan?.[0]?.Plan} dark={dark}/></Card></div>
      <div className="footnote"><Info size={14}/> Actual time and rows are per loop. Node time is inclusive of its children. Parent and child times should not be added.</div>
    </div>;
  }

  if (view === 'optimization') {
    const findings = scenarios.flatMap(s => (s.summary.recommendations ?? []).map(value => ({ value, s })));
    return <div className="report-stack">{sampleNotice}{reportChoice && <div className="filter-line">{reportChoice}</div>}
      {findings.length ? findings.map(({ value, s }, i) => <Card key={i} title={value.issue}
        note={s.summary.query + ' · ' + s.summary.indexConfig + ' · ' + num(s.summary.size, 0) + ' rows'}>
        <div className="form-body recommendation"><b>{value.evidence}</b><p>{value.explanation}</p><p>{value.action}</p>
          <div className="metric-grid"><div><span>Potential benefit</span><b>{value.benefit}</b></div><div><span>Trade-off</span><b>{value.drawback}</b></div></div>
          {value.sql && <pre>{value.sql}</pre>}<p>{value.verification}</p>{value.index && !sample &&
            <div className="form-actions"><Action primary onClick={() => onVerify(value.index!)}><Sparkles size={15}/> Verify in sandbox</Action></div>}</div>
      </Card>) : <Card><Empty title="No recommendations in this run" text="The analyzer flags selective scans, large estimate errors, repeated join work and temporary block usage when the plan contains supporting evidence."/></Card>}
    </div>;
  }

  return <div className="report-stack">
    {sampleNotice}
    <div className="run-header">
      <div className="run-title"><span className="run-icon"><Activity size={17}/></span><div><h2>{label}</h2>
        <p>{sample ? 'Commerce sandbox' : new Date(report!.job.createdAt).toLocaleString()} <span>·</span> {num(measured, 0)} {vi ? 'lần đo' : 'measured iterations'}</p></div>
        <span className={'badge ' + (report ? status(report.job) : '')}><Check size={12}/>{sample ? (vi ? 'Hoàn tất' : 'Completed') : status(report!.job)}</span></div>
      <div className="filter-line">{reportChoice}
        {queries.length > 1 && <Select label="" value={currentQuery} onChange={setQuery} options={queryTabs}/>}
        {!sample && reportId && view === 'reports' && <><a className="action plain-action" href={'/api' + prefix + '/reports/' + reportId + '/export?format=json'}><Download size={14}/> JSON</a>
          <a className="action plain-action" href={'/api' + prefix + '/reports/' + reportId + '/export?format=csv'}><Download size={14}/> CSV</a>
          <a className="action plain-action" href={'/api' + prefix + '/reports/' + reportId + '/export?format=html'}><Download size={14}/> HTML</a></>}</div>
    </div>
    {view === 'reports' && !sample && report && runs.some(run => run.id !== reportId && status(run) === 'completed') &&
      <Card title="Historical comparison" note="Matches query, dataset size and configuration name. Check seeds, parameters and workload settings before interpreting changes."
        actions={<Select label="" value={previousRun} onChange={setPreviousRun} options={[[ '', 'Choose another run' ],
          ...runs.filter(run => run.id !== reportId && status(run) === 'completed').map(run =>
            [run.id, String(run.data.label) + ' · ' + new Date(run.createdAt).toLocaleString()] as [string, string])]} />}>
        {past.error && <div className="inline-error">{past.error.message}</div>}
        {past.data && <Historical current={data} prior={past.data.scenarios}/>}
      </Card>}
    <div className="stats-grid">
      <Stat icon={<Zap size={16}/>} className="purple" label={vi ? 'Tăng tốc tốt nhất' : 'Best speedup'} value={num(factor, 2)} unit="×">
        <span className="positive"><ArrowUpRight size={14}/> {num(improvement, 1)}% {vi ? 'thấp hơn' : 'lower latency'}</span></Stat>
      <Stat icon={<Clock3 size={16}/>} className="green" label={vi ? 'Độ trễ nhanh nhất' : 'Fastest mean latency'} value={num(best?.summary.mean ?? 0)} unit="ms">
        {best?.summary.indexConfig} <span>·</span> {compact(largest)} rows</Stat>
      <Stat icon={<Database size={16}/>} className="blue" label={vi ? 'Tập dữ liệu lớn nhất' : 'Largest dataset'} value={compact(largest)} unit="rows">
        {sizes.length} {vi ? 'quy mô dữ liệu' : 'dataset sizes tested'}</Stat>
      <Stat icon={<Layers3 size={16}/>} className="orange" label={vi ? 'Kịch bản' : 'Scenarios completed'} value={String(scenarios.length)}>
        {names.length} {vi ? 'cấu hình chỉ mục' : 'index configurations'}</Stat>
    </div>
    <div className="report-tabs" role="tablist" aria-label="Benchmark metrics">{modes.map((item, i) => <button key={item} role="tab"
      aria-selected={item === mode} className={item === mode ? 'selected' : ''} onClick={() => setMode(item)}>
      {vi ? ['Tổng quan', 'Phân bố độ trễ', 'Dung lượng chỉ mục', 'Thông lượng'][i] : item}</button>)}</div>
    <div className="charts-grid">
      <Card title={mode === 'Latency distribution' ? 'Latency percentiles' : vi ? 'Độ trễ theo quy mô' : 'Latency by dataset size'}
        note={mode === 'Latency distribution' ? 'Client timings at the largest dataset size' : 'Mean client latency across index configurations'}
        actions={<span className="chart-head-icon"><ChartNoAxesCombined size={17}/></span>}>
        <Chart scenarios={data} dark={dark} kind={mode === 'Latency distribution' ? 'distribution' : 'scale'}/>
        <div className="chart-legend">{names.map((name, i) => <span key={name}><i style={{ backgroundColor: colors[i % colors.length] }}/>{name}</span>)}</div>
      </Card>
      <Card title={mode === 'Index storage' ? 'Index storage' : mode === 'Throughput' ? 'Execution throughput' : vi ? 'So sánh cấu hình' : 'Configuration comparison'}
        note={(mode === 'Index storage' ? 'Total index size' : mode === 'Throughput' ? 'Queries per second' : 'Mean client latency') + ' at ' + compact(largest) + ' rows'}
        actions={<span className="chart-head-icon"><Layers3 size={16}/></span>}>
        <Chart scenarios={data} dark={dark} kind={mode === 'Index storage' ? 'storage' : mode === 'Throughput' ? 'throughput' : 'ranking'}/>
        <div className="chart-legend subtle">{mode === 'Index storage' ? 'Scale: bytes' : mode === 'Throughput' ? 'Higher is better' : <><ArrowDown size={13}/> Lower is better</>}</div>
      </Card>
    </div>
    <Card title={vi ? 'Kết quả kịch bản' : 'Scenario results'} note={vi ? 'Kết quả theo quy mô và cấu hình.' : 'Compare measured timings, storage and execution plans.'}
      actions={<div className="filter-line"><Select label="" value={scale} onChange={setScale} options={[
        ['', 'All dataset sizes'], ...sizes.map(item => [String(item), num(item, 0) + ' rows'] as [string, string]),
      ]}/>{view === 'reports' && <Action onClick={() => setRaw(!raw)}>{raw ? 'Summary' : 'Raw timings'}</Action>}</div>}>
      <div className="table-scroll"><table><thead><tr><th>Dataset size</th><th>Index configuration</th><th>Mean latency</th><th>P95 latency</th><th>Index size</th><th>Plan</th></tr></thead>
        <tbody>{rows.map((item, i) => {
          const s = item.summary;
          return <tr key={i}><td className="mono">{num(s.size, 0)}</td>
            <td><span className="series-name"><i style={{ backgroundColor: colors[names.indexOf(s.indexConfig) % colors.length] }}/>{s.indexConfig}</span></td>
            <td className="mono">{num(s.mean)} ms</td><td className="mono">{num(s.p95)} ms</td><td>{bytes(s.indexBytes)}</td>
            <td><button className="plan-button" onClick={() => setSelected(item)}><GitFork size={14}/> {s.planNode || 'View plan'} <ChevronRight size={14}/></button></td></tr>;
        })}</tbody></table></div>
      {raw && <div className="form-body raw-details">{rows.map((item, i) => <details key={i}><summary>{item.summary.indexConfig} · {num(item.summary.size, 0)} rows · {item.samples.length} samples</summary>
        <pre>{JSON.stringify(item.samples, null, 2)}</pre></details>)}</div>}
    </Card>
  </div>;
}

function Historical({ current, prior }: { current: Scenario[]; prior: Scenario[] }) {
  const key = (item: Scenario) => JSON.stringify([item.summary.query, item.summary.size, item.summary.indexConfig]);
  const previous = new Map(prior.map(item => [key(item), item.summary.mean]));
  const matching = current.filter(item => previous.has(key(item)));
  if (!matching.length) return <Empty title="No matching scenarios" text="Choose a run with the same query, dataset size and index configuration names."/>;
  return <div className="table-scroll"><table><thead><tr><th>Query</th><th>Dataset</th><th>Configuration</th>
    <th>Previous mean</th><th>Current mean</th><th>Change</th></tr></thead><tbody>
    {matching.map(item => { const old = previous.get(key(item))!; const value = item.summary.mean;
      return <tr key={key(item)}><td>{item.summary.query}</td><td>{num(item.summary.size, 0)}</td><td>{item.summary.indexConfig}</td>
        <td>{num(old)} ms</td><td>{num(value)} ms</td><td className={value < old ? 'positive' : ''}>
          {old > 0 ? (value > old ? '+' : '') + num((value - old) / old * 100, 1) + '%' : '—'}</td></tr>; })}
  </tbody></table></div>;
}
