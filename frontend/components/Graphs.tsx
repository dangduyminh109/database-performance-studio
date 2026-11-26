'use client';

import { Background, Controls, Handle, Position, ReactFlow, type Edge, type Node, type NodeProps } from '@xyflow/react';
import { useMemo, useState } from 'react';
import type { Catalog, PlanNode, Table } from './data';
import { num } from './data';
import { Card, Empty } from './ui';

type GraphNode = Node<{ label: string; details: string; warning?: boolean }, 'studio'>;
function Box({ data }: NodeProps<GraphNode>) {
  return <div className={'graph-box ' + (data.warning ? 'warning' : '')}>
    <Handle type="target" position={Position.Top}/><b>{data.label}</b><small>{data.details}</small><Handle type="source" position={Position.Bottom}/>
  </div>;
}
const nodeTypes = { studio: Box };

function planGraph(root: PlanNode) {
  const nodes: GraphNode[] = [], edges: Edge[] = [];
  const values: Record<string, PlanNode> = {};
  let leaf = 0;
  function walk(node: PlanNode, id: string, depth: number): number {
    const positions = (node.Plans ?? []).map((child, i) => {
      const childId = id + '.' + i;
      edges.push({ id: id + '>' + childId, source: id, target: childId });
      return walk(child, childId, depth + 1);
    });
    const x = positions.length ? (positions[0] + positions[positions.length - 1]) / 2 : leaf++ * 246;
    const actual = Number(node['Actual Rows'] ?? 0), estimated = Number(node['Plan Rows'] ?? 0);
    values[id] = node;
    nodes.push({ id, type: 'studio', position: { x, y: depth * 102 }, data: {
      label: node['Node Type'] ?? 'Plan',
      details: typeof node['Actual Total Time'] === 'number'
        ? num(node['Actual Total Time'] as number, 3) + ' ms / loop · ' + num(actual) + ' rows'
        : num(estimated) + ' estimated rows',
      warning: Math.max(actual, estimated) > 100 && (Math.max(actual, estimated) + 1) / (Math.min(actual, estimated) + 1) > 10,
    }});
    return x;
  }
  walk(root, '0', 0);
  return { nodes, edges, values };
}

export function PlanGraph({ root, dark }: { root?: PlanNode; dark: boolean }) {
  const [selected, setSelected] = useState('0');
  const graph = useMemo(() => root ? planGraph(root) : undefined, [root]);
  if (!graph) return <Empty title="No plan available" text="An instrumented PostgreSQL plan appears here after execution." />;
  const current = graph.values[selected] ?? graph.values['0'];
  const metrics = Object.entries(current).filter(([key, value]) => key !== 'Plans' && typeof value !== 'object');
  return <div className="graph-shell"><div className="graph"><ReactFlow nodes={graph.nodes} edges={graph.edges} nodeTypes={nodeTypes} fitView
    minZoom={.08} maxZoom={1.6} colorMode={dark ? 'dark' : 'light'} onNodeClick={(_, node) => setSelected(node.id)}
    nodesDraggable={false} nodesConnectable={false} proOptions={{ hideAttribution: true }}><Background/><Controls showInteractive={false}/></ReactFlow></div>
    <div className="metric-grid">{metrics.map(([key, value]) => <div key={key}><span>{key}</span><b>{String(value)}</b></div>)}</div>
  </div>;
}

export function Erd({ catalog, dark, onSelect }: { catalog: Catalog; dark: boolean; onSelect: (table: Table) => void }) {
  const { nodes, edges, tables } = useMemo(() => {
    const tables = catalog.tables.filter(item => item.kind === 'r' || item.kind === 'p');
    const relation = (table: Table) => JSON.stringify([table.schema, table.name]);
    const known = new Set(tables.map(relation));
    const nodes: GraphNode[] = tables.map((table, i) => ({
      id: relation(table), type: 'studio', position: { x: (i % 4) * 240, y: Math.floor(i / 4) * 115 },
      data: { label: table.name, details: table.schema + ' · ' + table.columns.length + ' columns' },
    }));
    const edges: Edge[] = tables.flatMap(table => table.constraints.filter(c => c.type === 'f').flatMap(c => {
      const to = JSON.stringify([c.foreignSchema, c.foreignTable]);
      return known.has(to) ? [{ id: relation(table) + c.name, source: relation(table), target: to,
        label: c.columns.join(', '), style: { stroke: '#9980f5' } }] : [];
    }));
    return { nodes, edges, tables };
  }, [catalog]);
  return <Card title="Relationships" note="Foreign keys from the PostgreSQL catalog."><div className="graph erd">
    <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView minZoom={.08} maxZoom={1.6} colorMode={dark ? 'dark' : 'light'}
      nodesDraggable={false} nodesConnectable={false} proOptions={{ hideAttribution: true }}
      onNodeClick={(_, node) => { const table = tables.find(item => JSON.stringify([item.schema, item.name]) === node.id); if (table) onSelect(table); }}>
      <Background/><Controls showInteractive={false}/></ReactFlow></div></Card>;
}
