import type { ReactElement } from 'react'
import { Navigate } from 'react-router-dom'
import type { UserRole } from '@/api/auth'
import { useAuthSession } from '@/hooks/useAuthSession'

export default function RequireRole({
  children,
  role
}: {
  children: ReactElement
  role: UserRole
}) {
  const session = useAuthSession()
  if (session.status !== 'authenticated' || session.user.role !== role) {
    return <Navigate to="/workbench" replace />
  }
  return children
}
