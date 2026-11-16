import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningCenterPage from './index'

vi.mock('@/api/learning', () => ({
  listLearningPaths: vi.fn(),
  getProgress: vi.fn(),
  listProjects: vi.fn(),
  getLearningPath: vi.fn()
}))

function page() {
  return render(
    <MemoryRouter>
      <AntdApp><LearningCenterPage /></AntdApp>
    </MemoryRouter>
  )
}

describe('learning center request state', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a retryable error instead of an empty path list when loading fails', async () => {
    vi.mocked(api.listLearningPaths).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.getProgress).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listProjects).mockRejectedValue(new Error('learning service down'))
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('学习数据加载失败')
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }))
    await waitFor(() => expect(api.listLearningPaths).toHaveBeenCalledTimes(2))
  })
})