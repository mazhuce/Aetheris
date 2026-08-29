import http from './http'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatResult {
  role: string
  content: string
  model?: string
  conversationId?: number
}

export interface ChatConversation {
  id: number
  ownerUsername?: string
  title: string
  createdAt?: string
  updatedAt?: string
}

export function listConversations(): Promise<ChatConversation[]> {
  return http.get<ChatConversation[]>('/api/ai/conversations').then((res) => res.data)
}

export function createConversation(): Promise<ChatConversation> {
  return http.post<ChatConversation>('/api/ai/conversations').then((res) => res.data)
}

export function deleteConversation(id: number): Promise<void> {
  return http.delete(`/api/ai/conversations/${id}`).then(() => undefined)
}

export function listMessages(conversationId: number): Promise<ChatMessage[]> {
  return http.get<ChatMessage[]>(`/api/ai/conversations/${conversationId}/messages`).then((res) => res.data)
}

export function sendChat(
  conversationId: number,
  messages: ChatMessage[],
  dataMode = false
): Promise<ChatResult> {
  // 数据模式下 AI 需多轮调用工具（检索表 → 看结构 → 执行查询），
  // 耗时明显高于普通对话，因此单独放宽超时。
  return http
    .post<ChatResult>(
      '/api/ai/chat',
      { conversationId, messages, dataMode },
      { timeout: dataMode ? 300000 : 70000 }
    )
    .then((res) => res.data)
}
