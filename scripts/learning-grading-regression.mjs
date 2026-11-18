#!/usr/bin/env node
// 全量判分回归：逐个提交每个已发布路径任务的标准答案，断言得分达到通过线。
// 这是验收工具，会从数据库读取 expected_sql；学员端不会暴露标准答案。
// 用法：node scripts/learning-grading-regression.mjs [--reset]
// 凭据取自 AETHERIS_E2E_* 或 gitignored 的 configs/security/private/e2e-learner.env。
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const origin = process.env.AETHERIS_API_ORIGIN ?? 'http://127.0.0.1:8080'
const container = process.env.AETHERIS_PG_CONTAINER ?? 'aetheris-postgres-1'
const dbUser = process.env.AETHERIS_PG_USER ?? 'aetheris'
const dbName = process.env.AETHERIS_PG_DATABASE ?? 'aetheris'
const shouldReset = process.argv.includes('--reset')

function readCredentials() {
  if (process.env.AETHERIS_E2E_USERNAME && process.env.AETHERIS_E2E_PASSWORD) {
    return { username: process.env.AETHERIS_E2E_USERNAME, password: process.env.AETHERIS_E2E_PASSWORD }
  }
  const file = path.join(root, 'configs', 'security', 'private', 'e2e-learner.env')
  if (!fs.existsSync(file)) throw new Error('缺少测试账号：设置 AETHERIS_E2E_USERNAME/AETHERIS_E2E_PASSWORD 或提供 e2e-learner.env')
  const values = Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .filter((line) => line.includes('='))
    .map((line) => {
      const index = line.indexOf('=')
      return [line.slice(0, index), line.slice(index + 1)]
    }))
  return { username: values.E2E_LEARNER_USERNAME, password: values.E2E_LEARNER_PASSWORD }
}

const dump = execFileSync('docker', ['exec', '-i', container, 'psql', '-U', dbUser, '-d', dbName, '-t', '-A', '-c', `
  select coalesce(json_agg(x order by x.path_code, x.module_order, x.order_no), '[]'::json) from (
    select p.code as path_code, m.order_no as module_order, l.order_no,
           l.code, l.task_type, l.expected_sql, l.pass_score
    from learning.learning_path p
    join learning.course c on c.path_code = p.code
    join learning.course_module m on m.course_code = c.code
    join learning.lab l on l.module_code = m.code
    where p.status='published' and l.status='published' and l.expected_sql is not null
  ) x;
`], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }).trim()
const labs = JSON.parse(dump)
if (labs.length === 0) throw new Error('数据库中没有可回归的任务，请确认容器与迁移状态')

const credentials = readCredentials()
const login = await fetch(`${origin}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(credentials)
})
if (!login.ok) throw new Error(`登录失败：${login.status}`)
const cookie = login.headers.getSetCookie().map((entry) => entry.split(';')[0]).join('; ')

const failures = []
for (const lab of labs) {
  try {
    if (shouldReset) {
      await fetch(`${origin}/api/learning/labs/${lab.code}/reset`, { method: 'POST', headers: { cookie } })
    }
    const response = await fetch(`${origin}/api/learning/labs/${lab.code}/submit`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', cookie },
      body: JSON.stringify({ sql: lab.expected_sql, notes: 'full grading regression' })
    })
    const body = await response.json()
    const passed = response.status === 200 && body.passed === true && body.score >= lab.pass_score
    if (!passed) {
      failures.push({ code: lab.code, status: response.status, score: body.score, passed: body.passed,
        detail: (body.result?.summary ?? body.message ?? '').slice(0, 160) })
      console.log(`FAIL ${lab.path_code} ${lab.code} [${lab.task_type}] score=${body.score ?? '-'} ${(body.result?.summary ?? body.message ?? '').slice(0, 120)}`)
    } else {
      console.log(`PASS ${lab.path_code} ${lab.code} [${lab.task_type}] score=${body.score}`)
    }
  } catch (error) {
    failures.push({ code: lab.code, error: String(error).slice(0, 160) })
    console.log(`FAIL ${lab.code} error=${String(error).slice(0, 120)}`)
  }
}
console.log(JSON.stringify({ total: labs.length, failures: failures.length, details: failures }, null, 2))
process.exit(failures.length === 0 ? 0 : 1)