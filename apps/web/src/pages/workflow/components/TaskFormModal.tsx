import { useEffect, useState } from 'react'
import { App, AutoComplete, Form, Input, InputNumber, Modal, Select } from 'antd'
import { createTask, updateTask, type TaskInput, type TaskView } from '@/api/workflow'
import { listDataSources, type DataSource } from '@/api/metadata'

interface Props {
  open: boolean
  editing: TaskView | null
  onClose: () => void
  onSaved: () => void
}

/** 常用 cron 预设，避免用户手写出错；Quartz 是 6 段格式（秒 分 时 日 月 周）。 */
const CRON_PRESETS = [
  { value: '0 30 2 * * ?', label: '每天 02:30' },
  { value: '0 0 3 * * ?', label: '每天 03:00' },
  { value: '0 0 * * * ?', label: '每小时' },
  { value: '0 0 4 ? * MON', label: '每周一 04:00' },
  { value: '0 0 5 1 * ?', label: '每月 1 号 05:00' }
]

export default function TaskFormModal({ open, editing, onClose, onSaved }: Props) {
  const { message } = App.useApp()
  const [form] = Form.useForm<TaskInput>()
  const [submitting, setSubmitting] = useState(false)
  const [dataSources, setDataSources] = useState<DataSource[]>([])

  useEffect(() => {
    if (!open) return
    if (editing) {
      form.setFieldsValue(editing.task)
    } else {
      form.resetFields()
      // 给出可用的默认值，减少新建时的必填项。
      form.setFieldsValue({ timeoutSeconds: 1800, maxRetries: 0, status: 'enabled' })
    }
    void (async () => {
      try {
        setDataSources(await listDataSources())
      } catch {
        message.error('数据源列表加载失败')
      }
    })()
  }, [open, editing, form, message])

  const handleSubmit = async (values: TaskInput) => {
    setSubmitting(true)
    try {
      // 冗余存一份数据源名称，任务列表就不必再联查 metadata。
      const selected = dataSources.find((d) => d.id === values.datasourceId)
      const payload = { ...values, datasourceName: selected?.name }

      if (editing) {
        await updateTask(editing.task.id, payload)
        message.success('任务已更新')
      } else {
        await createTask(payload)
        message.success('任务已创建')
      }
      onSaved()
      onClose()
    } catch (error) {
      // 后端会返回 cron 不合法等具体原因，直接透出比笼统的「保存失败」有用。
      const detail = (error as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      message.error(detail || '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={editing ? '编辑任务' : '新建任务'}
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={submitting}
      width={760}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={(v) => void handleSubmit(v)}>
        <Form.Item
          name="name"
          label="任务名称"
          rules={[{ required: true, message: '请输入任务名称' }]}
        >
          <Input placeholder="例如：ODS 到 DWD 订单清洗" />
        </Form.Item>

        <Form.Item
          name="datasourceId"
          label="执行数据源"
          rules={[{ required: true, message: '请选择数据源' }]}
          extra="需要具备写权限的数据源；只读账号无法执行加工任务。"
        >
          <Select
            placeholder="选择执行任务的数据源"
            options={dataSources.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))}
          />
        </Form.Item>

        <Form.Item
          name="taskSql"
          label="加工 SQL"
          rules={[{ required: true, message: '请输入加工 SQL' }]}
          extra={
            <span>
              仅支持 <code>INSERT INTO / INSERT OVERWRITE / CREATE ... AS SELECT</code> 单条语句；
              可用 <code>${'{bizDate}'}</code> 占位符引用业务日期，执行时替换为实际日期。
            </span>
          }
        >
          <Input.TextArea
            rows={8}
            placeholder={"INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt='${bizDate}')\nSELECT ... FROM ods.ods_order WHERE dt = '${bizDate}'"}
            className="code-input"
          />
        </Form.Item>

        <Form.Item
          name="targetTable"
          label="目标表"
          extra="用于列表展示与血缘关联，例如 dwd.dwd_order。"
        >
          <Input placeholder="dwd.dwd_order" />
        </Form.Item>

        <Form.Item
          name="cronExpression"
          label="调度表达式"
          extra="Quartz 6 段格式（秒 分 时 日 月 周）。留空表示仅支持手动触发。定时执行的业务日期取前一天。"
        >
          {/*
            用 AutoComplete 而非 Select：既能从预设里选，也能直接手写表达式，
            且值始终是字符串。Select 的 tags 模式会把值变成数组，与后端的字符串字段不匹配。
          */}
          <AutoComplete
            allowClear
            placeholder="留空则仅手动触发，例如 0 30 2 * * ?"
            options={CRON_PRESETS.map((p) => ({ value: p.value, label: `${p.label}　${p.value}` }))}
            filterOption={(input, option) =>
              String(option?.value ?? '').includes(input)
              || String(option?.label ?? '').includes(input)}
          />
        </Form.Item>

        <Form.Item
          name="timeoutSeconds"
          label="超时时间（秒）"
          rules={[{ required: true, message: '请输入超时时间' }]}
        >
          <InputNumber min={1} max={86400} className="full-width-control" />
        </Form.Item>

        <Form.Item
          name="maxRetries"
          label="失败重试次数"
          rules={[{ required: true, message: '请输入重试次数' }]}
          extra="0 表示不重试。重试仍复用同一实例，只累加尝试次数。"
        >
          <InputNumber min={0} max={10} className="full-width-control" />
        </Form.Item>

        <Form.Item name="status" label="状态">
          <Select
            options={[
              { value: 'enabled', label: '启用' },
              { value: 'disabled', label: '停用（同时取消定时调度）' }
            ]}
          />
        </Form.Item>

        <Form.Item name="description" label="说明">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
