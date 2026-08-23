import http from './http'

export interface LoginResult {
  token: string
  username: string
  nickname?: string
  expiresIn: number
}

export function login(username: string, password: string): Promise<LoginResult> {
  return http.post<LoginResult>('/api/auth/login', { username, password }).then((res) => res.data)
}
