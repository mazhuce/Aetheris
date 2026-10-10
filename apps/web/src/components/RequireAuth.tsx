import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { Navigate } from 'react-router-dom'
import { bootstrapSession } from '@/auth/session'
import { useAuthSession } from '@/hooks/useAuthSession'

export default function RequireAuth({ children }: { children: ReactElement }) {
  const session = useAuthSession()
  useEffect(() => {
    if (session.status === 'unknown') void bootstrapSession()
  }, [session.status])

  if (session.status === 'unknown' || session.status === 'loading') {
    return <div className="route-loading">正在验证登录状态…</div>
  }
  if (session.status !== 'authenticated') {
    return <Navigate to="/login" replace />
  }
  return children
}
