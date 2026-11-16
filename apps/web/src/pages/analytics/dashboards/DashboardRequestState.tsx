import { Alert, Button, Empty } from 'antd'

export function DashboardLoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="dashboard-request-state">
      <Alert
        type="error"
        showIcon
        message="看板加载失败"
        description={message}
        action={<Button onClick={onRetry}>重试</Button>}
      />
    </div>
  )
}

export function DashboardEmptyState({ message }: { message: string }) {
  return (
    <div className="dashboard-request-state">
      <Empty description={message} />
    </div>
  )
}
