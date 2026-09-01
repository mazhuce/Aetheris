import { Button } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import StatusBadge from '@/components/StatusBadge'
import { EmptyState } from '@/components/FeedbackState'
import { findChild, firstAvailablePath } from '@/constants/navigation'
import { getCurrentRole } from '@/utils/access'

/** 未实现功能的统一正式占位页：由导航配置驱动标题与说明，禁止白屏/404/开发者化文案。 */
export default function Placeholder() {
  const location = useLocation()
  const navigate = useNavigate()
  const role = getCurrentRole()
  const matched = findChild(location.pathname)
  const module = matched?.module
  const child = matched?.child

  const description = child?.description ?? '该能力正在规划中，将按阶段逐步开放。'
  const backPath = module ? firstAvailablePath(module, role) : '/workbench'

  return (
    <>
      <PageHeader
        description={description}
        extra={<StatusBadge tone="neutral">规划中</StatusBadge>}
      />
      <EmptyState
        icon={module?.icon}
        title="该能力尚未启用"
        description={description}
        action={(
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(backPath)}>
            返回{module?.label ?? '工作台'}
          </Button>
        )}
      />
    </>
  )
}
