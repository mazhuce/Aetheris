import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useConversationMessages } from './useConversationMessages'
import type { ChatMessage, ChatResult, ChatConversation } from '@/api/ai'

const api = vi.hoisted(() => ({ createConversation: vi.fn(), listMessages: vi.fn(), sendChat: vi.fn() }))
vi.mock('@/api/ai', () => api)
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
const callbacks = () => ({ created: vi.fn(), sent: vi.fn().mockResolvedValue(undefined) })
beforeEach(() => vi.resetAllMocks())

describe('conversation asynchronous ownership', () => {
  it('an older message load cannot replace the selected conversation', async () => {
    const first = deferred<ChatMessage[]>()
    api.listMessages.mockImplementation((id: number) => id === 1 ? first.promise : Promise.resolve([{ role: 'user', content: 'second' }]))
    const { result } = renderHook(() => useConversationMessages(callbacks()))
    let old!: Promise<void>
    act(() => { old = result.current.open(1) })
    await act(() => result.current.open(2))
    await act(async () => { first.resolve([{ role: 'user', content: 'first' }]); await old })
    expect(result.current.currentId).toBe(2)
    expect(result.current.messages[0].content).toBe('second')
    expect(result.current.loading).toBe(false)
  })

  it('a late reply remains attached to its original conversation', async () => {
    const reply = deferred<ChatResult>()
    api.listMessages.mockResolvedValue([{ role: 'user', content: 'history' }])
    api.sendChat.mockReturnValue(reply.promise)
    const { result } = renderHook(() => useConversationMessages(callbacks()))
    await act(() => result.current.open(1))
    let sending!: Promise<void>
    act(() => { sending = result.current.send('question', false) })
    await act(() => result.current.open(2))
    await act(async () => { reply.resolve({ role: 'assistant', content: 'reply for first' }); await sending })
    expect(api.sendChat.mock.calls[0][0]).toBe(1)
    expect(result.current.currentId).toBe(2)
    expect(result.current.messages.map((item) => item.content)).toEqual(['history'])
    expect(result.current.sending).toBe(false)
  })

  it('creating an implicit conversation cannot steal a newer selection', async () => {
    const created = deferred<ChatConversation>()
    api.createConversation.mockReturnValue(created.promise)
    api.listMessages.mockResolvedValue([{ role: 'user', content: 'second' }])
    api.sendChat.mockResolvedValue({ role: 'assistant', content: 'first reply' })
    const { result } = renderHook(() => useConversationMessages(callbacks()))
    let sending!: Promise<void>
    act(() => { sending = result.current.send('question', false) })
    await act(() => result.current.open(2))
    await act(async () => { created.resolve({ id: 3, title: 'created' }); await sending })
    expect(api.sendChat.mock.calls[0][0]).toBe(3)
    expect(result.current.currentId).toBe(2)
    expect(result.current.messages[0].content).toBe('second')
  })

  it('deleting an earlier conversation cannot clear a later selection', async () => {
    api.listMessages.mockResolvedValue([{ role: 'user', content: 'second' }])
    const { result } = renderHook(() => useConversationMessages(callbacks()))
    await act(() => result.current.open(2))
    act(() => result.current.deleted(1))
    expect(result.current.currentId).toBe(2)
    expect(result.current.messages[0].content).toBe('second')
  })
})
