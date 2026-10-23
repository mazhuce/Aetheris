import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { describe, expect, it, vi } from 'vitest'
import DataSourceForm from './DataSourceForm'

describe('business credential form', () => {
  it('keeps hidden historical options unless the user explicitly clears them', async () => {
    const submit = vi.fn()
    render(<AntdApp><DataSourceForm open submitting={false} onCancel={() => {}} onSubmit={submit}
      editing={{ id: 7, name: 'fixture', type: 'postgresql', host: '127.0.0.1', port: 5432,
        databaseName: 'fixture', username: 'reader', status: 'inactive', jdbcUrl: null,
        connectionParams: null, connectionConfigurationRedacted: true }} /></AntdApp>)
    await screen.findByText('部分历史连接配置已隐藏')
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(submit).toHaveBeenCalledOnce())
    expect(submit.mock.calls[0][0].jdbcUrl).toBeUndefined()
    expect(submit.mock.calls[0][0].connectionParams).toBeUndefined()
    fireEvent.click(screen.getByRole('button', { name: '清除旧 URL 和连接参数' }))
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1][0]).toMatchObject({ jdbcUrl: '', connectionParams: '', status: 'inactive' })
  })

  it('requires replacement of a hidden host before submitting a repair', async () => {
    const submit = vi.fn()
    render(<AntdApp><DataSourceForm open submitting={false} onCancel={() => {}} onSubmit={submit}
      editing={{ id: 8, name: 'redacted', type: 'postgresql', host: null, port: 5432,
        databaseName: 'fixture', status: 'inactive', connectionConfigurationRedacted: true }} /></AntdApp>)
    fireEvent.click(await screen.findByRole('button', { name: '清除旧 URL 和连接参数' }))
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('请输入主机地址')
    expect(submit).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('主机地址'), { target: { value: '127.0.0.1' } })
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(submit).toHaveBeenCalledOnce())
    expect(submit.mock.calls[0][0]).toMatchObject({ host: '127.0.0.1', jdbcUrl: '', connectionParams: '' })
  })

  it('explicitly disables an existing writer without resending hidden account or secrets', async () => {
    const submit = vi.fn()
    render(<AntdApp><DataSourceForm open submitting={false} onCancel={() => {}} onSubmit={submit} editing={{ id: 7, name: 'fixture', type: 'postgresql', host: '127.0.0.1', port: 5432, databaseName: 'fixture', username: 'reader', status: 'active', writeConfigured: true }} /></AntdApp>)
    await screen.findByLabelText('写入账户')
    expect(screen.getByLabelText('写入账户')).toHaveValue('')
    fireEvent.click(screen.getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(submit).toHaveBeenCalledOnce())
    const payload = submit.mock.calls[0][0]
    expect(payload.disableWriteCredentials).toBe(true)
    expect(payload).not.toHaveProperty('writeUsername')
    expect(payload).not.toHaveProperty('writePassword')
    expect(payload).not.toHaveProperty('writeEnabled')
  })
  it('rejects a same-account writer and submits a separate writer only after correction', async () => {
    const submit = vi.fn()
    render(<AntdApp><DataSourceForm open submitting={false} onCancel={() => {}} onSubmit={submit} /></AntdApp>)
    fireEvent.change(await screen.findByLabelText('数据源名称'), { target: { value: 'fixture' } })
    fireEvent.change(screen.getByLabelText('主机地址'), { target: { value: '127.0.0.1' } })
    fireEvent.change(screen.getByLabelText('查询 / 采集读取账户'), { target: { value: 'reader' } })
    fireEvent.click(screen.getByRole('switch'))
    fireEvent.change(await screen.findByLabelText('写入账户'), { target: { value: 'READER' } })
    fireEvent.change(screen.getByLabelText('写入密码'), { target: { value: 'write-fixture' } })
    fireEvent.click(screen.getByRole('button', { name: '确认接入' }))
    await screen.findByText('写入账户必须与读取账户不同')
    expect(submit).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('写入账户'), { target: { value: 'writer' } })
    fireEvent.click(screen.getByRole('button', { name: '确认接入' }))
    await waitFor(() => expect(submit).toHaveBeenCalledOnce())
    expect(submit.mock.calls[0][0]).toMatchObject({ username: 'reader', writeUsername: 'writer', writePassword: 'write-fixture' })
  })
})
