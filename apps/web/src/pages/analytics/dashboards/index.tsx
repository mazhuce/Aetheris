import { useEffect, useState } from 'react'
import { DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined, RocketOutlined } from '@ant-design/icons'
import { Button, Form, Input, Modal, Popconfirm, Tag, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import {
  createAnalyticsDashboard,
  deleteAnalyticsDashboard,
  listAnalyticsDashboards,
  listDashboardTemplates,
  publishAnalyticsDashboard,
  type AnalyticsDashboard,
  type DashboardTemplate
} from '@/api/analytics'
import { getCurrentRole } from '@/utils/access'
import { formatDateTime } from '@/utils/format'

export default function DashboardListPage() {
  const navigate = useNavigate()
  const [templates, setTemplates] = useState<DashboardTemplate[]>([])
  const [dashboards, setDashboards] = useState<AnalyticsDashboard[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedTemplate, setSelectedTemplate] = useState<DashboardTemplate | null>(null)
  const [form] = Form.useForm<{ name: string; description?: string }>()
  const isAdmin = getCurrentRole() === 'admin'

  const reload = async () => {
    setLoading(true)
    try {
      const [nextTemplates, nextDashboards] = await Promise.all([
        listDashboardTemplates(),
        listAnalyticsDashboards()
      ])
      setTemplates(nextTemplates)
      setDashboards(nextDashboards)
    } catch {
      message.error('看板加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void reload() }, [])

  const startCreate = (template: DashboardTemplate) => {
    setSelectedTemplate(template)
    form.setFieldsValue({ name: template.code === 'blank' ? '未命名看板' : template.name, description: template.description })
  }

  const create = async () => {
    if (!selectedTemplate) return
    const values = await form.validateFields()
    try {
      const dashboard = await createAnalyticsDashboard({ ...values, templateId: selectedTemplate.id })
      message.success('看板草稿已创建')
      navigate(`/analytics/dashboards/${dashboard.id}/edit`)
    } catch (error: any) {
      message.error(error.response?.data?.message || '看板创建失败')
    }
  }

  return (
    <div className="page-shell dashboard-list-page">
      <PageHeader
        description="基于中台数据集搭建、预览并发布可持续更新的业务看板。"
        extra={isAdmin ? <Button type="primary" icon={<PlusOutlined />} onClick={() => templates[0] && startCreate(templates[0])}>创建看板</Button> : undefined}
      />

      <section className="dashboard-template-section">
        <div className="dashboard-section-heading">
          <div><h2>选择模板开始</h2></div>
          <p>模板仅定义布局与组件，创建后绑定对应业务数据集。</p>
        </div>
        <div className="dashboard-template-grid">
          {templates.map((template) => (
            <button
              key={template.id}
              type="button"
              className={`dashboard-template-card tone-${template.previewTone}`}
              disabled={!isAdmin}
              onClick={() => startCreate(template)}
            >
              <div className="template-miniature">
                {template.spec.layouts.lg?.slice(0, 7).map((item) => (
                  <i key={item.i} style={{
                    left: `${(item.x / 24) * 100}%`,
                    top: `${Math.min(item.y / 20, .78) * 100}%`,
                    width: `${Math.max((item.w / 24) * 100 - 1.4, 6)}%`,
                    height: `${Math.max((item.h / 22) * 100, 12)}%`
                  }} />
                ))}
                {template.code === 'blank' && <b>+</b>}
              </div>
              <div className="template-card-copy"><span>{template.category}</span><strong>{template.name}</strong><p>{template.description}</p></div>
            </button>
          ))}
        </div>
      </section>

      <section className="dashboard-library-section">
        <div className="dashboard-section-heading">
          <div><h2>看板空间</h2></div>
          <p>{loading ? '正在加载…' : `共 ${dashboards.length} 个看板`}</p>
        </div>
        <div className="dashboard-library-grid">
          {!loading && dashboards.length === 0 && <div className="dashboard-library-empty">选择上方模板，创建第一张数据看板。</div>}
          {dashboards.map((dashboard) => (
            <article className="dashboard-library-card" key={dashboard.id}>
              <div className="dashboard-card-preview">
                {dashboard.draftSpec.layouts.lg?.slice(0, 8).map((item) => (
                  <i key={item.i} style={{
                    left: `${(item.x / 24) * 100}%`,
                    top: `${Math.min(item.y / 22, .8) * 100}%`,
                    width: `${Math.max((item.w / 24) * 100 - 1.5, 5)}%`,
                    height: `${Math.max((item.h / 24) * 100, 10)}%`
                  }} />
                ))}
              </div>
              <div className="dashboard-card-body">
                <div className="dashboard-card-title"><strong>{dashboard.name}</strong><Tag>{dashboard.status === 'published' ? `已发布 v${dashboard.publishedVersion}` : '草稿'}</Tag></div>
                <p>{dashboard.description || '暂无说明'}</p>
                <span>更新于 {formatDateTime(dashboard.updatedAt)}</span>
              </div>
              <div className="dashboard-card-actions">
                {isAdmin && <Button type="text" icon={<EditOutlined />} onClick={() => navigate(`/analytics/dashboards/${dashboard.id}/edit`)}>编辑</Button>}
                <Button type="text" icon={<EyeOutlined />} onClick={() => window.open(`/analytics/dashboards/${dashboard.id}/preview`, '_blank', 'noopener')}>预览</Button>
                {dashboard.status === 'published' && <Button type="text" icon={<RocketOutlined />} onClick={() => window.open(`/d/${dashboard.slug}`, '_blank', 'noopener')}>打开</Button>}
                {isAdmin && dashboard.status !== 'published' && <Button type="text" icon={<RocketOutlined />} onClick={async () => { await publishAnalyticsDashboard(dashboard.id); message.success('看板已发布'); await reload() }}>发布</Button>}
                {isAdmin && (
                  <Popconfirm title="删除这个看板？" onConfirm={async () => { await deleteAnalyticsDashboard(dashboard.id); message.success('看板已删除'); await reload() }}>
                    <Button type="text" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                )}
              </div>
            </article>
          ))}
        </div>
      </section>

      <Modal
        title={`基于“${selectedTemplate?.name || ''}”创建`}
        open={Boolean(selectedTemplate)}
        onCancel={() => setSelectedTemplate(null)}
        onOk={() => void create()}
        okText="创建并编辑"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="看板名称" rules={[{ required: true, message: '请输入看板名称' }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="description" label="业务说明"><Input.TextArea rows={3} maxLength={512} /></Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
