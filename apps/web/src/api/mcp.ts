import http from './http'

export interface McpToolInfo {
  name: string
  title: string
  description: string
  inputSchema: Record<string, unknown>
  riskLevel: number
}

export interface McpPromptInfo {
  name: string
  title: string
  description: string
  arguments: { name: string; description: string; required: boolean }[]
}

export interface McpInfo {
  protocolVersion: string
  learner: string
  tools: McpToolInfo[]
  resources: { uri: string; name: string; description: string; mimeType: string }[]
  resourceTemplates: { uriTemplate: string; name: string; description: string; mimeType: string }[]
  prompts: McpPromptInfo[]
  rateLimitPerMinute: number
}

export interface JsonRpcResponse<T = unknown> {
  jsonrpc: string
  id: number | string | null
  result?: T
  error?: { code: number; message: string }
}

export const getMcpInfo = () => http.get<McpInfo>('/api/mcp/info').then((res) => res.data)

export const callMcp = <T = unknown>(method: string, params?: unknown) =>
  http
    .post<JsonRpcResponse<T>>('/api/mcp', {
      jsonrpc: '2.0',
      id: Date.now(),
      method,
      params: params ?? {}
    })
    .then((res) => res.data)
