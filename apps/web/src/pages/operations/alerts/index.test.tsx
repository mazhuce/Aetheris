import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as system from '@/api/system'
import AlertCenterPage from './index'

vi.mock('@/api/system')
const alert: system.PlatformNotification = { id: 17, recipient: 'operations', source: 'workflow', level: 'critical', title: '执行结果待核对', isRead: false }
function page() { return render(<MemoryRouter><AntdApp><AlertCenterPage /></AntdApp></MemoryRouter>) }

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(system.listAlerts).mockResolvedValue([{ ...alert }])
  vi.mocked(system.getAlertStats).mockResolvedValue({ critical: 1, warning: 0, info: 0, total: 1 })
  vi.mocked(system.acknowledgeAlert).mockResolvedValue({ acknowledged: 1, hasMore: false })
  vi.mocked(system.acknowledgeAlertsBatch).mockResolvedValue({ acknowledged: 500, hasMore: true })
})

describe('shared operations acknowledgement', () => {
  it('uses the dedicated acknowledgement API and explains that confirmation does not repair execution', async () => {
    page()
    await screen.findByText(alert.title)
    expect(screen.getByText(/不代表故障已修复或执行已恢复/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '共享确认' }))
    await waitFor(() => expect(system.acknowledgeAlert).toHaveBeenCalledWith(17))
    expect(await screen.findByText('已共享确认，其他管理员也会看到此状态')).toBeInTheDocument()
    expect(system.markNotificationRead).not.toHaveBeenCalled()
    expect(system.markAllNotificationsRead).not.toHaveBeenCalled()
  })

  it('reports a partial batch and waits for another explicit action instead of silently confirming the rest', async () => {
    page()
    await screen.findByText(alert.title)
    fireEvent.click(screen.getByRole('button', { name: /批量共享确认/ }))
    expect(await screen.findByText(/本次共享确认 500 条，仍有待确认告警/)).toBeInTheDocument()
    expect(system.acknowledgeAlertsBatch).toHaveBeenCalledTimes(1)
    expect(system.markAllNotificationsRead).not.toHaveBeenCalled()
  })

  it('does not present a failed acknowledgement as success or invoke a legacy marking endpoint', async () => {
    vi.mocked(system.acknowledgeAlertsBatch).mockRejectedValue(new Error('unavailable'))
    page()
    await screen.findByText(alert.title)
    fireEvent.click(screen.getByRole('button', { name: /批量共享确认/ }))
    expect(await screen.findByText('批量标记失败')).toBeInTheDocument()
    expect(screen.queryByText(/本次共享确认/)).not.toBeInTheDocument()
    expect(system.markAllNotificationsRead).not.toHaveBeenCalled()
  })

  it('clears stale rows when a later permission or dependency check fails', async () => {
    page()
    await screen.findByText(alert.title)
    vi.mocked(system.listAlerts).mockRejectedValue(new Error('permission denied'))
    fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
    await screen.findByText('告警数据加载失败')
    await waitFor(() => expect(screen.queryByText(alert.title)).not.toBeInTheDocument())
  })
})
