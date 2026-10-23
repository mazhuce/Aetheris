import { useEffect, useState } from 'react'
import { App, Checkbox, Form, Input, InputNumber, Modal, Select, Typography } from 'antd'
import { getRecoveryDefinition, reconcileExecution, type Reconciliation } from '@/api/executionRecovery'

interface Props {
  kind: 'ingestion' | 'quality'
  id: number | undefined
  onClose: () => void
  onResolved: () => void | Promise<void>
}

export default function ExecutionReconciliationModal({ kind, id, onClose, onResolved }: Props) {
  const { message } = App.useApp()
  const [form] = Form.useForm<Reconciliation>()
  const [saving, setSaving] = useState(false)
  const [snapshot, setSnapshot] = useState<string>()
  const [definitionError, setDefinitionError] = useState(false)
  const status = Form.useWatch('status', form)
  useEffect(() => {
    if (id === undefined) return
    form.resetFields()
    setSnapshot(undefined)
    setDefinitionError(false)
    let active = true
    void getRecoveryDefinition(kind, id).then((run) => {
      if (active) setSnapshot(run.executionSnapshot)
    }).catch(() => { if (active) setDefinitionError(true) })
    return () => { active = false }
  }, [form, id, kind])

  const save = async () => {
    let values: Reconciliation
    try { values = await form.validateFields() } catch { return }
    if (id === undefined || snapshot === undefined) return
    setSaving(true)
    try {
      await reconcileExecution(kind, id, values)
      message.success('对账结果已保存')
      onClose()
      await onResolved()
    } catch (error) {
      const failure = error as { response?: { data?: { message?: string } } }
      message.error(failure.response?.data?.message ?? '对账保存失败，请刷新记录后核实')
    } finally { setSaving(false) }
  }

  return <Modal open={id !== undefined} title={`核实运行结果 #${id ?? ''}`} onCancel={() => { if (!saving) onClose() }}
    onOk={() => void save()} okText="保存对账结果" confirmLoading={saving} okButtonProps={{ disabled: snapshot === undefined }} cancelButtonProps={{ disabled: saving }}
    closable={!saving} maskClosable={!saving} keyboard={!saving} destroyOnHidden>
    <Typography.Paragraph type="secondary">
      请先核实目标库、远端执行与已有结果。保存后会解除该任务的执行阻塞，后续调度可能再次运行。
    </Typography.Paragraph>
    {snapshot && <details><summary>执行时的定义</summary><Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 180, overflow: 'auto' }}>{snapshot === '{}' ? '旧版本未保存定义，请使用原始作业日志及目标库记录核实。' : snapshot}</Typography.Paragraph></details>}
    {definitionError && <Typography.Paragraph type="danger">执行定义读取失败，请关闭并刷新记录后重试。</Typography.Paragraph>}
    <Form form={form} layout="vertical" preserve={false}>
      <Form.Item name="status" label="核实结果" rules={[{ required: true, message: '请选择核实后的结果' }]}>
        <Select options={kind === 'ingestion'
          ? [{ value: 'success', label: '确认成功' }, { value: 'failed', label: '确认未成功' }]
          : [{ value: 'passed', label: '确认质量通过' }, { value: 'failed', label: '确认质量未通过' }, { value: 'error', label: '确认检查未完成' }]} />
      </Form.Item>
      {kind === 'ingestion' && status === 'success' && <>
        <Form.Item name="rowsRead" label="核实读取行数" rules={[{ required: true }]}><InputNumber min={0} max={Number.MAX_SAFE_INTEGER} precision={0} /></Form.Item>
        <Form.Item name="rowsWritten" label="核实写入行数" rules={[{ required: true }]}><InputNumber min={0} max={Number.MAX_SAFE_INTEGER} precision={0} /></Form.Item>
      </>}
      {kind === 'quality' && status && status !== 'error' && <Form.Item name="violations" label="核实违规数" rules={[{ required: true }]}>
        <InputNumber min={0} max={Number.MAX_SAFE_INTEGER} precision={0} />
      </Form.Item>}
      <Form.Item name="evidence" label="对账依据" rules={[{ required: true }, { min: 16, max: 2000, whitespace: true, message: '请填写16至2000字的核实依据' }]}>
        <Input.TextArea rows={4} maxLength={2000} placeholder="记录已核实的目标结果、事务或作业标识、核实方式；请勿填写密码。" />
      </Form.Item>
      <Form.Item name="confirmedRemoteStopped" valuePropName="checked" rules={[{
        validator: (_, value) => value === true ? Promise.resolve() : Promise.reject(new Error('请先确认旧执行已结束'))
      }]}>
        <Checkbox>已确认旧进程和远端执行均已结束，可安全解除阻塞</Checkbox>
      </Form.Item>
    </Form>
  </Modal>
}
