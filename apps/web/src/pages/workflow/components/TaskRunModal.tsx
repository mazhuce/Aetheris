import { useEffect, useRef, useState } from 'react'
import { Alert, App, DatePicker, Descriptions, Form, Modal } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { getBusinessCalendar, runTask, INSTANCE_STATUS_META, type BusinessCalendarView, type TaskInstance, type TaskView } from '@/api/workflow'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'

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
  const [calendar, setCalendar] = useState<BusinessCalendarView | null>(null)
  const epoch = useRef(0)

  useEffect(() => {
    const current = ++epoch.current
    setCalendar(null)
    setRunning(false)
    if (target) {
      form.resetFields()
      setResult(null)
      void getBusinessCalendar().then((value) => {
        if (current !== epoch.current) return
        if (!dayjs(value.previousDate).isValid() || !dayjs(value.today).isValid()) throw new Error('invalid calendar')
        setCalendar(value)
        form.setFieldsValue({ bizDate: dayjs(value.previousDate) })
      }).catch(() => { if (current === epoch.current) message.error('业务日期加载失败，请关闭后重试') })
    }
    return () => { epoch.current++ }
  }, [target, form, message])

  const handleRun = async () => {
    const values = await form.validateFields()
    if (!target) return
    const current = epoch.current

    setRunning(true)
    setResult(null)
    try {
      const instance = await runTask(target.task.id, values.bizDate.format('YYYY-MM-DD'))
      if (current !== epoch.current) return
      setResult(instance)

      // 三种结果分别提示：skipped 不是失败，而是依赖未就绪，用 warning 区分开。
      if (instance.status === 'success') {
        message.success('任务执行成功')
      } else if (instance.status === 'skipped') {
        message.warning('依赖未就绪，任务已跳过')
      } else if (instance.status === 'unknown') {
        message.warning('执行结果待核对，请先对账')
      } else if (['running', 'submitting', 'cancel_requested'].includes(instance.status)) {
        message.info('任务已受理，请在运行实例中查看进展')
      } else {
        message.error('任务执行失败，请查看日志')
      }
      onFinished()
    } catch (error) {
      if (current !== epoch.current) return
      const detail = (error as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      // 409 表示同一业务日期已有实例在跑，属于冲突而非故障。
      message.error(detail || '触发失败')
    } finally {
      if (current === epoch.current) setRunning(false)
    }
  }

  const resultTone = (status: TaskInstance['status']): StatusTone => {
    if (status === 'success') return 'success'
    if (status === 'failed') return 'danger'
    if (status === 'skipped') return 'warning'
    if (status === 'running' || status === 'submitting') return 'info'
    if (status === 'unknown' || status === 'cancel_requested') return 'warning'
    return 'neutral'
  }

  return (
    <Modal
      title={`运行任务：${target?.task.name ?? ''}`}
      open={target !== null}
      onCancel={() => { if (!running) onClose() }}
      closable={!running}
      maskClosable={!running}
      keyboard={!running}
      onOk={() => void handleRun()}
      confirmLoading={running}
      okButtonProps={{ disabled: !calendar || running }}
      okText={running ? '执行中…' : '开始执行'}
      width={560}
      destroyOnHidden
    >
      <Alert
        className="modal-notice"
        type="info"
        showIcon
        message="执行前确认业务日期和目标 SQL"
        description="同一业务日期复用同一实例，写入方式取决于任务 SQL。结果未知时应先对账，避免重复写入。"
      />

      <Form form={form} layout="vertical">
        <Form.Item
          name="bizDate"
          label="业务日期"
          rules={[{ required: true, message: '请选择业务日期' }]}
          extra={calendar ? `按 ${calendar.zone} 的业务时钟默认选择前一天，日期代表目标分区（dt）。` : '正在读取业务日期…'}
        >
          <DatePicker
            className="full-width-control"
            // 不允许选未来日期：那些分区的源数据还不存在，执行只会得到空结果。
            disabled={!calendar || running}
            disabledDate={(current) => !!calendar && !!current && current.format('YYYY-MM-DD') > calendar.today}
          />
        </Form.Item>
      </Form>

      {result && (
        <Descriptions className="task-run-result" size="small" column={1}>
          <Descriptions.Item label="执行结果">
            <StatusBadge tone={resultTone(result.status)}>
              {INSTANCE_STATUS_META[result.status]?.label ?? result.status}
            </StatusBadge>
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
              <span className="result-error">{result.errorMessage}</span>
            </Descriptions.Item>
          )}
        </Descriptions>
      )}
    </Modal>
  )
}
