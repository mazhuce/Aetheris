import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import RequireRole from './RequireRole'
import { setAuthenticated } from '@/auth/authState'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/system/users"
          element={
            <RequireRole role="admin">
              <div>admin-content</div>
            </RequireRole>
          }
        />
        <Route path="/workbench" element={<div>workbench-page</div>} />
      </Routes>
    </MemoryRouter>
  )
}

describe('RequireRole', () => {
  it('角色匹配时渲染子内容', () => {
    setAuthenticated({ username: 'alice', role: 'admin' })
    renderAt('/system/users')
    expect(screen.getByText('admin-content')).toBeInTheDocument()
  })

  it('角色不匹配时重定向到 /workbench', () => {
    setAuthenticated({ username: 'alice', role: 'viewer' })
    renderAt('/system/users')
    expect(screen.getByText('workbench-page')).toBeInTheDocument()
    expect(screen.queryByText('admin-content')).not.toBeInTheDocument()
  })

  it('未设置角色时重定向到 /workbench', () => {
    renderAt('/system/users')
    expect(screen.getByText('workbench-page')).toBeInTheDocument()
  })
})
