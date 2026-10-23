/** Keep authorization, dependency failure and revision conflict distinct in this workflow. */
export function executionApprovalError(error: unknown, fallback = '执行批准状态加载失败'): string {
  const response = (error as { response?: { status?: number; data?: { message?: unknown } } })?.response
  if (response?.status === 403) return '没有执行此操作所需的资源权限，请联系授权管理员'
  if (response?.status === 503) return '权限或执行服务暂不可用，请稍后重试'
  if (response?.status === 409) {
    const reason = response.data?.message
    return typeof reason === 'string' && reason.trim() && reason.length <= 300 && !/[\u0000-\u001f]/.test(reason)
      ? `${reason}；请刷新核对批准、定时绑定及运行记录`
      : '当前状态冲突，可能为版本变化、执行中或待对账占槽、租约失效，请刷新核对'
  }
  if (response?.status === 404) return '任务或规则已不存在，请刷新列表'
  if (response?.status === 401) return '当前登录状态已失效，请重新登录'
  return fallback
}
