import { useState } from 'react'
import { Avatar, Button, Card, Input, Space } from 'antd'
import { ClearOutlined, RobotOutlined, SendOutlined, UserOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import { sendChat, type ChatMessage } from '@/api/ai'

const welcome: ChatMessage = {
  role: 'assistant',
  content: '你好，我是 Aetheris 的智能助手，基于 DeepSeek。你可以向我咨询数据接入、元数据、数据质量、数据治理等相关问题。'
}

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([welcome])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)

  const handleSend = async () => {
    const text = input.trim()
    if (!text || sending) return
    const next: ChatMessage[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setInput('')
    setSending(true)
    try {
      const result = await sendChat(next)
      setMessages([...next, { role: 'assistant', content: result.content }])
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
        description="基于 DeepSeek 的数据平台智能助手，支持数据领域问题问答。"
        extra={
          <Button icon={<ClearOutlined />} onClick={() => setMessages([welcome])}>
            清空对话
          </Button>
        }
      />

      <Card size="small">
        <div className="chat-scroll">
          {messages.map((message, index) => {
            const isUser = message.role === 'user'
            return (
              <div key={index} className={`chat-msg ${isUser ? 'user' : ''}`}>
                <Avatar size={32} icon={isUser ? <UserOutlined /> : <RobotOutlined />} style={{ background: isUser ? '#1677ff' : '#10b981', flexShrink: 0 }} />
                <div style={{ maxWidth: '80%' }}>
                  <div className="chat-meta" style={{ textAlign: isUser ? 'right' : 'left' }}>
                    {isUser ? '我' : 'DeepSeek 助手'}
                  </div>
                  <div className="chat-bubble">{message.content}</div>
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
          <Button type="primary" icon={<SendOutlined />} loading={sending} onClick={() => void handleSend()} style={{ height: 'auto' }}>
            发送
          </Button>
        </Space.Compact>
      </Card>
    </>
  )
}
