import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App, Button, Col, Empty, Form, Input, InputNumber, Modal, Popconfirm, Row, Select, Space,
  Switch, Table, Tabs, Tag, Typography
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  CheckCircleOutlined, CheckOutlined, ClockCircleOutlined, CloudServerOutlined, EditOutlined,
  PlusOutlined, ReloadOutlined, SendOutlined, StopOutlined, CloseOutlined, TeamOutlined, TrophyOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import {
  createLab, decideApproval, getAdminAnalytics, listAdminDatasets, listAdminLabs, listApprovals, publishLab,
  registerAdminDataset, reviewLab, unpublishLab, updateLab, verifyAdminDatasets, type AdminOverview,
  type ApprovalView, type DatasetManifestView, type DatasetVerificationView, type LabAdminView, type LabAnalytics
} from '@/api/learning'
import { formatDateTime } from '@/utils/format'

const reviewMeta: Record<string, { text: string; color: string }> = {
  draft: { text: '草稿', color: 'default' },
  approved: { text: '已审核', color: 'blue' },
  rejected: { text: '已驳回', color: 'red' }
}

const moduleOptions = [
  { value: 'm-l1-sql', label: 'L1 查询与数据认知' },
  { value: 'm-l2-warehouse', label: 'L2 离线数仓开发' },
  { value: 'm-l3-analytics', label: 'L3 经营分析' },
  { value: 'm-l4-production', label: 'L4 生产化与质量' }
]

