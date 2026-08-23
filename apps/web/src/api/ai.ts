import http from './http'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatResult {
  role: string
  content: string
  model?: string
}

export function sendChat(messages: ChatMessage[]): Promise<ChatResult> {
  return http.post<ChatResult>('/api/ai/chat', { messages }).then((res) => res.data)
}
