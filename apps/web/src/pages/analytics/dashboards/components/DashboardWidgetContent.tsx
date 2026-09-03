import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Empty, Spin } from 'antd'
import type { EChartsCoreOption } from 'echarts/core'
import {
  queryAnalyticsDataset,
  type AnalyticsDataset,
  type AnalyticsQueryResult,
  type DashboardWidget
} from '@/api/analytics'
import EChart from './EChart'
import { usePreferences } from '@/contexts/PreferencesContext'
import type { ColorMode } from '@/utils/preferences'

interface DashboardWidgetContentProps {
  widget: DashboardWidget
  datasets: AnalyticsDataset[]
}

const chartPalettes = {
  dark: {
    axisText: '#777b85',
    line: 'rgba(255,255,255,.08)',
    primary: '#f0f0f2',
    muted: '#a8abb3',
    surface: '#16171b',
    tooltipBg: '#202126',
    tooltipBorder: 'rgba(255,255,255,.12)',
    tooltipText: '#f0f0f2',
    area: 'rgba(255,255,255,.07)'
  },
  light: {
    axisText: '#737b87',
    line: 'rgba(15,23,42,.10)',
    primary: '#202226',
    muted: '#4f5560',
    surface: '#ffffff',
    tooltipBg: '#25272b',
    tooltipBorder: 'rgba(15,23,42,.18)',
    tooltipText: '#ffffff',
    area: 'rgba(15,23,42,.09)'
  }
} as const

function fieldIndex(result: AnalyticsQueryResult, field?: string, fallback = 0) {
  if (!field) return Math.min(fallback, Math.max(result.columns.length - 1, 0))
  const index = result.columns.indexOf(field)
  return index >= 0 ? index : fallback
}

function formatMetric(value: unknown, decimals = 0) {
  if (typeof value === 'number') {
    return new Intl.NumberFormat('zh-CN', {
      maximumFractionDigits: decimals,
      minimumFractionDigits: decimals
    }).format(value)
  }
  return value == null ? '—' : String(value)
}

function buildChartOption(
  widget: DashboardWidget,
  result: AnalyticsQueryResult,
  colorMode: ColorMode,
  reduceMotion: boolean
): EChartsCoreOption {
  const palette = chartPalettes[colorMode]
  const xIndex = fieldIndex(result, widget.mapping.xField ?? widget.mapping.categoryField, 0)
  const yIndex = fieldIndex(result, widget.mapping.yField ?? widget.mapping.valueField, 1)
  const categories = result.rows.map((row) => String(row[xIndex] ?? ''))
  const values = result.rows.map((row) => row[yIndex] as number)

  if (widget.type === 'pie') {
    return {
      animationDuration: reduceMotion ? 0 : 420,
      tooltip: {
        trigger: 'item',
        backgroundColor: palette.tooltipBg,
        borderColor: palette.tooltipBorder,
        textStyle: { color: palette.tooltipText }
      },
      legend: { bottom: 2, textStyle: { color: palette.muted, fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
      series: [{
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '43%'],
        itemStyle: { borderColor: palette.surface, borderWidth: 2, borderRadius: 3 },
        label: { show: false },
        data: categories.map((name, index) => ({ name, value: values[index] }))
      }]
    }
  }

  return {
    animationDuration: reduceMotion ? 0 : 420,
    grid: { left: 10, right: 16, top: 18, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', backgroundColor: palette.tooltipBg, borderColor: palette.tooltipBorder, textStyle: { color: palette.tooltipText } },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: palette.axisText, fontSize: 10, hideOverlap: true },
      axisLine: { lineStyle: { color: palette.line } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: palette.axisText, fontSize: 10 },
      splitLine: { lineStyle: { color: palette.line } }
    },
    series: [{
      type: widget.type,
      data: values,
      smooth: widget.type === 'line',
      symbol: 'circle',
      symbolSize: 5,
      itemStyle: { color: palette.primary, borderRadius: widget.type === 'bar' ? [3, 3, 0, 0] : 0 },
      lineStyle: { color: palette.primary, width: 2 },
      areaStyle: widget.type === 'line' ? { color: palette.area } : undefined,
      barMaxWidth: 26
    }]
  }
}

export default function DashboardWidgetContent({ widget, datasets }: DashboardWidgetContentProps) {
  const { colorMode, reduceMotion } = usePreferences()
  const dataset = datasets.find((item) => item.id === widget.datasetId)
  const query = useQuery({
    queryKey: ['analytics-dataset-result', widget.datasetId],
    queryFn: () => queryAnalyticsDataset(widget.datasetId!),
    enabled: Boolean(widget.datasetId),
    refetchInterval: widget.refreshSeconds
      ? Math.max(widget.refreshSeconds, 5) * 1000
      : dataset?.refreshSeconds
        ? dataset.refreshSeconds * 1000
        : false,
    refetchIntervalInBackground: false
  })

  const option = useMemo(
    () => query.data && ['line', 'bar', 'pie'].includes(widget.type)
      ? buildChartOption(widget, query.data, colorMode, reduceMotion)
      : undefined,
    [colorMode, query.data, reduceMotion, widget]
  )

  if (widget.type === 'text') {
    return <div className="dashboard-text-widget">{widget.content || '双击属性面板编辑说明文字。'}</div>
  }

  if (!widget.datasetId) {
    return (
      <div className="dashboard-widget-empty">
        <span className="dashboard-empty-glyph" />
        <strong>绑定数据集</strong>
        <small>在右侧选择数据集和字段</small>
      </div>
    )
  }

  if (query.isLoading) return <div className="dashboard-widget-loading"><Spin size="small" /></div>
  if (query.isError) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="数据查询失败" />
  if (!query.data || query.data.rows.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />

  if (widget.type === 'metric') {
    const index = fieldIndex(query.data, widget.mapping.valueField, 0)
    return (
      <div className="dashboard-metric-widget">
        <strong>{formatMetric(query.data.rows[0]?.[index], widget.decimals)}</strong>
        <span>{dataset?.name}</span>
      </div>
    )
  }

  if (widget.type === 'table') {
    return (
      <div className="dashboard-table-scroll">
        <table>
          <thead><tr>{query.data.columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
          <tbody>
            {query.data.rows.slice(0, 20).map((row, rowIndex) => (
              <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{String(cell ?? '—')}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  return option ? <EChart option={option} /> : null
}
