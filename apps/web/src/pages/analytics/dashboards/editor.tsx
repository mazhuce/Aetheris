import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  CopyOutlined,
  DeleteOutlined,
  EyeOutlined,
  FieldNumberOutlined,
  LineChartOutlined,
  PieChartOutlined,
  RocketOutlined,
  SaveOutlined,
  TableOutlined,
  FileTextOutlined
} from '@ant-design/icons'
import { Button, Divider, Empty, Input, InputNumber, Select, Space, Spin, Tooltip, message } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import type { Layout, LayoutItem } from 'react-grid-layout/legacy'
import {
  getAnalyticsDashboard,
  listAnalyticsDatasets,
  publishAnalyticsDashboard,
  updateAnalyticsDashboard,
  type AnalyticsDashboard,
  type AnalyticsDataset,
  type DashboardLayoutItem,
  type DashboardSpec,
  type DashboardWidget,
  type DashboardWidgetType
} from '@/api/analytics'
import DashboardCanvas from './components/DashboardCanvas'

interface PaletteItem {
  type: DashboardWidgetType
  title: string
  description: string
  icon: React.ReactNode
  size: { w: number; h: number; minW: number; minH: number }
}

const palette: PaletteItem[] = [
  { type: 'metric', title: '指标卡', description: '展示单个核心数值', icon: <FieldNumberOutlined />, size: { w: 6, h: 4, minW: 4, minH: 3 } },
  { type: 'line', title: '折线图', description: '展示时间与趋势变化', icon: <LineChartOutlined />, size: { w: 12, h: 8, minW: 7, minH: 6 } },
  { type: 'bar', title: '柱状图', description: '比较分类与排名', icon: <BarChartOutlined />, size: { w: 12, h: 8, minW: 7, minH: 6 } },
  { type: 'pie', title: '环形图', description: '展示构成和占比', icon: <PieChartOutlined />, size: { w: 8, h: 8, minW: 6, minH: 6 } },
  { type: 'table', title: '明细表', description: '查看查询结果明细', icon: <TableOutlined />, size: { w: 14, h: 8, minW: 8, minH: 6 } },
  { type: 'text', title: '说明文字', description: '添加标题和业务说明', icon: <FileTextOutlined />, size: { w: 8, h: 4, minW: 4, minH: 3 } }
]

function defaultTitle(type: DashboardWidgetType) {
  return palette.find((item) => item.type === type)?.title || '数据组件'
}

