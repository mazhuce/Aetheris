import { useCallback, useEffect, useRef, useState } from 'react'
import { Avatar, Button, Empty, Input, List, Popconfirm, Switch } from 'antd'
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
  // 数据模式：启用后 AI 可检索元数据并执行只读查询。默认关闭，
  // 因为工具调用会多花几轮模型请求，普通咨询不需要付这个代价。
  const [dataMode, setDataMode] = useState(false)
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
      const result = await sendChat(conversationId, next, dataMode)
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

      <div className="assistant-shell">
        <aside className="conversation-sidebar">
            <div className="conversation-heading"><span>对话记录</span><small>{conversations.length}</small></div>
            <List
              size="small"
              dataSource={conversations}
              locale={{ emptyText: <Empty description="暂无对话" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
              renderItem={(conversation) => (
                <List.Item
                  onClick={() => void openConversation(conversation.id)}
                  className={`conversation-item${currentId === conversation.id ? ' active' : ''}`}
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
                    <div className="conversation-title">
                      {conversation.title}
                    </div>
                    <div className="conversation-time">{formatDateTime(conversation.updatedAt)}</div>
                  </div>
                </List.Item>
              )}
            />
        </aside>

        <section className="assistant-main">
            <div className="assistant-toolbar">
              <div className="assistant-identity"><span className="assistant-mark"><RobotOutlined /></span><div><strong>Aetheris AI</strong><small>Powered by DeepSeek</small></div></div>
              <label className="assistant-mode">
                <div><strong>{dataMode ? '数据模式' : '对话模式'}</strong><small>{dataMode ? '检索元数据并执行只读查询' : '仅使用模型知识，不查询数据'}</small></div>
                <Switch size="small" checked={dataMode} onChange={setDataMode} />
              </label>
            </div>

            <div ref={scrollRef} className="chat-scroll assistant-messages">
              {messages.length === 0 && !loading && (
                <div className="assistant-empty">
                  <span className="assistant-empty-mark"><RobotOutlined /></span>
                  <h2>有什么可以帮你？</h2>
                  <p>询问数据接入、元数据、数据质量或治理策略。</p>
                  <div className="assistant-suggestions"><button onClick={() => setInput('如何设计一条数据质量规则？')}>设计质量规则</button><button onClick={() => setInput('解释一下当前数仓分层结构')}>理解数仓分层</button><button onClick={() => setInput('如何排查数据接入失败？')}>排查接入问题</button></div>
                </div>
              )}

              {messages.map((message, index) => {
                const isUser = message.role === 'user'
                return (
                  <div key={index} className={`chat-msg ${isUser ? 'user' : ''}`}>
                    <Avatar
                      size={32}
                      icon={isUser ? <UserOutlined /> : <RobotOutlined />}
                      style={{ background: '#071a3d', flexShrink: 0 }}
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

            <div className="assistant-composer">
              <Input.TextArea
                className="assistant-input"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder={dataMode
                  ? '例如：2026年8月销售额最高的品类是哪个？'
                  : '输入你的问题，Enter 发送，Shift+Enter 换行'}
                autoSize={{ minRows: 2, maxRows: 6 }}
                onPressEnter={(event) => {
                  if (!event.shiftKey) {
                    event.preventDefault()
                    void handleSend()
                  }
                }}
              />
              <Button
                className="assistant-send"
                type="primary"
                icon={<SendOutlined />}
                loading={sending}
                onClick={() => void handleSend()}
              >
                <span>发送</span>
              </Button>
              <div className="composer-hint">Enter 发送 · Shift + Enter 换行</div>
            </div>
        </section>
      </div>
    </>
  )
}
