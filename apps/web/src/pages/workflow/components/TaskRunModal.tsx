import { useEffect, useState } from 'react'
import { Alert, App, DatePicker, Descriptions, Form, Modal, Tag } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { runTask, INSTANCE_STATUS_META, type TaskInstance, type TaskView } from '@/api/workflow'

interface Props {
  target: TaskView | null
  onClose: () => void
  onFinished: () => void
}

/**
 * 手动触发任务。
 *
 * 默认业务日期取昨天，与定时触发口径一致——数仓通常加工前一天的完整数据。
 * 补历史数据时改这里的日期即可，不必修改任务 SQL。
 */
export default function TaskRunModal({ target, onClose, onFinished }: Props) {
  const { message } = App.useApp()
  const [form] = Form.useForm<{ bizDate: Dayjs }>()
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<TaskInstance | null>(null)

  useEffect(() => {
    if (target) {
      form.setFieldsValue({ bizDate: dayjs().subtract(1, 'day') })
      setResult(null)
    }
  }, [target, form])

  const handleRun = async () => {
    const values = await form.validateFields()
    if (!target) return

    setRunning(true)
    setResult(null)
    try {
      const instance = await runTask(target.task.id, values.bizDate.format('YYYY-MM-DD'))
      setResult(instance)

      // 三种结果分别提示：skipped 不是失败，而是依赖未就绪，用 warning 区分开。
      if (instance.status === 'success') {
        message.success('任务执行成功')
      } else if (instance.status === 'skipped') {
        message.warning('依赖未就绪，任务已跳过')
      } else {
        message.error('任务执行失败，请查看日志')
      }
      onFinished()
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      // 409 表示同一业务日期已有实例在跑，属于冲突而非故障。
      message.error(detail || '触发失败')
    } finally {
      setRunning(false)
    }
  }

  return (
    <Modal
      title={`运行任务：${target?.task.name ?? ''}`}
      open={target !== null}
      onCancel={onClose}
      onOk={() => void handleRun()}
      confirmLoading={running}
      okText={running ? '执行中…' : '开始执行'}
      width={560}
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="重跑会覆盖目标分区"
        description="同一业务日期重复执行会复用同一实例并覆盖该分区的数据，不会产生重复记录。"
      />

      <Form form={form} layout="vertical">
        <Form.Item
          name="bizDate"
          label="业务日期"
          rules={[{ required: true, message: '请选择业务日期' }]}
          extra="即目标表的分区日期（dt），不是执行时刻。默认为昨天。"
        >
          <DatePicker
            style={{ width: '100%' }}
            // 不允许选未来日期：那些分区的源数据还不存在，执行只会得到空结果。
            disabledDate={(current) => current && current > dayjs().endOf('day')}
          />
        </Form.Item>
      </Form>

      {result && (
        <Descriptions size="small" bordered column={1} style={{ marginTop: 8 }}>
          <Descriptions.Item label="执行结果">
            <Tag color={INSTANCE_STATUS_META[result.status]?.color}>
              {INSTANCE_STATUS_META[result.status]?.label ?? result.status}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="业务日期">{result.bizDate}</Descriptions.Item>
          <Descriptions.Item label="尝试次数">{result.attempt ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="耗时">
            {result.durationMs != null ? `${result.durationMs} ms` : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="影响行数">
            {/* Hive 驱动不返回行数时为 -1，显示「未知」比显示 -1 或 0 都更准确 */}
            {result.affectedRows == null || result.affectedRows < 0
              ? '未知（引擎未返回）'
              : result.affectedRows}
          </Descriptions.Item>
          {result.errorMessage && (
            <Descriptions.Item label="原因">
              <span style={{ color: result.status === 'skipped' ? '#d46b08' : '#cf1322' }}>
                {result.errorMessage}
              </span>
            </Descriptions.Item>
          )}
        </Descriptions>
      )}
    </Modal>
  )
}
