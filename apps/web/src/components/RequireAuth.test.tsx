import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import RequireAuth from './RequireAuth'
import { TOKEN_KEY } from '@/api/http'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/system/users"
          element={
            <RequireAuth>
              <div>protected-content</div>
            </RequireAuth>
          }
        />
        <Route path="/login" element={<div>login-page</div>} />
      </Routes>
    </MemoryRouter>
  )
}

describe('RequireAuth', () => {
  it('已登录时渲染子内容', () => {
    localStorage.setItem(TOKEN_KEY, 't-123')
    renderAt('/system/users')
    expect(screen.getByText('protected-content')).toBeInTheDocument()
  })

  it('未登录时重定向到 /login', () => {
    renderAt('/system/users')
    expect(screen.getByText('login-page')).toBeInTheDocument()
    expect(screen.queryByText('protected-content')).not.toBeInTheDocument()
  })
})
