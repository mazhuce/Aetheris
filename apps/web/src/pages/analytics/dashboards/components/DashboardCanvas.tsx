import { CloseOutlined, DragOutlined } from '@ant-design/icons'
import { Responsive, WidthProvider, type Layout, type LayoutItem, type ResponsiveLayouts } from 'react-grid-layout/legacy'
import type { AnalyticsDataset, DashboardSpec, DashboardWidget } from '@/api/analytics'
import DashboardWidgetContent from './DashboardWidgetContent'

const ResponsiveGrid = WidthProvider(Responsive)

interface DashboardCanvasProps {
  spec: DashboardSpec
  datasets: AnalyticsDataset[]
  editable?: boolean
  selectedWidgetId?: string
  onSelect?: (id: string) => void
  onRemove?: (id: string) => void
  onLayoutsChange?: (layouts: Record<string, Layout>) => void
  onDrop?: (layout: Layout, item: LayoutItem | undefined, event: Event) => void
  onDropDragOver?: (event: React.DragEvent) => { w: number; h: number } | false
}

export default function DashboardCanvas({
  spec,
  datasets,
  editable = false,
  selectedWidgetId,
  onSelect,
  onRemove,
  onLayoutsChange,
  onDrop,
  onDropDragOver
}: DashboardCanvasProps) {
  const layouts = spec.layouts as ResponsiveLayouts

  return (
    <div className={`dashboard-canvas${editable ? ' is-editing' : ''}`}>
      {spec.widgets.length === 0 && editable && (
        <div className="dashboard-canvas-empty">
          <span />
          <strong>把组件拖到画布中</strong>
          <p>从左侧选择图表，拖入后在右侧绑定数据集与字段。</p>
        </div>
      )}
      <ResponsiveGrid
        className="dashboard-grid"
        layouts={layouts}
        breakpoints={{ lg: 1180, md: 900, sm: 640, xs: 420, xxs: 0 }}
        cols={{ lg: 24, md: 16, sm: 8, xs: 4, xxs: 2 }}
        rowHeight={34}
        margin={[12, 12]}
        containerPadding={[0, 0]}
        compactType="vertical"
        isDraggable={editable}
        isResizable={editable}
        isDroppable={editable}
        draggableHandle=".dashboard-widget-handle"
        draggableCancel=".dashboard-widget-action"
        onLayoutChange={(_current, nextLayouts) => onLayoutsChange?.(nextLayouts as Record<string, Layout>)}
        onDrop={onDrop}
        onDropDragOver={onDropDragOver}
      >
        {spec.widgets.map((widget: DashboardWidget) => (
          <div
            key={widget.id}
            className={`dashboard-widget${selectedWidgetId === widget.id ? ' is-selected' : ''}`}
            onMouseDown={() => onSelect?.(widget.id)}
          >
            <div className="dashboard-widget-header dashboard-widget-handle">
              <div>
                {editable && <DragOutlined />}
                <strong>{widget.title}</strong>
              </div>
              {editable && (
                <button
                  className="dashboard-widget-action"
                  type="button"
                  aria-label="删除组件"
                  onClick={(event) => {
                    event.stopPropagation()
                    onRemove?.(widget.id)
                  }}
                >
                  <CloseOutlined />
                </button>
              )}
            </div>
            <div className="dashboard-widget-body">
              <DashboardWidgetContent widget={widget} datasets={datasets} />
            </div>
          </div>
        ))}
      </ResponsiveGrid>
    </div>
  )
}
