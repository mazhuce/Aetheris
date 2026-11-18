#!/usr/bin/env node
// 在线 AI 验收（DeepSeek Key 到位后运行）：
// 1) 检查 ai-agent 与 learning-service 是否已拿到 Key；
// 2) 用测试学员验证「数据开发专家」与「AI 导师」均返回模型回答（非规则兜底）。
// 未配置 Key 时退出码 2，并打印配置方法，不产生任何模型调用。
// 用法：node scripts/ai-online-acceptance.mjs
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const api = process.env.AETHERIS_API_ORIGIN ?? 'http://127.0.0.1:8080'

function containerEnv(container, key) {
  const result = spawnSync('docker', ['exec', container, 'printenv', key], { encoding: 'utf8' })
  return result.status === 0 ? result.stdout.trim() : ''
}

function readCredentials() {
  if (process.env.AETHERIS_E2E_USERNAME && process.env.AETHERIS_E2E_PASSWORD) {
    return { username: process.env.AETHERIS_E2E_USERNAME, password: process.env.AETHERIS_E2E_PASSWORD }
  }
  const file = path.join(root, 'configs', 'security', 'private', 'e2e-learner.env')
  if (!fs.existsSync(file)) throw new Error('缺少测试账号：设置 AETHERIS_E2E_* 或提供 e2e-learner.env')
  const values = Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .filter((line) => line.includes('='))
    .map((line) => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)] }))
  return { username: values.E2E_LEARNER_USERNAME, password: values.E2E_LEARNER_PASSWORD }
}

const expertKey = containerEnv('aetheris-ai-agent-1', 'DEEPSEEK_API_KEY')
const tutorKey = containerEnv('aetheris-learning-service-1', 'AETHERIS_AI_API_KEY')
const status = {
  expertKeyConfigured: expertKey.length > 0,
  tutorKeyConfigured: tutorKey.length > 0,
  expert: null,
  tutor: null
}

if (expertKey.length === 0 && tutorKey.length === 0) {
  console.log(JSON.stringify({
    ...status,
    ok: false,
    reason: '未配置模型密钥，跳过在线验收（不会发起模型调用）',
    next: [
      '在 .env 中设置 DEEPSEEK_API_KEY=<key>（专家）与 AETHERIS_AI_API_KEY=<key>（导师）',
      'docker compose up -d ai-agent learning-service',
      '重新运行 node scripts/ai-online-acceptance.mjs'
    ]
  }, null, 2))
  process.exit(2)
}

const credentials = readCredentials()
const login = await fetch(`${api}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(credentials)
})
if (!login.ok) throw new Error(`登录失败：${login.status}`)
const cookie = login.headers.getSetCookie().map((entry) => entry.split(';')[0]).join('; ')

if (status.tutorKeyConfigured) {
  const response = await fetch(`${api}/api/learning/labs/da-l1-01/tutor`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', cookie },
    body: JSON.stringify({ message: '窗口函数和数据仓库分层有什么关系？请用一句话说明。' })
  })
  const body = await response.json()
  status.tutor = {
    status: response.status,
    fallback: body.fallback,
    model: body.model,
    replyLength: (body.reply ?? '').length,
    ok: response.status === 200 && body.fallback === false && (body.reply ?? '').length > 0
  }
}

if (status.expertKeyConfigured) {
  const conversation = await fetch(`${api}/api/ai/conversations`, { method: 'POST', headers: { cookie } })
  if (!conversation.ok) throw new Error(`创建会话失败：${conversation.status}`)
  const { id } = await conversation.json()
  const response = await fetch(`${api}/api/ai/chat`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', cookie },
    body: JSON.stringify({
      conversationId: id,
      messages: [{ role: 'user', content: '用一句话说明 ODS 和 DWD 的区别。' }],
      dataMode: false,
      mode: 'expert',
      context: '当前页面：学习中心'
    })
  })
  const body = await response.json()
  status.expert = {
    status: response.status,
    model: body.model ?? null,
    replyLength: (body.content ?? '').length,
    detail: (body.content ?? '').slice(0, 160),
    ok: response.status === 200 && (body.content ?? '').length > 0
  }
}

const ok = (status.tutor === null || status.tutor.ok) && (status.expert === null || status.expert.ok)
console.log(JSON.stringify({ ...status, ok }, null, 2))
process.exit(ok ? 0 : 1)