import { Card, Empty, Tag } from 'antd'
import { useLocation } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import { findNav } from '@/constants/navigation'

export default function Placeholder() {
  const location = useLocation()
  const { item } = findNav(location.pathname)

  return (
    <>
      <PageHeader
        title={item?.label ?? '未知模块'}
        description="该模块正在建设中，将按阶段逐步开放。"
        extra={<Tag color="processing">规划中</Tag>}
      />
      <Card>
        <Empty description="功能尚未开放" />
      </Card>
    </>
  )
}
