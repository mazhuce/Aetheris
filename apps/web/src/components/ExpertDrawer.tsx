import { useRef, useState } from 'react'
import { Alert, Button, Drawer, Input, Space, Switch, Typography } from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { createConversation, sendChat, type ChatMessage } from '@/api/ai'

interface ExpertDrawerProps {
  open: boolean
  onClose: () => void
}

const EXAMPLES = [
  '这条 SQL 为什么执行很慢？',
  'ODS、DWD、DWS 应该怎么选？',
  '我的任务判分失败，下一步该查什么？'
]

function contextOf(pathname: string): string {
  const lab = pathname.match(/^\/learning\/labs\/([^/]+)/)?.[1]
  if (lab) return `当前页面：学习任务 ${lab}（${pathname}）`
  const project = pathname.match(/^\/learning\/projects\/([^/]+)/)?.[1]
  if (project) return `当前页面：学习项目 ${project}（${pathname}）`
  if (pathname.startsWith('/learning')) return '当前页面：学习中心'
  return `当前页面：${pathname}`
}

export default function ExpertDrawer({ open, onClose }: ExpertDrawerProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const messagesRef = useRef<ChatMessage[]>([])
  const conversationId = useRef<number | null>(null)
  const sendingRef = useRef(false)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [dataMode, setDataMode] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const show = (next: ChatMessage[]) => {
    messagesRef.current = next
    setMessages(next)
  }

  const dispatch = async (next: ChatMessage[]) => {
    if (sendingRef.current) return
    sendingRef.current = true
    setSending(true)
    setFailure(null)
    try {
      let id = conversationId.current
      if (id === null) {
        const conversation = await createConversation()
        id = conversation.id
        conversationId.current = id
      }
      const reply = await sendChat(id, next, dataMode, {
        expert: true,
        context: contextOf(location.pathname)
      })
      show([...next, { role: 'assistant', content: reply.content }])
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      setFailure(
        status === 429
          ? '请求过于频繁，请稍后再试。'
          : status === 503
            ? '模型服务未配置密钥或暂不可用。'
            : '专家服务暂时不可用，请稍后重试。'
      )
    } finally {
      sendingRef.current = false
      setSending(false)
    }
  }

  const ask = (text?: string) => {
    const question = (text ?? input).trim()
    if (!question || sendingRef.current) return
    const next: ChatMessage[] = [...messagesRef.current, { role: 'user', content: question }]
    show(next)
    setInput('')
    void dispatch(next)
  }

  const leaveTo = (path: string) => {
    onClose()
    navigate(path)
  }

  return (
    <Drawer
      title="数据开发专家"
      placement="right"
      width={440}
      open={open}
      onClose={onClose}
      className="expert-drawer"
    >
      <div className="expert-drawer-body">
        <p className="expert-drawer-lead">
          面向学习者的数据开发顾问：可以问 SQL、数仓分层、建模、质量与报错原因。回答不替代平台判分和执行结果。
        </p>

        {messages.length === 0 && (
          <div className="expert-drawer-intro">
            <Typography.Text type="secondary">可以这样问：</Typography.Text>
            <Space wrap size={8}>
              {EXAMPLES.map((example) => (
                <Button key={example} size="small" onClick={() => ask(example)}>{example}</Button>
              ))}
            </Space>
          </div>
        )}

        <div className="expert-drawer-messages" aria-live="polite">
          {messages.map((message, index) => (
            <div key={index} className={`expert-message expert-message-${message.role}`}>
              <div className="expert-message-bubble">
                {message.role === 'assistant'
                  ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
                  : message.content}
              </div>
            </div>
          ))}
          {sending && (
            <div className="expert-message expert-message-assistant">
              <div className="expert-message-bubble">
                <Typography.Text type="secondary">正在结合学习上下文思考…</Typography.Text>
              </div>
            </div>
          )}
        </div>

        {failure && (
          <Alert
            className="expert-offline"
            type="warning"
            showIcon
            message="离线提示（非模型回答）"
            description={(
              <Space direction="vertical" size={6}>
                <span>{failure}</span>
                <span>可以先自查：SQL 是否为只读并带分区条件；输出列与口径是否符合任务验收标准；失败类型对应的知识点是否已完成。</span>
                <Space wrap size={8}>
                  <Button size="small" onClick={() => leaveTo('/learning')}>去学习路径</Button>
                  <Button size="small" onClick={() => leaveTo('/development/sql')}>SQL 实训</Button>
                  <Button size="small" onClick={() => leaveTo('/assets/tables')}>查看数据表</Button>
                  <Button size="small" disabled={sending} onClick={() => void dispatch(messagesRef.current)}>重试</Button>
                </Space>
              </Space>
            )}
          />
        )}

        <div className="expert-drawer-composer">
          <div className="expert-drawer-toolbar">
            <Space size={6}>
              <Switch size="small" checked={dataMode} onChange={setDataMode} />
              <Typography.Text type="secondary">允许检索元数据（更慢）</Typography.Text>
            </Space>
          </div>
          <Input.TextArea
            value={input}
            onChange={(event) => setInput(event.target.value)}
            autoSize={{ minRows: 2, maxRows: 5 }}
            placeholder="问 SQL、数仓分层、报错或学习路径…"
            onPressEnter={(event) => {
              if (event.shiftKey) return
              event.preventDefault()
              ask()
            }}
          />
          <Space size={8} align="center">
            <Button type="primary" icon={<SendOutlined />} loading={sending} disabled={!input.trim()} onClick={() => ask()}>
              发送
            </Button>
            <Typography.Text type="secondary" className="expert-drawer-footnote">
              回答可能不准确；判分与执行结果以平台服务器为准。
            </Typography.Text>
          </Space>
        </div>
      </div>
    </Drawer>
  )
}