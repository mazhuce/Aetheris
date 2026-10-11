import { isRouteErrorResponse, useNavigate, useRouteError } from 'react-router-dom'

/**
 * 数据路由或懒加载 chunk 失败时的局部兜底页。
 * 保留当前应用壳，避免一次模块加载失败把用户留在空白页面。
 */
export default function RouteErrorBoundary() {
  const error = useRouteError()
  const navigate = useNavigate()
  const status = isRouteErrorResponse(error) ? error.status : undefined
  const message = isRouteErrorResponse(error)
    ? error.statusText || `请求失败（${error.status}）`
    : error instanceof Error && error.message
      ? error.message
      : '页面加载失败，请重试。'

  return (
    <main className="route-error" role="alert">
      <div className="route-error-card">
        <span className="route-error-code">{status ?? 'ERROR'}</span>
        <h1>页面暂时不可用</h1>
        <p>{message}</p>
        <div className="route-error-actions">
          <button type="button" onClick={() => window.location.reload()}>重新加载</button>
          <button type="button" className="secondary" onClick={() => navigate('/workbench')}>返回工作台</button>
        </div>
      </div>
    </main>
  )
}
