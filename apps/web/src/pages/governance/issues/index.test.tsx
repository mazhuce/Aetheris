import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as system from '@/api/system'
import GovernanceIssuesPage from './index'

vi.mock('@/api/system')
const issue: system.PlatformNotification = { id: 29, recipient: 'operations', source: 'quality', level: 'warning', title: '质量检查需处理', isRead: false }
function page() { return render(<MemoryRouter><AntdApp><GovernanceIssuesPage /></AntdApp></MemoryRouter>) }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(system.listAlerts).mockResolvedValue([{ ...issue }])
  vi.mocked(system.getAlertStats).mockResolvedValue({ critical: 0, warning: 1, info: 0, total: 1 })
  vi.mocked(system.acknowledgeAlert).mockResolvedValue({ acknowledged: 1, hasMore: false })
})

describe('governance shared acknowledgement', () => {
  it('confirms only through the managed operations API without claiming business recovery', async () => {
    page()
    await screen.findByText(issue.title)
    fireEvent.click(screen.getByRole('button', { name: '共享确认' }))
    await screen.findByText('共享确认团队已知悉？此操作不代表故障修复或执行恢复。')
    fireEvent.click(screen.getByRole('button', { name: /^(OK|确定|确 定)$/ }))
    await waitFor(() => expect(system.acknowledgeAlert).toHaveBeenCalledWith(29))
    expect(await screen.findByText('已共享确认；实际故障修复与执行恢复仍需另行核对')).toBeInTheDocument()
    expect(system.markNotificationRead).not.toHaveBeenCalled()
  })

  it('clears stale governance details after a denied or unavailable refresh', async () => {
    page()
    await screen.findByText(issue.title)
    vi.mocked(system.listAlerts).mockRejectedValue(new Error('service unavailable'))
    fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
    await screen.findByText('治理问题加载失败')
    await waitFor(() => expect(screen.queryByText(issue.title)).not.toBeInTheDocument())
  })
})
