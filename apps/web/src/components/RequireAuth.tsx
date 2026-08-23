import type { ReactElement } from 'react'
import { Navigate } from 'react-router-dom'
import { TOKEN_KEY } from '@/api/http'

export default function RequireAuth({ children }: { children: ReactElement }) {
  if (!localStorage.getItem(TOKEN_KEY)) {
    return <Navigate to="/login" replace />
  }
  return children
}
