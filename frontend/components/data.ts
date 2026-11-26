export type Resource = {
  id: string;
  name: string;
  kind: string;
  projectId: string;
  createdAt: string;
  updatedAt: string;
  data: Record<string, unknown>;
};

export type Project = { id: string; name: string; description: string; configuration: Record<string, unknown> };
export type PlanNode = { 'Node Type'?: string; Plans?: PlanNode[]; [key: string]: unknown };
export type Recommendation = {
  issue: string; evidence: string; explanation: string; action: string; benefit: string; drawback: string;
  verification: string; sql?: string; index?: Record<string, unknown>;
};
export type Scenario = {
  summary: {
    size: number; query: string; indexConfig: string; mean: number; median: number; min: number; max: number;
    stddev: number; p95: number; p99: number; executionMs: number; planningMs: number; throughput: number;
    indexBytes: number; tableBytes: number; indexBuildMs: number; count: number; planNode: string;
    recommendations?: Recommendation[];
  };
  samples: { clientMs: number; rows: number }[];
  plan: { Plan: PlanNode }[];
};
export type Report = { job: Resource; scenarios: Scenario[] };
export type Index = {
  name: string; schema: string; table: string; method: string; unique: boolean; protected: boolean; enabled: boolean;
  definition: string; predicate: string | null; expressions: string | null; bytes: number; scans: number; columns: string[];
};
export type Catalog = {
  tables: Table[]; schemas: { name: string }[]; indexes: Index[]; sequences: Record<string, unknown>[];
  extensions: { name: string; version: string }[];
};
export type Table = {
  schema: string; name: string; kind: string; estimatedRows: number; exactRows?: number; tableBytes: number; definition?: string;
  columns: { name: string; type: string; nullable: boolean; defaultValue?: string; identity: string; generated: string; nullFraction?: number; distinctEstimate?: number }[];
  constraints: { name: string; type: string; definition: string; foreignSchema?: string; foreignTable?: string; columns: string[] }[];
};
export type Preview = { name: string; clientMs?: number; rows?: (string | null)[][]; columns?: string[]; updated?: number; truncated?: boolean; plan?: { Plan: PlanNode }[] };

export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, {
    method,
    cache: 'no-store',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(error?.message ?? 'The local API is unavailable. Check that the Java service is running.');
  }
  const value = await response.text();
  return (value ? JSON.parse(value) : null) as T;
}

export function jsonObject(text: string): Record<string, unknown> {
  const value: unknown = JSON.parse(text);
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Enter a JSON object.');
  return value as Record<string, unknown>;
}

export function num(value: number, digits = 2) {
  return new Intl.NumberFormat('en', { maximumFractionDigits: digits }).format(value);
}
export function compact(value: number) {
  return new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}
export function bytes(value: number) {
  return value >= 1048576 ? num(value / 1048576, 1) + ' MB' : num(value / 1024, 0) + ' KB';
}
export function status(job: Resource) { return String(job.data.status); }
export function active(job: Resource) { return ['queued', 'running'].includes(status(job)); }

const definitions = [
  { name: 'Baseline', values: [4.1, 31.7, 148.6, 842.3], plan: 'Seq Scan' },
  { name: 'B-tree index', values: [1.25, 2.9, 12.7, 43.2], plan: 'Index Scan' },
  { name: 'GIN trigram', values: [1.72, 4.8, 24.1, 96.4], plan: 'Bitmap Heap Scan' },
];
const sizes = [1000, 10000, 100000, 1000000];
export const sampleReport: Report = {
  job: { id: 'sample', name: 'Sample run', kind: 'job', projectId: '', data: { type: 'benchmark', status: 'completed', label: 'Customer lookup comparison' }, createdAt: '', updatedAt: '' },
  scenarios: sizes.flatMap((size, i) => definitions.map((definition, j) => {
    const mean = definition.values[i];
    return {
      summary: { size, query: 'Customer lookup', indexConfig: definition.name, mean, median: mean * .98,
        min: mean * .78, max: mean * 1.22, stddev: mean * .11, p95: mean * 1.16, p99: mean * 1.21,
        executionMs: mean * .85, planningMs: .17, throughput: 1000 / mean, indexBytes: size * (32 + j * 14),
        tableBytes: size * 180, indexBuildMs: j * 12, count: 20, planNode: definition.plan },
      samples: Array.from({ length: 20 }, (_, n) => ({ clientMs: mean * (.78 + (n % 11) * .044), rows: 1 })),
      plan: [{ Plan: { 'Node Type': definition.plan, 'Relation Name': 'customers', 'Plan Rows': 1, 'Actual Rows': 1,
        'Actual Loops': 1, 'Actual Total Time': mean * .85, 'Shared Hit Blocks': size / 100, 'Shared Read Blocks': 0 } }],
    };
  })).reverse().sort((a, b) => b.summary.size - a.summary.size || definitions.findIndex(x => x.name === a.summary.indexConfig) - definitions.findIndex(x => x.name === b.summary.indexConfig)),
};

export const navEn = {
  overview: 'Overview', projects: 'Projects', connections: 'Connections', schema: 'Schema Explorer', sandboxes: 'Sandboxes',
  generator: 'Data Generator', load: 'Load Testing', sql: 'Query Workspace', indexes: 'Index Manager', benchmark: 'Benchmark Lab',
  plans: 'Execution Plans', optimization: 'Optimization', reports: 'Reports', settings: 'Settings',
} as const;
export type View = keyof typeof navEn;
export const navVi: Record<View, string> = {
  overview: 'Tổng quan', projects: 'Dự án', connections: 'Kết nối', schema: 'Khám phá lược đồ', sandboxes: 'Môi trường thử nghiệm',
  generator: 'Tạo dữ liệu', load: 'Kiểm thử tải', sql: 'Không gian SQL', indexes: 'Quản lý chỉ mục', benchmark: 'Đo hiệu năng',
  plans: 'Kế hoạch thực thi', optimization: 'Tối ưu hóa', reports: 'Báo cáo', settings: 'Cài đặt',
};
