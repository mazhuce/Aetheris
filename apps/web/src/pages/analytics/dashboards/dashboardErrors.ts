export function dashboardErrorMessage(error: unknown, fallback: string): string {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status === 403) return '没有访问此看板的权限（403）。请检查当前账号和资源授权。'
  if (status === 404) return '看板不存在、已下线或发布版本不可用（404）。'
  if (status === 503) return '看板服务暂时不可用（503）。请稍后重试；这不是空数据。'
  return fallback
}
