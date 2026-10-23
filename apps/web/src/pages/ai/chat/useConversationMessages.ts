import { useCallback, useEffect, useRef, useState } from 'react'
import { createConversation, listMessages, sendChat, type ChatConversation, type ChatMessage } from '@/api/ai'

/** Selection epochs keep delayed loads and replies attached to their original conversation. */
export function useConversationMessages(callbacks: {
  created: (conversation: ChatConversation) => void
  sent: () => Promise<void>
}) {
  const [currentId, setCurrentId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const active = useRef<number | null>(null)
  const epoch = useRef(0)
  const history = useRef<ChatMessage[]>([])
  const sendingRef = useRef(false)
  const mounted = useRef(true)
  const callbacksRef = useRef(callbacks)
  callbacksRef.current = callbacks

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; epoch.current += 1 }
  }, [])

  const show = useCallback((next: ChatMessage[]) => {
    history.current = next
    setMessages(next)
  }, [])

  const open = useCallback(async (id: number) => {
    const request = ++epoch.current
    active.current = id
    setCurrentId(id)
    show([])
    setLoading(true)
    setLoadError(null)
    try {
      const result = await listMessages(id)
      if (mounted.current && request === epoch.current) show(result)
    } catch {
      if (mounted.current && request === epoch.current) setLoadError('对话内容暂时无法加载，请稍后重试。')
    } finally {
      if (mounted.current && request === epoch.current) setLoading(false)
    }
  }, [show])

  const create = useCallback(async () => {
    const request = ++epoch.current
    setLoading(true)
    setLoadError(null)
    try {
      const conversation = await createConversation()
      if (!mounted.current) return
      callbacksRef.current.created(conversation)
      if (request !== epoch.current) return
      active.current = conversation.id
      setCurrentId(conversation.id)
      show([])
    } catch {
      if (mounted.current && request === epoch.current) setLoadError('新对话暂时无法创建，请稍后重试。')
    } finally {
      if (mounted.current && request === epoch.current) setLoading(false)
    }
  }, [show])

  const deleted = useCallback((id: number) => {
    if (active.current !== id) return
    epoch.current++
    active.current = null
    setCurrentId(null)
    show([])
    setLoading(false)
    setLoadError(null)
  }, [show])

  const send = useCallback(async (content: string, dataMode: boolean) => {
    if (!content.trim() || sendingRef.current) return
    const request = ++epoch.current
    const next: ChatMessage[] = [...history.current, { role: 'user', content: content.trim() }]
    let target = active.current
    show(next)
    setLoading(false)
    setLoadError(null)
    sendingRef.current = true
    setSending(true)
    try {
      if (target == null) {
        const conversation = await createConversation()
        target = conversation.id
        if (mounted.current) {
          callbacksRef.current.created(conversation)
          if (request === epoch.current) {
            active.current = target
            setCurrentId(target)
          }
        }
      }
      const reply = await sendChat(target, next, dataMode)
      if (!mounted.current) return
      if (request === epoch.current && active.current === target) {
        show([...next, { role: 'assistant', content: reply.content }])
      } else if (active.current === target) {
        // The user revisited this conversation while it was sending. Reload its
        // persisted history instead of replacing it with the old captured array.
        await open(target)
      }
      await callbacksRef.current.sent()
    } catch {
      if (mounted.current && request === epoch.current && active.current === target) {
        show([...next, { role: 'assistant', content: '抱歉，请求失败，请稍后重试。' }])
      }
    } finally {
      sendingRef.current = false
      if (mounted.current) setSending(false)
    }
  }, [open, show])

  const openInitial = useCallback(async (id: number) => {
    if (epoch.current === 0) await open(id)
  }, [open])

  return { currentId, messages, sending, loading, loadError, setLoadError, open, openInitial, create, deleted, send }
}
