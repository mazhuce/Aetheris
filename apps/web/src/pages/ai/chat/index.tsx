import { useCallback, useEffect, useRef, useState } from 'react'
import { Avatar, Button, Drawer, Empty, Input, List, Popconfirm, Switch, Tooltip } from 'antd'
import { DeleteOutlined, HistoryOutlined, PlusOutlined, RobotOutlined, SendOutlined, UserOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import PageHeader from '@/components/PageHeader'
import { ErrorState, LoadingState } from '@/components/FeedbackState'
import {
  deleteConversation,
  listConversations,
  type ChatConversation
} from '@/api/ai'
import { formatDateTime } from '@/utils/format'
import { useConversationMessages } from './useConversationMessages'

export default function ChatPage() {
  const [conversations, setConversations] = useState<ChatConversation[]>([])
  const [input, setInput] = useState('')
  // 数据模式：启用后 AI 可检索元数据并执行只读查询。默认关闭，
  // 因为工具调用会多花几轮模型请求，普通咨询不需要付这个代价。
  const [dataMode, setDataMode] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  const refreshConversations = useCallback(async () => {
    try {
      setConversations(await listConversations())
    } catch {
      /* ignore */
    }
  }, [])

  const conversation = useConversationMessages({
    created: (item) => setConversations((previous) => [item, ...previous]),
    sent: refreshConversations
  })
  const { currentId, messages, sending, loading, loadError, setLoadError } = conversation

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const list = await listConversations()
        if (cancelled) return
        setConversations(list)
        if (list.length > 0) {
          await conversation.openInitial(list[0].id)
        }
      } catch {
        if (!cancelled) setLoadError('对话列表暂时无法加载，请稍后重试。')
      }
    })()
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, sending])

  const openConversation = async (id: number) => {
    setHistoryOpen(false)
    await conversation.open(id)
  }

  const handleNew = async () => {
    await conversation.create()
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteConversation(id)
      setConversations((prev) => prev.filter((item) => item.id !== id))
      conversation.deleted(id)
    } catch {
      /* ignore */
    }
  }

  const handleSend = async () => {
    const text = input.trim()
    if (!text || sending) return
    setInput('')
    await conversation.send(text, dataMode)
  }

  const conversationList = (
    <>
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
                title={`删除对话“${conversation.title}”？`}
                description="删除后将无法恢复该对话记录。"
                onConfirm={() => void handleDelete(conversation.id)}
              >
                <Tooltip title="删除对话">
                  <Button
                    type="text"
                    size="small"
                    icon={<DeleteOutlined />}
                    aria-label={`删除对话 ${conversation.title}`}
                    onClick={(event) => event.stopPropagation()}
                  />
                </Tooltip>
              </Popconfirm>
            ]}
          >
            <div className="conversation-copy">
              <div className="conversation-title">{conversation.title}</div>
              <div className="conversation-time">{formatDateTime(conversation.updatedAt)}</div>
            </div>
          </List.Item>
        )}
      />
    </>
  )

  return (
    <div className="assistant-page">
      <PageHeader
        description="提供基于大语言模型的数据问答能力，对话记录自动保存，回复支持 Markdown 格式。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void handleNew()}>
            新建对话
          </Button>
        }
      />

      <div className="assistant-shell">
        <aside className="conversation-sidebar">
          {conversationList}
        </aside>

        <section className="assistant-main">
            <div className="assistant-toolbar">
              <div className="assistant-identity">
                <Tooltip title="打开对话记录">
                  <Button className="assistant-mobile-history" type="text" size="small" icon={<HistoryOutlined />} aria-label="打开对话记录" onClick={() => setHistoryOpen(true)} />
                </Tooltip>
                <span className="assistant-mark"><RobotOutlined /></span><div><strong>Aetheris AI</strong><small>数据平台助手</small></div>
              </div>
              <label className="assistant-mode">
                <div><strong>{dataMode ? '数据模式' : '对话模式'}</strong><small>{dataMode ? '检索元数据并执行只读查询' : '仅使用模型知识，不查询数据'}</small></div>
                <Switch size="small" checked={dataMode} onChange={setDataMode} />
              </label>
            </div>

            <div ref={scrollRef} className="chat-scroll assistant-messages">
              {loading && <LoadingState rows={4} />}
              {!loading && loadError && (
                <ErrorState description={loadError} onRetry={currentId == null ? undefined : () => void openConversation(currentId)} />
              )}
              {messages.length === 0 && !loading && !loadError && (
                <div className="assistant-empty">
                  <span className="assistant-empty-mark"><RobotOutlined /></span>
                  <h2>有什么可以帮你？</h2>
                  <p>询问数据接入、元数据、数据质量或治理策略。</p>
                  <div className="assistant-suggestions"><button onClick={() => setInput('如何设计一条数据质量规则？')}>设计质量规则</button><button onClick={() => setInput('解释一下当前数仓分层结构')}>理解数仓分层</button><button onClick={() => setInput('如何排查数据接入失败？')}>排查接入问题</button></div>
                </div>
              )}

              {!loading && !loadError && messages.map((message, index) => {
                const isUser = message.role === 'user'
                return (
                  <div key={index} className={`chat-msg ${isUser ? 'user' : ''}`}>
                    <Avatar
                      size={26}
                      icon={isUser ? <UserOutlined /> : <RobotOutlined />}
                      className={`chat-avatar ${isUser ? 'is-user' : 'is-assistant'}`}
                    />
                    <div className="chat-message-content">
                      <div className="chat-meta">
                        {isUser ? '我' : 'Aetheris AI'}
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

      <Drawer
        title="对话记录"
        placement="left"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        width={280}
        className="assistant-history-drawer"
      >
        <div className="conversation-drawer-list">{conversationList}</div>
      </Drawer>
    </div>
  )
}
