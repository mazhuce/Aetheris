import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import http from '@/api/http'
import { logoutIntent, getAuthSnapshot, resetAuthState } from '@/auth/authState'
import { markLoggedOut, bootstrapSession } from '@/auth/session'
import LoginPage from './index'

const original = http.defaults.adapter
afterEach(() => { http.defaults.adapter = original })

function mount() { return render(<App><MemoryRouter initialEntries={['/login']}><LoginPage /></MemoryRouter></App>) }
function response(config: InternalAxiosRequestConfig, status: number): AxiosResponse {
  return { config, status, statusText: 'fixture', data: null, headers: {} }
}

it('shows unconfirmed logout after reload and retains an explicit retry until server confirms', async () => {
  markLoggedOut('unconfirmed')
  resetAuthState()
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    throw Object.assign(new Error('fixture unavailable'), { config, response: response(config, 503) })
  })
  http.defaults.adapter = adapter
  await expect(bootstrapSession()).resolves.toBe(false)
  mount()
  expect(screen.getByText('本地已退出，服务端会话撤销尚未确认')).toBeInTheDocument()
  expect(adapter).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '重试退出' }))
  await waitFor(() => expect(logoutIntent()?.revocation).toBe('unconfirmed'))
  expect(adapter).toHaveBeenCalledTimes(1)
  expect(screen.getByText('本地已退出，服务端会话撤销尚未确认')).toBeInTheDocument()
  http.defaults.adapter = async (config) => response(config, 204)
  fireEvent.click(screen.getByRole('button', { name: '重试退出' }))
  await waitFor(() => expect(screen.queryByText('本地已退出，服务端会话撤销尚未确认')).not.toBeInTheDocument())
  expect(logoutIntent()?.revocation).toBe('confirmed')
  expect(getAuthSnapshot().status).toBe('anonymous')
})

it('does not silently trim a submitted username into another account', async () => {
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    throw Object.assign(new Error('fixture rejected'), { config, response: response(config, 401) })
  })
  http.defaults.adapter = adapter
  mount()
  fireEvent.change(screen.getByLabelText('用户名'), { target: { value: ' alice ' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'fixture-password' } })
  await act(async () => { fireEvent.submit(screen.getByLabelText('用户名').closest('form')!) })
  await waitFor(() => expect(adapter).toHaveBeenCalledTimes(1))
  expect(JSON.parse(adapter.mock.calls[0][0].data as string).username).toBe(' alice ')
})
