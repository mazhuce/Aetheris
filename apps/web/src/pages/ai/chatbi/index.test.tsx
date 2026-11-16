import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/ai'
import ChatBiPage from './index'

vi.mock('@/api/ai', () => ({ askChatBi: vi.fn() }))

function page() {
  return render(<MemoryRouter><AntdApp><ChatBiPage /></AntdApp></MemoryRouter>)
}

describe('ChatBI request ownership and cancellation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('locks duplicate submissions and aborts the active request', async () => {
    let resolve!: (value: api.ChatResult) => void
    vi.mocked(api.askChatBi).mockReturnValue(new Promise<api.ChatResult>((done) => { resolve = done }))
    page()

    const input = screen.getByPlaceholderText('例如：2026年8月销售额最高的品类是哪个？')
    fireEvent.change(input, { target: { value: '当前问题' } })
    fireEvent.click(screen.getByRole('button', { name: /提问$/ }))
    await waitFor(() => expect(api.askChatBi).toHaveBeenCalledTimes(1))
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', shiftKey: false })
    expect(api.askChatBi).toHaveBeenCalledTimes(1)

    const signal = vi.mocked(api.askChatBi).mock.calls[0][1]
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(signal?.aborted).toBe(true)
    await act(async () => resolve({ role: 'assistant', content: '旧回答' }))
    expect(screen.queryByText('旧回答')).not.toBeInTheDocument()
  })

  it('does not let a cancelled question overwrite the next question', async () => {
    let resolveOld!: (value: api.ChatResult) => void
    let resolveCurrent!: (value: api.ChatResult) => void
    vi.mocked(api.askChatBi)
      .mockReturnValueOnce(new Promise<api.ChatResult>((done) => { resolveOld = done }))
      .mockReturnValueOnce(new Promise<api.ChatResult>((done) => { resolveCurrent = done }))
    page()

    const input = screen.getByPlaceholderText('例如：2026年8月销售额最高的品类是哪个？')
    fireEvent.change(input, { target: { value: '旧问题' } })
    fireEvent.click(screen.getByRole('button', { name: /提问$/ }))
    await waitFor(() => expect(api.askChatBi).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    fireEvent.change(input, { target: { value: '当前问题' } })
    fireEvent.click(screen.getByRole('button', { name: /提问$/ }))
    await waitFor(() => expect(api.askChatBi).toHaveBeenCalledTimes(2))

    await act(async () => resolveOld({ role: 'assistant', content: '旧回答' }))
    await act(async () => resolveCurrent({ role: 'assistant', content: '当前回答' }))
    expect(await screen.findByText('当前回答')).toBeInTheDocument()
    expect(screen.queryByText('旧回答')).not.toBeInTheDocument()
  })

  it('aborts the in-flight question when the page unmounts', async () => {
    vi.mocked(api.askChatBi).mockReturnValue(new Promise<api.ChatResult>(() => {}))
    const view = page()

    const input = screen.getByPlaceholderText('例如：2026年8月销售额最高的品类是哪个？')
    fireEvent.change(input, { target: { value: '卸载前问题' } })
    fireEvent.click(screen.getByRole('button', { name: /提问$/ }))
    await waitFor(() => expect(api.askChatBi).toHaveBeenCalledTimes(1))

    const signal = vi.mocked(api.askChatBi).mock.calls[0][1]
    view.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
