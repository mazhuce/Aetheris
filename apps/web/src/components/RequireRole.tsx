import type { ReactElement } from 'react'
import { Navigate } from 'react-router-dom'
import { ROLE_KEY } from '@/api/http'
import type { UserRole } from '@/api/auth'

export default function RequireRole({
  children,
  role
}: {
  children: ReactElement
  role: UserRole
}) {
  if (localStorage.getItem(ROLE_KEY) !== role) {
    return <Navigate to="/workbench" replace />
  }
  return children
}
