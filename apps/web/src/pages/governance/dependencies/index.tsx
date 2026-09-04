import { useEffect, useState } from 'react'
import { Alert, Table, Tag } from 'antd'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { getSemanticDependencyGraph, type SemanticDependencyGraph } from '@/api/metadata'

export default function SemanticDependencyPage() {
  const [graph, setGraph] = useState<SemanticDependencyGraph>(); const [error, setError] = useState(false)
  useEffect(() => { void getSemanticDependencyGraph().then(setGraph).catch(() => setError(true)) }, [])
  return <div><PageHeader description="语义资产依赖图 · 查看指标、维度、模型与受管数据表之间的影响关系" />{error && <Alert type="warning" showIcon message="依赖图加载失败，请检查权限或服务状态" />}<DataSurface title="依赖节点"><Table rowKey="id" pagination={{ pageSize: 10 }} dataSource={graph?.nodes ?? []} columns={[{ title: '节点', dataIndex: 'label' }, { title: '类型', dataIndex: 'type', render: (v: string) => <Tag>{v}</Tag> }, { title: '关联数', render: (_: unknown, node: { id: string }) => graph?.edges.filter((edge) => edge.from === node.id || edge.to === node.id).length ?? 0 }]} /></DataSurface></div>
}
