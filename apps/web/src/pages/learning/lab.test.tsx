import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningLabPage from './lab'

vi.mock('@/api/learning', () => ({
  chatWithTutor: vi.fn(),
  getLab: vi.fn(),
  getTutorHistory: vi.fn(),
  listSubmissions: vi.fn(),
  requestHint: vi.fn(),
  resetLab: vi.fn(),
  startLab: vi.fn(),
  submitLab: vi.fn()
}))

function page() {
  return render(
    <MemoryRouter initialEntries={['/learning/labs/l1-sales']}>
      <AntdApp>
        <Routes>
          <Route path="/learning/labs/:code" element={<LearningLabPage />} />
        </Routes>
      </AntdApp>
    </MemoryRouter>
  )
}

describe('learning lab request state', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a retryable error instead of "task not found" when loading fails', async () => {
    vi.mocked(api.getLab).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listSubmissions).mockRejectedValue(new Error('learning service down'))
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('任务加载失败')
    expect(screen.queryByText('任务不存在或尚未发布')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }))
    await waitFor(() => expect(api.getLab).toHaveBeenCalledTimes(2))
  })
})