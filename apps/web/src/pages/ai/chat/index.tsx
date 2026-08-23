import { useCallback, useEffect, useRef, useState } from 'react'
import { Avatar, Button, Card, Empty, Input, List, Popconfirm, Space } from 'antd'
import { DeleteOutlined, PlusOutlined, RobotOutlined, SendOutlined, UserOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import PageHeader from '@/components/PageHeader'
import {
  createConversation,
  deleteConversation,
  listConversations,
  listMessages,
  sendChat,
  type ChatConversation,
  type ChatMessage
} from '@/api/ai'
import { formatDateTime } from '@/utils/format'

export default function ChatPage() {
  const [conversations, setConversations] = useState<ChatConversation[]>([])
  const [currentId, setCurrentId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  const refreshConversations = useCallback(async () => {
    try {
      setConversations(await listConversations())
    } catch {
      /* ignore */
    }
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const list = await listConversations()
        setConversations(list)
        if (list.length > 0) {
          setCurrentId(list[0].id)
          setMessages(await listMessages(list[0].id))
        }
      } catch {
        /* ignore */
      }
    })()
  }, [])

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, sending])

  const openConversation = async (id: number) => {
    setCurrentId(id)
    setLoading(true)
    try {
      setMessages(await listMessages(id))
    } catch {
      setMessages([])
    } finally {
      setLoading(false)
    }
  }

  const handleNew = async () => {
    try {
      const conversation = await createConversation()
      setConversations((prev) => [conversation, ...prev])
      setCurrentId(conversation.id)
      setMessages([])
    } catch {
      /* ignore */
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteConversation(id)
      setConversations((prev) => prev.filter((item) => item.id !== id))
      if (currentId === id) {
        setCurrentId(null)
        setMessages([])
      }
    } catch {
      /* ignore */
    }
  }

  const handleSend = async () => {
    const text = input.trim()
    if (!text || sending) return
    const next: ChatMessage[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setInput('')
    setSending(true)
    try {
      let conversationId = currentId
      if (conversationId == null) {
        const conversation = await createConversation()
        conversationId = conversation.id
        setCurrentId(conversationId)
      }
      const result = await sendChat(conversationId, next)
      setMessages([...next, { role: 'assistant', content: result.content }])
      await refreshConversations()
    } catch {
      setMessages([...next, { role: 'assistant', content: '抱歉，请求失败，请稍后重试。' }])
    } finally {
      setSending(false)
    }
  }

  return (
    <>
      <PageHeader
        title="智能助手"
        description="基于 DeepSeek 的数据平台智能助手，对话记录自动保存，支持 Markdown 回复。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void handleNew()}>
            新建对话
          </Button>
        }
      />

      <Card size="small">
        <div style={{ display: 'flex', gap: 16 }}>
          <div style={{ width: 240, flexShrink: 0, borderRight: '1px solid #f0f0f0', paddingRight: 12 }}>
            <List
              size="small"
              dataSource={conversations}
              locale={{ emptyText: <Empty description="暂无对话" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
              renderItem={(conversation) => (
                <List.Item
                  onClick={() => void openConversation(conversation.id)}
                  style={{
                    cursor: 'pointer',
                    padding: '8px 10px',
                    borderRadius: 6,
                    background: currentId === conversation.id ? '#e6f4ff' : 'transparent'
                  }}
                  actions={[
                    <Popconfirm
                      key="delete"
                      title="删除该对话？"
                      onConfirm={() => void handleDelete(conversation.id)}
                    >
                      <Button
                        type="text"
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={(event) => event.stopPropagation()}
                      />
                    </Popconfirm>
                  ]}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {conversation.title}
                    </div>
                    <div style={{ fontSize: 12, color: '#8a9099' }}>{formatDateTime(conversation.updatedAt)}</div>
                  </div>
                </List.Item>
              )}
            />
          </div>

          <div style={{ flex: 1, minWidth: 0 }}>
            <div ref={scrollRef} className="chat-scroll" style={{ height: 480 }}>
              {messages.length === 0 && !loading && (
                <div className="chat-msg">
                  <Avatar size={32} icon={<RobotOutlined />} style={{ background: '#10b981', flexShrink: 0 }} />
                  <div style={{ maxWidth: '80%' }}>
                    <div className="chat-meta">DeepSeek 助手</div>
                    <div className="chat-bubble">
                      你好，我是 Aetheris 的智能助手，基于 DeepSeek。可以咨询数据接入、元数据、数据质量、数据治理等相关问题。
                    </div>
                  </div>
                </div>
              )}

              {messages.map((message, index) => {
                const isUser = message.role === 'user'
                return (
                  <div key={index} className={`chat-msg ${isUser ? 'user' : ''}`}>
                    <Avatar
                      size={32}
                      icon={isUser ? <UserOutlined /> : <RobotOutlined />}
                      style={{ background: isUser ? '#1677ff' : '#10b981', flexShrink: 0 }}
                    />
                    <div style={{ maxWidth: '80%' }}>
                      <div className="chat-meta" style={{ textAlign: isUser ? 'right' : 'left' }}>
                        {isUser ? '我' : 'DeepSeek 助手'}
                      </div>
                      <div className="chat-bubble">
                        {isUser ? (
                          message.content
                        ) : (
                          <div className="md-body">
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>

            <Space.Compact style={{ width: '100%', marginTop: 12 }}>
              <Input.TextArea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
                autoSize={{ minRows: 2, maxRows: 6 }}
                onPressEnter={(event) => {
                  if (!event.shiftKey) {
                    event.preventDefault()
                    void handleSend()
                  }
                }}
              />
              <Button
                type="primary"
                icon={<SendOutlined />}
                loading={sending}
                onClick={() => void handleSend()}
                style={{ height: 'auto' }}
              >
                发送
              </Button>
            </Space.Compact>
          </div>
        </div>
      </Card>
    </>
  )
}