export default function LearningAdminPage() {
  const { message, modal } = App.useApp()
  const [labs, setLabs] = useState<LabAdminView[]>([])
  const [approvals, setApprovals] = useState<ApprovalView[]>([])
  const [analytics, setAnalytics] = useState<{ overview: AdminOverview; labs: LabAnalytics[] } | null>(null)
  const [datasets, setDatasets] = useState<DatasetManifestView[]>([])
  const [verifications, setVerifications] = useState<Record<string, DatasetVerificationView>>({})
  const [verifying, setVerifying] = useState(false)
  const [registerOpen, setRegisterOpen] = useState(false)
  const [registering, setRegistering] = useState(false)
  const [datasetForm] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<LabAdminView | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [decisionNote, setDecisionNote] = useState('')
  const [form] = Form.useForm()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [labList, approvalList, stats, datasetList] = await Promise.all([
        listAdminLabs(), listApprovals(), getAdminAnalytics(), listAdminDatasets()
      ])
      setLabs(labList)
      setApprovals(approvalList)
      setAnalytics(stats)
      setDatasets(datasetList)
    } catch {
      message.error('运营数据加载失败，请确认当前账号是内容管理员')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])

  const verifyDatasets = async () => {
    setVerifying(true)
    try {
      const list = await verifyAdminDatasets()
      setVerifications(Object.fromEntries(list.map((item) => [item.code, item])))
      const drifted = list.filter((item) => !item.rowCountMatches || !item.checksumMatches)
      if (drifted.length > 0) message.warning(`${drifted.length} 个数据集与清单不一致，请检查来源数据`)
      else message.success('全部数据集与清单一致')
    } catch {
      message.error('数据集校验失败')
    } finally {
      setVerifying(false)
    }
  }

  const openRegister = () => {
    datasetForm.resetFields()
    datasetForm.setFieldsValue({ version: 1, license: 'internal-training', source: 'real-desensitized', piiReviewed: false })
    setRegisterOpen(true)
  }

  const submitRegister = async () => {
    const values = await datasetForm.validateFields()
    setRegistering(true)
    try {
      await registerAdminDataset({ ...values, code: values.tableName })
      message.success('数据集版本已登记')
      setRegisterOpen(false)
      setDatasets(await listAdminDatasets())
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '数据集登记失败')
    } finally {
      setRegistering(false)
    }
  }

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ level: 'L1', difficulty: 'easy', moduleCode: 'm-l1-sql', passScore: 70, estimatedMinutes: 30, orderNo: 99 })
    setModalOpen(true)
  }

  const openEdit = (lab: LabAdminView) => {
    setEditing(lab)
    form.resetFields()
    form.setFieldsValue({
      code: lab.code, moduleCode: lab.moduleCode, projectCode: lab.projectCode, title: lab.title,
      level: lab.level, difficulty: lab.difficulty, summary: lab.summary, objective: lab.objective,
      expectedSql: lab.expectedSql, passScore: lab.passScore, estimatedMinutes: lab.estimatedMinutes,
      orderNo: 99
    })
    setModalOpen(true)
  }

  const submit = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      if (editing) {
        await updateLab(editing.code, values)
        message.success('已保存为新版本，需重新审核')
      } else {
        await createLab(values)
        message.success('草稿已创建')
      }
      setModalOpen(false)
      await refresh()
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleReview = async (lab: LabAdminView, approve: boolean) => {
    try {
      await reviewLab(lab.code, approve, approve ? '后台审核通过' : '后台驳回')
      message.success(approve ? '审核通过' : '已驳回')
      await refresh()
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '审核失败')
    }
  }

  const handlePublish = async (lab: LabAdminView) => {
    try {
      await publishLab(lab.code)
      message.success('任务已发布')
      await refresh()
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '发布失败')
    }
  }

  const handleUnpublish = async (lab: LabAdminView) => {
    try {
      await unpublishLab(lab.code)
      message.success('任务已下架')
      await refresh()
    } catch {
      message.error('下架失败')
    }
  }

  const handleDecision = (approval: ApprovalView, approve: boolean) => {
    setDecisionNote('')
    modal.confirm({
      title: approve ? '批准发布' : '驳回申请',
      content: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            {approval.requestedByName} 申请发布 {approval.targetCode}：{approval.reason}
          </Typography.Text>
          <Input.TextArea rows={3} placeholder="审批意见（可选）" onChange={(event) => setDecisionNote(event.target.value)} />
        </Space>
      ),
      okText: approve ? '批准' : '驳回',
      okButtonProps: { danger: !approve },
      onOk: async () => {
        try {
          const result = await decideApproval(approval.id, approve, decisionNote)
          message.success(result.message)
          await refresh()
        } catch (error) {
          const detail = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
          message.error(detail || '审批失败')
        }
      }
    })
  }

  const labColumns: TableColumnsType<LabAdminView> = [
    { title: '编码', dataIndex: 'code', width: 190 },
    { title: '标题', dataIndex: 'title' },
    { title: '层级', dataIndex: 'level', width: 70 },
    { title: '状态', key: 'status', width: 100, render: (_, row) => row.status === 'published' ? <Tag color="success">已发布</Tag> : <Tag>草稿</Tag> },
    {
      title: '审核', key: 'review', width: 100,
      render: (_, row) => <Tag color={reviewMeta[row.reviewStatus]?.color}>{reviewMeta[row.reviewStatus]?.text ?? row.reviewStatus}</Tag>
    },
    { title: '版本', dataIndex: 'version', width: 60 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (value: string) => value ? formatDateTime(value) : '—' },
    {
      title: '操作', key: 'actions', width: 300,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" icon={<EditOutlined />} disabled={row.status === 'published'}
            title={row.status === 'published' ? '已发布任务需先下架再编辑' : undefined}
            onClick={() => openEdit(row)}>编辑</Button>
          {row.reviewStatus !== 'approved' && (
            <Button size="small" icon={<CheckOutlined />} onClick={() => void handleReview(row, true)}>通过</Button>
          )}
          {row.reviewStatus !== 'rejected' && (
            <Button size="small" danger icon={<CloseOutlined />} onClick={() => void handleReview(row, false)}>驳回</Button>
          )}
          {row.status !== 'published' && row.reviewStatus === 'approved' && (
            <Button size="small" type="primary" onClick={() => void handlePublish(row)}>发布</Button>
          )}
          {row.status === 'published' && (
            <Popconfirm title="下架后学员将看不到该任务" onConfirm={() => void handleUnpublish(row)}>
              <Button size="small" icon={<StopOutlined />}>下架</Button>
            </Popconfirm>
          )}
        </Space>
      )
    }
  ]

  const approvalColumns: TableColumnsType<ApprovalView> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '类型', dataIndex: 'type', width: 150 },
    { title: '目标', dataIndex: 'targetCode', width: 190 },
    { title: '申请人', dataIndex: 'requestedByName', width: 120 },
    { title: '原因', dataIndex: 'reason' },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag color={value === 'pending' ? 'orange' : value === 'approved' ? 'green' : 'red'}>{value}</Tag> },
    { title: '申请时间', dataIndex: 'createdAt', width: 170, render: (value: string) => value ? formatDateTime(value) : '—' },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_, row) => row.status === 'pending' ? (
        <Space>
          <Button size="small" type="primary" onClick={() => handleDecision(row, true)}>批准</Button>
          <Button size="small" danger onClick={() => handleDecision(row, false)}>驳回</Button>
        </Space>
      ) : row.decidedByName ? `${row.decidedByName} 已处理` : '—'
    }
  ]

  const analyticsColumns: TableColumnsType<LabAnalytics> = [
    { title: '任务', dataIndex: 'title' },
    { title: '层级', dataIndex: 'level', width: 70 },
    { title: '提交次数', dataIndex: 'attempts', width: 100 },
    { title: '学员数', dataIndex: 'learners', width: 90 },
    { title: '通过人数', dataIndex: 'passedLearners', width: 100 },
    { title: '平均分', dataIndex: 'averageScore', width: 90 },
    { title: '主要失败类型', dataIndex: 'topFailure', width: 160, render: (value?: string) => value ?? '—' }
  ]

  const datasetColumns: TableColumnsType<DatasetManifestView> = [
    { title: '数据集', dataIndex: 'code' },
    { title: '版本', dataIndex: 'version', width: 70 },
    { title: '表', dataIndex: 'tableName', width: 190 },
    { title: '行数', dataIndex: 'rowCount', width: 80 },
    { title: '许可', dataIndex: 'license', width: 140 },
    { title: '来源', dataIndex: 'source', width: 110 },
    { title: 'PII 复核', dataIndex: 'piiReviewed', width: 100, render: (value: boolean) => value ? <Tag color="green">已复核</Tag> : <Tag color="orange">未复核</Tag> },
    { title: '状态', dataIndex: 'status', width: 90 },
    {
      title: '一致性', key: 'verification', width: 140,
      render: (_, row) => {
        const result = verifications[row.code]
        if (!result) return <Tag>未校验</Tag>
        if (result.rowCountMatches && result.checksumMatches) return <Tag color="green">一致</Tag>
        return <Tag color="red">
          {!result.rowCountMatches && !result.checksumMatches ? '行数与校验和漂移'
            : !result.rowCountMatches ? '行数漂移' : '校验和漂移'}
        </Tag>
      }
    },
    { title: '备注', dataIndex: 'note', ellipsis: true }
  ]

  const overview = analytics?.overview
  const pending = useMemo(() => approvals.filter((item) => item.status === 'pending').length, [approvals])

  return (
    <>
      <PageHeader
        description="草稿、审核、发布与 MCP 审批都在这里闭环。发布前必须通过内容审核，MCP 只能发起审批、不能直接写内容。"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={12} className="stat-row">
        <Col span={4}><StatCard title="已发布任务" value={String(overview?.publishedLabs ?? 0)} icon={<CheckCircleOutlined />} tone="green" /></Col>
        <Col span={4}><StatCard title="总提交" value={String(overview?.submissions ?? 0)} icon={<SendOutlined />} /></Col>
        <Col span={4}><StatCard title="学员数" value={String(overview?.learners ?? 0)} icon={<TeamOutlined />} /></Col>
        <Col span={4}><StatCard title="整体通过率" value={`${overview?.passRate ?? 0}%`} icon={<TrophyOutlined />} tone="green" /></Col>
        <Col span={4}><StatCard title="待审批" value={String(pending)} icon={<ClockCircleOutlined />} tone="orange" /></Col>
        <Col span={4}><StatCard title="活跃沙箱" value={String(overview?.activeSandboxes ?? 0)} icon={<CloudServerOutlined />} /></Col>
      </Row>

      <Tabs
        items={[
          {
            key: 'content',
            label: '内容库',
            children: (
              <DataSurface
                title="任务内容库"
                description="草稿、审核与发布状态一览；发布前必须通过内容审核，已发布任务需先下架再编辑。"
                label="任务内容库"
                extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建任务</Button>}
              >
                <Table rowKey="code" size="small" loading={loading} columns={labColumns} dataSource={labs}
                  pagination={{ pageSize: 10 }} scroll={{ x: 1200 }} />
              </DataSurface>
            )
          },
          {
            key: 'approvals',
            label: `审批（${pending}）`,
            children: (
              <DataSurface
                title="发布审批"
                description="MCP 只能发起审批，不能直接写内容；审批通过后才可发布。"
                label="发布审批"
              >
                {approvals.length === 0 ? <Empty description="暂无审批单" /> : (
                  <Table rowKey="id" size="small" columns={approvalColumns} dataSource={approvals}
                    pagination={{ pageSize: 10 }} scroll={{ x: 1100 }} />
                )}
              </DataSurface>
            )
          },
          {
            key: 'analytics',
            label: '运营分析',
            children: (
              <DataSurface
                title="运营分析"
                description="基于真实提交的任务通过率、学员规模与主要失败类型。"
                label="运营分析"
              >
                <Table rowKey="labCode" size="small" columns={analyticsColumns}
                  dataSource={analytics?.labs ?? []} pagination={false} />
              </DataSurface>
            )
          },
          {
            key: 'datasets',
            label: '数据样例',
            children: (
              <DataSurface
                title="样例数据集"
                description="任务引用的数据来源、版本、许可与 PII 复核状态；真实脱敏样例按新版本登记。"
                label="样例数据集"
                extra={(
                  <Space size={8}>
                    <Button loading={verifying} onClick={() => void verifyDatasets()}>校验一致性</Button>
                    <Button type="primary" onClick={openRegister}>登记新版本</Button>
                  </Space>
                )}
              >
                <Table rowKey={(row) => `${row.code}@${row.version}`} size="small" columns={datasetColumns}
                  dataSource={datasets} pagination={false} />
              </DataSurface>
            )
          }
        ]}
      />

      <Modal
        title="登记数据集版本"
        open={registerOpen}
        onCancel={() => setRegisterOpen(false)}
        onOk={() => void submitRegister()}
        confirmLoading={registering}
        okText="登记"
      >
        <Form form={datasetForm} layout="vertical">
          <Form.Item name="tableName" label="教学表名" rules={[{ required: true, message: '请输入 teach schema 下的表名' }]}>
            <Input placeholder="teach.orders_raw" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}><Form.Item name="version" label="版本"><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={8}><Form.Item name="license" label="许可" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={8}><Form.Item name="source" label="来源" rules={[{ required: true }]}><Input /></Form.Item></Col>
          </Row>
          <Form.Item name="piiReviewed" label="PII 已复核" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="note" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editing ? `编辑任务 ${editing.code}` : '新建任务'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
        width={760}
        okText="保存草稿"
      >
        <Form form={form} layout="vertical">
          <Row gutter={12}>
            <Col span={8}><Form.Item name="code" label="任务编码" rules={[{ required: true }]}><Input disabled={!!editing} placeholder="l3-example" /></Form.Item></Col>
            <Col span={8}><Form.Item name="moduleCode" label="所属模块" rules={[{ required: true }]}>
              <Select options={moduleOptions} /></Form.Item></Col>
            <Col span={8}><Form.Item name="projectCode" label="所属项目（可选）">
              <Select allowClear options={[
                { value: 'proj-order-analytics', label: '订单分析端到端项目' },
                { value: 'proj-quality-production', label: '质量与生产化项目' }
              ]} /></Form.Item></Col>
          </Row>
          <Form.Item name="title" label="标题" rules={[{ required: true }]}><Input /></Form.Item>
          <Row gutter={12}>
            <Col span={6}><Form.Item name="level" label="层级"><Select options={['L1', 'L2', 'L3', 'L4', 'L5', 'L6'].map((value) => ({ value }))} /></Form.Item></Col>
            <Col span={6}><Form.Item name="difficulty" label="难度"><Select options={['easy', 'medium', 'hard'].map((value) => ({ value }))} /></Form.Item></Col>
            <Col span={6}><Form.Item name="passScore" label="通过线"><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item></Col>
            <Col span={6}><Form.Item name="estimatedMinutes" label="预计分钟"><InputNumber min={5} max={240} style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Form.Item name="summary" label="简介"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="objective" label="验收标准"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="expectedSql" label="标准答案（仅管理员可见）" rules={[{ required: true, message: '审核前必须提供标准答案' }]}>
            <Input.TextArea rows={6} style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--font-table)' }} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
