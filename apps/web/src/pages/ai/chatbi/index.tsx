import { useState } from 'react'
import { App, Button, Input, Space, Spin, Tag, Tooltip, Typography } from 'antd'
import { CopyOutlined, DatabaseOutlined, QuestionCircleOutlined, SendOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { askChatBi, type ChatResult } from '@/api/ai'

const EXAMPLES = [
  '昨天销售额最高的品类是哪个？',
  '过去 7 天每天的订单量是多少？',
  '无效订单占总订单的比例？'
]

/** 从回答中提取 ```sql 代码块，供复制与跳转到即席查询。 */
function extractSql(content: string): string | undefined {
  const match = content.match(/```sql\s*\n([\s\S]*?)```/i)
  return match?.[1]?.trim()
}

export default function ChatBiPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [question, setQuestion] = useState('')
  const [result, setResult] = useState<ChatResult>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)

  const ask = async (text?: string) => {
    const q = (text ?? question).trim()
    if (!q) {
      message.warning('请输入问题')
      return
    }
    setLoading(true)
    setError(undefined)
    setResult(undefined)
    try {
      setResult(await askChatBi(q))
    } catch (err) {
      const detail = (err as { response?: { data?: { content?: string } } })?.response?.data?.content
      setError(detail || '智能问数暂时不可用，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  const sql = result ? extractSql(result.content) : undefined

  const copySql = async () => {
    if (!sql) return
    try {
      await navigator.clipboard.writeText(sql)
      message.success('SQL 已复制')
    } catch {
      message.error('复制失败，请手动选择复制')
    }
  }

  return (
    <div className="chatbi-page">
      <PageHeader
        description="用自然语言直接问数：AI 自动检索表结构、执行只读查询并给出结论、口径与 SQL。无需了解数仓细节。"
        extra={
          <Tooltip title="AI 生成的 SQL 受平台只读守卫约束，仅能执行 SELECT 类语句">
            <Tag color="green"><DatabaseOutlined /> 只读查询</Tag>
          </Tooltip>
        }
      />

      <DataSurface title="提问" label="智能问数">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input.TextArea
            rows={2}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="例如：2026年8月销售额最高的品类是哪个？"
            maxLength={500}
            showCount
            onPressEnter={(event) => {
              if (!event.shiftKey) {
                event.preventDefault()
                void ask()
              }
            }}
          />
          <Space wrap>
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={loading}
              disabled={!question.trim()}
              onClick={() => void ask()}
            >
              提问
            </Button>
            {EXAMPLES.map((example) => (
              <Button
                key={example}
                type="text"
                size="small"
                onClick={() => {
                  setQuestion(example)
                  void ask(example)
                }}
              >
                {example}
              </Button>
            ))}
          </Space>
        </Space>
      </DataSurface>

      {loading && (
        <DataSurface title="思考中" label="AI 分析">
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Spin />
            <Typography.Text type="secondary">
              AI 正在检索表结构并执行查询，通常需要 10-60 秒，请勿重复提交。
            </Typography.Text>
          </Space>
        </DataSurface>
      )}

      {error && (
        <DataSurface title="出错了" label="错误信息">
          <Typography.Text type="danger">{error}</Typography.Text>
        </DataSurface>
      )}

      {result && (
        <DataSurface
          title="回答"
          label="问数结果"
          extra={sql ? (
            <Space>
              <Button size="small" icon={<CopyOutlined />} onClick={() => void copySql()}>复制 SQL</Button>
              <Tooltip title="把这段 SQL 粘贴到即席查询，自行调整日期或条件后执行">
                <Button size="small" onClick={() => navigate('/analytics/query')}>去即席查询验证</Button>
              </Tooltip>
            </Space>
          ) : undefined}
        >
          <div className="md-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{result.content}</ReactMarkdown>
          </div>
          {result.model && (
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
              <QuestionCircleOutlined /> 模型：{result.model}
            </Typography.Text>
          )}
        </DataSurface>
      )}
    </div>
  )
}