function newWidget(type: DashboardWidgetType): DashboardWidget {
  return {
    id: `${type}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    type,
    title: defaultTitle(type),
    mapping: {},
    content: type === 'text' ? '在这里补充业务背景、指标口径或结论说明。' : undefined
  }
}

export default function DashboardEditorPage() {
  const { id } = useParams()
  const dashboardId = Number(id)
  const navigate = useNavigate()
  const [dashboard, setDashboard] = useState<AnalyticsDashboard | null>(null)
  const [datasets, setDatasets] = useState<AnalyticsDataset[]>([])
  const [spec, setSpec] = useState<DashboardSpec | null>(null)
  const [selectedId, setSelectedId] = useState<string>()
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const dragType = useRef<DashboardWidgetType>()
  const previewChannel = useRef<BroadcastChannel>()

  useEffect(() => {
    Promise.all([getAnalyticsDashboard(dashboardId), listAnalyticsDatasets()])
      .then(([nextDashboard, nextDatasets]) => {
        setDashboard(nextDashboard)
        setSpec(nextDashboard.draftSpec)
        setDatasets(nextDatasets.filter((dataset) => dataset.status === 'published'))
      })
      .catch(() => message.error('看板编辑器加载失败'))
  }, [dashboardId])

  useEffect(() => {
    previewChannel.current = new BroadcastChannel(`aetheris-dashboard-${dashboardId}`)
    return () => previewChannel.current?.close()
  }, [dashboardId])

  useEffect(() => {
    if (spec) previewChannel.current?.postMessage({ type: 'spec', spec })
  }, [spec])

  const selectedWidget = useMemo(
    () => spec?.widgets.find((widget) => widget.id === selectedId),
    [selectedId, spec]
  )
  const selectedDataset = datasets.find((dataset) => dataset.id === selectedWidget?.datasetId)
  const fieldOptions = (selectedDataset?.schema?.columns || []).map((column) => ({ value: column, label: column }))

  const mutateSpec = (mutator: (current: DashboardSpec) => DashboardSpec) => {
    setSpec((current) => current ? mutator(current) : current)
    setDirty(true)
  }

  const addWidget = (type: DashboardWidgetType, dropped?: LayoutItem) => {
    const widget = newWidget(type)
    const size = palette.find((item) => item.type === type)!.size
    mutateSpec((current) => {
      const lg = (current.layouts.lg || []).filter((item) => item.i !== '__dropping-elem__')
      const bottom = lg.reduce((max, item) => Math.max(max, item.y + item.h), 0)
      const layout: DashboardLayoutItem = {
        i: widget.id,
        x: dropped?.x ?? 0,
        y: dropped?.y ?? bottom,
        w: dropped?.w ?? size.w,
        h: dropped?.h ?? size.h,
        minW: size.minW,
        minH: size.minH
      }
      return { ...current, widgets: [...current.widgets, widget], layouts: { ...current.layouts, lg: [...lg, layout] } }
    })
    setSelectedId(widget.id)
  }

  const updateWidget = (patch: Partial<DashboardWidget>) => {
    if (!selectedId) return
    mutateSpec((current) => ({
      ...current,
      widgets: current.widgets.map((widget) => widget.id === selectedId ? { ...widget, ...patch } : widget)
    }))
  }

  const removeWidget = (widgetId: string) => {
    mutateSpec((current) => ({
      ...current,
      widgets: current.widgets.filter((widget) => widget.id !== widgetId),
      layouts: Object.fromEntries(Object.entries(current.layouts).map(([breakpoint, items]) => [
        breakpoint,
        items.filter((item) => item.i !== widgetId)
      ]))
    }))
    if (selectedId === widgetId) setSelectedId(undefined)
  }

  const duplicateWidget = () => {
    if (!selectedWidget || !spec) return
    const clone = { ...selectedWidget, id: `${selectedWidget.type}-${Date.now()}`, title: `${selectedWidget.title} 副本` }
    const sourceLayout = spec.layouts.lg?.find((item) => item.i === selectedWidget.id)
    mutateSpec((current) => ({
      ...current,
      widgets: [...current.widgets, clone],
      layouts: {
        ...current.layouts,
        lg: [...(current.layouts.lg || []), {
          ...(sourceLayout || { x: 0, y: 0, w: 8, h: 6 }),
          i: clone.id,
          x: Math.min((sourceLayout?.x || 0) + 1, 18),
          y: (sourceLayout?.y || 0) + 1
        }]
      }
    }))
    setSelectedId(clone.id)
  }

  const handleLayouts = (layouts: Record<string, Layout>) => {
    if (!spec) return
    const normalized = Object.fromEntries(Object.entries(layouts).map(([key, items]) => [key, items.map((item) => ({ ...item }))]))
    if (JSON.stringify(normalized) === JSON.stringify(spec.layouts)) return
    mutateSpec((current) => ({ ...current, layouts: normalized as Record<string, DashboardLayoutItem[]> }))
  }

  const save = async () => {
    if (!dashboard || !spec) return dashboard
    setSaving(true)
    try {
      const saved = await updateAnalyticsDashboard(dashboard.id, {
        name: dashboard.name,
        description: dashboard.description,
        spec
      })
      setDashboard(saved)
      setDirty(false)
      return saved
    } catch (error: any) {
      message.error(error.response?.data?.message || '看板保存失败')
      throw error
    } finally {
      setSaving(false)
    }
  }

  const openPreview = async () => {
    await save()
    window.open(`/analytics/dashboards/${dashboardId}/preview`, '_blank', 'noopener')
  }

  const publish = async () => {
    setPublishing(true)
    try {
      await save()
      const published = await publishAnalyticsDashboard(dashboardId)
      setDashboard(published)
      message.success(`看板已发布为 v${published.publishedVersion}`)
    } finally {
      setPublishing(false)
    }
  }

  if (!spec || !dashboard) {
    return <div className="dashboard-editor-loading"><Spin /><span>正在准备画布…</span></div>
  }

  return (
    <div className="dashboard-editor-page">
      <header className="dashboard-editor-toolbar">
        <div className="editor-toolbar-left">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/analytics/dashboards')} />
          <div><span>看板编辑器</span><strong>{dashboard.name}</strong></div>
          <i className={dirty ? 'is-dirty' : ''}>{saving ? '保存中…' : dirty ? '有未保存更改' : '已保存'}</i>
        </div>
        <Space>
          <Button icon={<EyeOutlined />} onClick={() => void openPreview()}>新窗口预览</Button>
          <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存</Button>
          <Button type="primary" icon={<RocketOutlined />} loading={publishing} onClick={() => void publish()}>发布</Button>
        </Space>
      </header>

      <div className="dashboard-editor-workspace">
        <aside className="dashboard-editor-palette">
          <div className="editor-panel-heading"><span>COMPONENTS</span><strong>组件</strong></div>
          <div className="dashboard-palette-list">
            {palette.map((item) => (
              <button
                key={item.type}
                type="button"
                draggable
                onDragStart={(event) => {
                  dragType.current = item.type
                  event.dataTransfer.setData('text/plain', item.type)
                  event.dataTransfer.effectAllowed = 'copy'
                }}
                onClick={() => addWidget(item.type)}
              >
                <span>{item.icon}</span>
                <div><strong>{item.title}</strong><small>{item.description}</small></div>
              </button>
            ))}
          </div>
          <div className="editor-palette-hint">拖入画布添加，也可以单击组件。</div>
        </aside>

        <main className="dashboard-editor-canvas-wrap" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setSelectedId(undefined)
        }}>
          <DashboardCanvas
            spec={spec}
            datasets={datasets}
            editable
            selectedWidgetId={selectedId}
            onSelect={setSelectedId}
            onRemove={removeWidget}
            onLayoutsChange={handleLayouts}
            onDrop={(_layout, item) => {
              if (dragType.current) addWidget(dragType.current, item)
              dragType.current = undefined
            }}
            onDropDragOver={() => {
              const item = palette.find((candidate) => candidate.type === dragType.current)
              return item ? { w: item.size.w, h: item.size.h } : false
            }}
          />
        </main>

        <aside className="dashboard-editor-inspector">
          <div className="editor-panel-heading"><span>PROPERTIES</span><strong>属性</strong></div>
          {!selectedWidget ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择画布中的组件" />
          ) : (
            <div className="dashboard-property-form">
              <label><span>组件标题</span><Input value={selectedWidget.title} onChange={(event) => updateWidget({ title: event.target.value })} /></label>
              {selectedWidget.type !== 'text' && (
                <>
                  <label>
                    <span>数据集</span>
                    <Select
                      value={selectedWidget.datasetId}
                      placeholder="选择数据集"
                      allowClear
                      options={datasets.map((dataset) => ({ value: dataset.id, label: dataset.name }))}
                      onChange={(value) => updateWidget({ datasetId: value, mapping: {} })}
                    />
                  </label>
                  {['line', 'bar'].includes(selectedWidget.type) && (
                    <>
                      <label><span>分类 / X 轴</span><Select allowClear value={selectedWidget.mapping.xField} options={fieldOptions} onChange={(value) => updateWidget({ mapping: { ...selectedWidget.mapping, xField: value } })} /></label>
                      <label><span>数值 / Y 轴</span><Select allowClear value={selectedWidget.mapping.yField} options={fieldOptions} onChange={(value) => updateWidget({ mapping: { ...selectedWidget.mapping, yField: value } })} /></label>
                    </>
                  )}
                  {selectedWidget.type === 'pie' && (
                    <>
                      <label><span>分类字段</span><Select allowClear value={selectedWidget.mapping.categoryField} options={fieldOptions} onChange={(value) => updateWidget({ mapping: { ...selectedWidget.mapping, categoryField: value } })} /></label>
                      <label><span>数值字段</span><Select allowClear value={selectedWidget.mapping.valueField} options={fieldOptions} onChange={(value) => updateWidget({ mapping: { ...selectedWidget.mapping, valueField: value } })} /></label>
                    </>
                  )}
                  {selectedWidget.type === 'metric' && (
                    <>
                      <label><span>指标字段</span><Select allowClear value={selectedWidget.mapping.valueField} options={fieldOptions} onChange={(value) => updateWidget({ mapping: { ...selectedWidget.mapping, valueField: value } })} /></label>
                      <label><span>小数位</span><InputNumber min={0} max={6} value={selectedWidget.decimals || 0} onChange={(value) => updateWidget({ decimals: value || 0 })} /></label>
                    </>
                  )}
                  <label><span>刷新周期（秒）</span><InputNumber min={5} max={3600} value={selectedWidget.refreshSeconds || selectedDataset?.refreshSeconds || 30} onChange={(value) => updateWidget({ refreshSeconds: value || undefined })} /></label>
                </>
              )}
              {selectedWidget.type === 'text' && (
                <label><span>文字内容</span><Input.TextArea rows={8} value={selectedWidget.content} onChange={(event) => updateWidget({ content: event.target.value })} /></label>
              )}
              <Divider />
              <Space className="dashboard-property-actions">
                <Tooltip title="复制组件"><Button icon={<CopyOutlined />} onClick={duplicateWidget}>复制</Button></Tooltip>
                <Button danger icon={<DeleteOutlined />} onClick={() => removeWidget(selectedWidget.id)}>删除</Button>
              </Space>
              {selectedWidget.type !== 'text' && fieldOptions.length === 0 && selectedWidget.datasetId && (
                <p className="dashboard-field-hint">该数据集尚未生成字段结构，请先到“分析数据集”执行一次预览。</p>
              )}
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
