import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, App, Button, Col, Descriptions, Empty, Input, List, Row, Select, Space, Tag, Typography } from 'antd'
import { ApiOutlined, CopyOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { callMcp, getMcpInfo, type JsonRpcResponse, type McpInfo, type McpToolInfo } from '@/api/mcp'

function defaultArguments(tool: McpToolInfo | undefined): string {
  if (!tool) return '{}'
  const properties = (tool.inputSchema?.properties ?? {}) as Record<string, Record<string, unknown>>
  const required = (tool.inputSchema?.required ?? []) as string[]
  const payload: Record<string, unknown> = {}
  for (const key of required) {
    const property = properties[key] ?? {}
    if (Array.isArray(property.enum) && property.enum.length > 0) payload[key] = property.enum[0]
    else if (property.type === 'integer' || property.type === 'number') payload[key] = 1
    else if (property.type === 'array') payload[key] = []
    else payload[key] = ''
  }
  return JSON.stringify(payload, null, 2)
}

export default function LearningMcpPage() {
  const { message } = App.useApp()
  const [info, setInfo] = useState<McpInfo | null>(null)
  const [selected, setSelected] = useState<string>('get_learning_path')
  const [args, setArgs] = useState('{}')
  const [response, setResponse] = useState<JsonRpcResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [calling, setCalling] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const next = await getMcpInfo()
      setInfo(next)
      const tool = next.tools.find((item) => item.name === selected) ?? next.tools[0]
      if (tool) {
        setSelected(tool.name)
        setArgs(defaultArguments(tool))
      }
    } catch {
      message.error('MCP 工具目录加载失败，请确认 mcp-server 已启动')
    } finally {
      setLoading(false)
    }
  }, [message, selected])

  useEffect(() => {
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const tool = useMemo(() => info?.tools.find((item) => item.name === selected), [info, selected])

  const handleSelect = (name: string) => {
    setSelected(name)
    const next = info?.tools.find((item) => item.name === name)
    setArgs(defaultArguments(next))
    setResponse(null)
  }

  const handleCall = async () => {
    let parsed: unknown
    try {
      parsed = JSON.parse(args)
    } catch {
      message.warning('参数必须是合法 JSON')
      return
    }
    setCalling(true)
    try {
      const result = await callMcp('tools/call', { name: selected, arguments: parsed })
      setResponse(result)
      if (result.error) message.error(result.error.message)
    } catch {
      message.error('工具调用失败')
    } finally {
      setCalling(false)
    }
  }

  const copyCurl = async () => {
    const snippet = `curl -X POST http://localhost:8080/api/mcp \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer <Aetheris access token>' \\
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`
    try {
      await navigator.clipboard.writeText(snippet)
      message.success('已复制调用示例')
    } catch {
      message.warning('浏览器未授权剪贴板，请手动复制')
    }
  }

  return (
    <>
      <PageHeader
        description="M2 只开放只读工具：平台元数据、指标、只读 SQL 与学习进度。所有调用继承当前用户权限并写入审计。"
        extra={
          <Space>
            <Button icon={<CopyOutlined />} onClick={() => void copyCurl()}>复制调用示例</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>
          </Space>
        }
      />

      <Row gutter={16}>
        <Col span={8}>
          <DataSurface title="连接信息" label="连接信息">
            <Descriptions column={1} size="small">
              <Descriptions.Item label="协议版本">{info?.protocolVersion ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="端点"><code>/api/mcp</code></Descriptions.Item>
              <Descriptions.Item label="当前身份">{info?.learner ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="限流">{info?.rateLimitPerMinute ?? '—'} 次/分钟</Descriptions.Item>
              <Descriptions.Item label="工具数">{info?.tools.length ?? 0}</Descriptions.Item>
            </Descriptions>
          </DataSurface>

          <DataSurface title="工具" label="工具" style={{ marginTop: 16 }}>
            {info?.tools.length ? (
              <List
                size="small"
                dataSource={info.tools}
                renderItem={(item) => (
                  <List.Item
                    style={{ cursor: 'pointer', background: item.name === selected ? 'var(--bg-hover)' : undefined }}
                    onClick={() => handleSelect(item.name)}
                    actions={[<Tag key="risk" color={item.riskLevel === 0 ? 'success' : 'warning'}>L{item.riskLevel}</Tag>]}
                  >
                    <List.Item.Meta
                      title={item.name}
                      description={item.title}
                    />
                  </List.Item>
                )}
              />
            ) : (
              <Empty description="暂无工具" />
            )}
          </DataSurface>

          <DataSurface title="Prompts" label="Prompts" style={{ marginTop: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              {(info?.prompts ?? []).map((prompt) => (
                <div key={prompt.name}>
                  <Typography.Text strong>{prompt.name}</Typography.Text>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 4 }}>
                    {prompt.description}
                  </Typography.Paragraph>
                </div>
              ))}
            </Space>
          </DataSurface>
        </Col>

        <Col span={16}>
          <DataSurface
            title={<Space><ApiOutlined />调用工具</Space>}
            extra={<Select style={{ width: 260 }} value={selected} onChange={handleSelect}
              options={(info?.tools ?? []).map((item) => ({ value: item.name, label: `${item.name} · ${item.title}` }))} />}
          >
            {tool ? (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                <Typography.Text type="secondary">{tool.description}</Typography.Text>
                <Typography.Text strong>arguments</Typography.Text>
                <Input.TextArea
                  rows={10}
                  value={args}
                  spellCheck={false}
                  onChange={(event) => setArgs(event.target.value)}
                  style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--font-body)' }}
                />
                <Space>
                  <Button type="primary" icon={<PlayCircleOutlined />} loading={calling} onClick={() => void handleCall()}>
                    调用
                  </Button>
                  <Button onClick={() => setArgs(defaultArguments(tool))}>重置参数</Button>
                </Space>

                {response?.error && (
                  <Alert type="error" showIcon message={`${response.error.code} · ${response.error.message}`} />
                )}
                {response?.result !== undefined && (
                  <>
                    <Typography.Text strong>响应</Typography.Text>
                    <pre style={{
                      background: 'var(--bg-secondary, #fafafa)', padding: 12, borderRadius: 8, maxHeight: 420,
                      overflow: 'auto', fontSize: 'var(--font-table)', margin: 0
                    }}>
                      {JSON.stringify(response.result, null, 2)}
                    </pre>
                  </>
                )}
              </Space>
            ) : (
              <Empty description="选择左侧工具开始调试" />
            )}
          </DataSurface>

          <DataSurface title="Resources" label="Resources" style={{ marginTop: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              {(info?.resources ?? []).map((resource) => (
                <Space key={resource.uri}>
                  <Tag>{resource.name}</Tag>
                  <code>{resource.uri}</code>
                </Space>
              ))}
              {(info?.resourceTemplates ?? []).map((template) => (
                <Space key={template.uriTemplate}>
                  <Tag>模板</Tag>
                  <code>{template.uriTemplate}</code>
                </Space>
              ))}
            </Space>
          </DataSurface>
        </Col>
      </Row>
    </>
  )
}
