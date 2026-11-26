'use client';

import { useEffect, useRef } from 'react';
import { init, use, type EChartsCoreOption } from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { Scenario } from './data';
import { compact, num } from './data';

use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
export const colors = ['#e89556', '#9a82fa', '#4bc9bd', '#e77dac', '#658de8', '#e4c65b'];

export default function Chart({ scenarios, kind, dark }: { scenarios: Scenario[]; kind: 'scale' | 'ranking' | 'distribution' | 'storage' | 'throughput'; dark: boolean }) {
  const node = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!node.current || !scenarios.length) return;
    const chart = init(node.current, null, { renderer: 'canvas' });
    const font = 'Inter, system-ui, sans-serif';
    const muted = dark ? '#888c9e' : '#616477';
    const line = dark ? '#282a38' : '#e7e6ef';
    const axisLabel = { color: muted, fontSize: 10, fontFamily: font };
    const largest = Math.max(...scenarios.map(s => s.summary.size));
    const ranking = scenarios.filter(s => s.summary.size === largest);
    const names = [...new Set(scenarios.map(s => s.summary.indexConfig))];
    const sizes = [...new Set(scenarios.map(s => s.summary.size))].sort((a, b) => a - b);
    const horizontal = kind === 'ranking' || kind === 'storage' || kind === 'throughput';
    const fmt = (v: number) => kind === 'storage' ? compact(v) + 'B' : kind === 'throughput' ? compact(v) : num(v, v < 10 ? 1 : 0) + ' ms';
    const valueAxis = { type: 'value', axisLabel: { ...axisLabel, formatter: fmt }, splitNumber: 4,
      axisLine: { show: false }, axisTick: { show: false }, splitLine: { lineStyle: { color: line, type: 'dashed' } } };
    const categories = kind === 'distribution' ? ['Min', 'P50', 'P95', 'P99', 'Max'] : sizes.map(compact);
    const series = horizontal ? [{
      type: 'bar', barWidth: 13, showBackground: true, backgroundStyle: { color: dark ? '#232432' : '#eeedf4', borderRadius: 4 },
      data: ranking.map(s => ({ value: kind === 'storage' ? s.summary.indexBytes : kind === 'throughput' ? s.summary.throughput : s.summary.mean,
        itemStyle: { color: colors[names.indexOf(s.summary.indexConfig) % colors.length], borderRadius: 4 } })),
      label: { show: true, position: 'right', color: dark ? '#dedfeb' : '#262737', fontSize: 11,
        formatter: (p: { value: number }) => fmt(p.value) },
    }] : names.map((name, i) => ({
      name, type: 'line', connectNulls: false, symbol: 'circle', symbolSize: 7, showSymbol: true,
      lineStyle: { width: 2, type: i === 2 ? 'dashed' : 'solid' }, itemStyle: { borderColor: dark ? '#161720' : '#fff', borderWidth: 1.5 },
      data: kind === 'scale' ? sizes.map(size => scenarios.find(s => s.summary.size === size && s.summary.indexConfig === name)?.summary.mean ?? null)
        : (() => { const s = ranking.find(s => s.summary.indexConfig === name)?.summary;
          return s ? [s.min, s.median, s.p95, s.p99, s.max] : []; })(),
    }));
    const option: EChartsCoreOption = {
      animation: false, color: colors, textStyle: { fontFamily: font }, tooltip: { trigger: 'axis', confine: true, renderMode: 'richText',
        backgroundColor: dark ? '#232433' : '#fff', borderColor: line, textStyle: { color: dark ? '#f5f4fc' : '#222333', fontSize: 12 },
        valueFormatter: (v: number) => fmt(v) },
      grid: horizontal ? { left: 104, right: 61, top: 14, bottom: 33 } : { left: 53, right: 18, top: 17, bottom: 34 },
      xAxis: horizontal ? valueAxis : { type: 'category', data: categories, boundaryGap: true, axisLabel,
        axisLine: { lineStyle: { color: line } }, axisTick: { show: false } },
      yAxis: horizontal ? { type: 'category', inverse: true, data: ranking.map(s => s.summary.indexConfig), axisLine: { show: false },
        axisTick: { show: false }, axisLabel: { ...axisLabel, width: 94, overflow: 'truncate' } } : valueAxis,
      series,
    };
    chart.setOption(option);
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(node.current);
    return () => { observer.disconnect(); chart.dispose(); };
  }, [scenarios, kind, dark]);
  return <div className="chart-canvas" ref={node} role="img" aria-label={kind === 'scale' ? 'Query latency by dataset size' : kind + ' chart'} />;
}
