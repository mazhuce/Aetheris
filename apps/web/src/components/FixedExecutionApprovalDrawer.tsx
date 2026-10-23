import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Alert, App, Button, Checkbox, Descriptions, Drawer, Space, Spin, Typography } from 'antd'
import { getAuthSnapshot, subscribeAuth } from '@/auth/authState'
import { getTask, getTaskApproval, approveTask, bindTaskCron, type IngestionTask } from '@/api/ingestion'
import { getRule, getRuleApproval, approveRule, bindRuleCron, type QualityRule } from '@/api/quality'
import type { ExecutionApproval } from '@/api/executionApproval'
import { executionApprovalError } from '@/utils/executionApprovalError'

type Kind = 'ingestion' | 'quality'
interface Props {
  kind: Kind
  resourceId: number | null
  refreshToken: number
  canApprove: boolean
  canBindCron: boolean
  onClose: () => void
}
interface Snapshot {
  id: number
  kind: Kind
  subject: ReturnType<typeof getAuthSnapshot>
  refreshToken: number
  approval: ExecutionApproval
  definition: IngestionTask | QualityRule
}

/** Definition is reviewed between two equal server revisions; no stale list row can approve unseen SQL. */
export default function FixedExecutionApprovalDrawer({ kind, resourceId, refreshToken, canApprove, canBindCron, onClose }: Props) {
  const { message } = App.useApp()
  const subject = useSyncExternalStore(subscribeAuth, getAuthSnapshot, getAuthSnapshot)
  const active = useRef({ id: resourceId, kind, subject, refreshToken, mounted: true })
  active.current = { id: resourceId, kind, subject, refreshToken, mounted: active.current.mounted }
  const epoch = useRef(0)
  const action = useRef<object>()
  const [snapshot, setSnapshot] = useState<Snapshot>()
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [reviewed, setReviewed] = useState(false)
  const [loadError, setLoadError] = useState<string>()
  const [actionError, setActionError] = useState<string>()
  const matches = (id: number | null, source: Kind, account: typeof subject, token: number) => {
    const current = active.current
    return current.mounted && current.id === id && current.kind === source && current.subject === account && current.refreshToken === token
  }

  useEffect(() => { active.current.mounted = true; return () => { active.current.mounted = false; epoch.current++ } }, [])
  const reload = useCallback(async () => {
    if (resourceId == null || subject.status !== 'authenticated') return
    const request = ++epoch.current
    setSnapshot(undefined); setReviewed(false); setLoadError(undefined); setLoading(true)
    try {
      const getApproval = kind === 'ingestion' ? getTaskApproval : getRuleApproval
      const before = await getApproval(resourceId)
      if (!matches(resourceId, kind, subject, refreshToken) || epoch.current !== request) return
      const definition = kind === 'ingestion' ? await getTask(resourceId) : await getRule(resourceId)
      if (!matches(resourceId, kind, subject, refreshToken) || epoch.current !== request) return
      const after = await getApproval(resourceId)
      if (!matches(resourceId, kind, subject, refreshToken) || epoch.current !== request) return
      if (definition.id !== resourceId || before.executionRevision !== after.executionRevision || before.definitionSha256 !== after.definitionSha256
        || before.scheduleGeneration !== after.scheduleGeneration || before.bindingGeneration !== after.bindingGeneration || before.approved !== after.approved || before.cronBound !== after.cronBound) {
        throw { response: { status: 409, data: { message: '读取期间执行定义或定时状态已变化，请重新加载后核对' } } }
      }
      setSnapshot({ id: resourceId, kind, subject, refreshToken, approval: after, definition })
    } catch (error) {
      if (matches(resourceId, kind, subject, refreshToken) && epoch.current === request) setLoadError(executionApprovalError(error))
    } finally {
      if (matches(resourceId, kind, subject, refreshToken) && epoch.current === request) setLoading(false)
    }
  }, [kind, resourceId, subject, refreshToken])

  useEffect(() => {
    epoch.current++; action.current = undefined
    setSnapshot(undefined); setReviewed(false); setBusy(false); setActionError(undefined); setLoadError(undefined); setLoading(false)
    void reload()
    return () => { epoch.current++ }
  }, [reload])

  const current = snapshot && matches(snapshot.id, snapshot.kind, snapshot.subject, snapshot.refreshToken) ? snapshot : undefined
  const state = current?.approval
  const definition = current?.definition
  const cron = definition && (kind === 'ingestion' ? (definition as IngestionTask).scheduleCron : (definition as QualityRule).cronExpression)
  const bindingReady = state?.approved && definition?.status === 'enabled' && !!cron?.trim() && state.scheduleGeneration > 0
  const mutate = async (operation: 'approve' | 'bind') => {
    if (!current || !reviewed || action.current || (operation === 'approve' ? !canApprove : !canBindCron || !bindingReady)) return
    const pending = {}; action.current = pending; setBusy(true); setActionError(undefined)
    try {
      if (operation === 'approve') await (kind === 'ingestion' ? approveTask : approveRule)(current.id, current.approval.executionRevision)
      else await (kind === 'ingestion' ? bindTaskCron : bindRuleCron)(current.id, current.approval.executionRevision, current.approval.scheduleGeneration)
      if (action.current !== pending || !matches(current.id, current.kind, current.subject, current.refreshToken)) return
      message.success(operation === 'approve' ? '批准操作已完成，正在核对最新状态' : '定时绑定操作已完成，正在核对最新状态')
      await reload()
    } catch (error) {
      if (action.current !== pending || !matches(current.id, current.kind, current.subject, current.refreshToken)) return
      setActionError(executionApprovalError(error, '操作未确认成功，请刷新核对状态'))
      await reload()
    } finally {
      if (action.current === pending) { action.current = undefined; if (active.current.mounted) setBusy(false) }
    }
  }

  return <Drawer title={kind === 'ingestion' ? '接入任务执行批准' : '质量规则执行批准'} open={resourceId !== null} onClose={onClose} width={640}
    extra={<Button onClick={() => void reload()} disabled={loading || busy || subject.status !== 'authenticated'}>刷新状态</Button>}>
    {loading && <Spin tip="正在核对当前执行定义"><div style={{ minHeight: 80 }} /></Spin>}
    {loadError && <Alert type="error" showIcon message={loadError} />}
    {actionError && <Alert type="warning" showIcon message={actionError} style={{ marginBottom: 16 }} />}
    {current && state && definition && <>
      <Descriptions size="small" column={1} bordered items={[
        { key: 'name', label: '名称', children: definition.name },
        { key: 'revision', label: '执行版本', children: state.executionRevision },
        { key: 'approval', label: '执行批准', children: state.approved ? '已批准' : '未批准' },
        { key: 'cron', label: '定时账号绑定', children: state.cronBound ? '已绑定' : '未绑定' },
        { key: 'generation', label: '调度版本 / 绑定版本', children: `${state.scheduleGeneration} / ${state.bindingGeneration}` },
        { key: 'hash', label: '定义摘要', children: <Typography.Text code style={{ overflowWrap: 'anywhere' }}>{state.definitionSha256}</Typography.Text> }
      ]} />
      {!state.approved && <Alert type="info" showIcon message="当前执行版本尚未批准，手动运行与定时执行均不会自动批准。" style={{ marginTop: 16 }} />}
      <Typography.Paragraph style={{ marginTop: 16 }}>批准前请核对下方已保存的执行定义。后端会再次检查当前版本及底层数据源权限。</Typography.Paragraph>
      <Descriptions size="small" column={1} bordered items={kind === 'ingestion' ? [
        { key: 'source', label: '读取数据源', children: `#${(definition as IngestionTask).sourceDatasourceId ?? '未配置'}` },
        { key: 'target', label: '写入数据源', children: `#${(definition as IngestionTask).targetDatasourceId ?? '未配置'}` },
        { key: 'table', label: '写入目标表', children: definition.targetTable || '未配置' },
        { key: 'mode', label: '写入模式 / 批次', children: `${(definition as IngestionTask).writeMode === 'replace' ? '全量替换' : '追加'} / ${(definition as IngestionTask).batchSize ?? 500}` },
        { key: 'sql', label: '来源 SQL', children: <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 240, overflow: 'auto', marginBottom: 0 }}>{(definition as IngestionTask).sourceSql || '未配置'}</Typography.Paragraph> }
      ] : [
        { key: 'source', label: '读取数据源', children: `#${(definition as QualityRule).datasourceId ?? '未配置'}` },
        { key: 'table', label: '校验目标表', children: definition.targetTable || '未配置' },
        { key: 'type', label: '规则类型', children: (definition as QualityRule).ruleType },
        { key: 'threshold', label: '违规数阈值', children: (definition as QualityRule).threshold ?? 0 },
        { key: 'expression', label: '有效数据表达式 / SQL', children: <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 240, overflow: 'auto', marginBottom: 0 }}>{(definition as QualityRule).expression || '未配置'}</Typography.Paragraph> }
      ]} />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>定时调度：{cron || '未设置'}。{state.approved ? '再次批准会清除现有定时账号绑定。' : ''}绑定操作会替换现有定时执行账号。</Typography.Paragraph>
      {(canApprove || canBindCron) && <>
        <Checkbox checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} disabled={busy}>我已核对以上已保存的执行定义</Checkbox>
        <Space wrap style={{ marginTop: 12 }}>
          {canApprove && <Button onClick={() => void mutate('approve')} loading={busy} disabled={!reviewed || busy}>批准当前版本</Button>}
          {canBindCron && <Button onClick={() => void mutate('bind')} loading={busy} disabled={!reviewed || busy || !bindingReady}>绑定当前账号运行定时任务</Button>}
        </Space>
        {!bindingReady && canBindCron && <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>定时绑定要求当前版本已批准、状态启用且已配置有效 cron。</Typography.Paragraph>}
      </>}
    </>}
  </Drawer>
}
