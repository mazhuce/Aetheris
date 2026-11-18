#!/usr/bin/env node
// 真实样例落库（幂等）：UCI 在线购物意向数据集（CC BY 4.0）。
// 清单已登记则跳过；否则调用导入工具执行校验、备份、导入与登记。
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const container = process.env.AETHERIS_PG_CONTAINER ?? 'aetheris-postgres-1'
const dbUser = process.env.AETHERIS_PG_USER ?? 'aetheris'
const dbName = process.env.AETHERIS_PG_DATABASE ?? 'aetheris'
const csv = 'artifacts/learning-pivot-20261117/sample-data/online_shoppers.csv'

function psql(sql) {
  const result = spawnSync('docker', ['exec', container, 'psql', '-U', dbUser, '-d', dbName,
    '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-q', '-c', sql], { encoding: 'utf8' })
  if (result.status !== 0) throw new Error((result.stderr || result.stdout || '').trim())
  return result.stdout.trim()
}

if (!fs.existsSync(path.join(root, csv))) {
  console.error(JSON.stringify({ ok: false, error: `缺少样例 CSV：${csv}` }, null, 2))
  process.exit(1)
}

let registered
try {
  registered = psql("SELECT count(*) FROM learning.dataset_manifest WHERE code='teach.online_shoppers' AND version=1")
} catch (error) {
  console.error(JSON.stringify({ ok: false, error: `无法查询清单：${error.message}` }, null, 2))
  process.exit(1)
}

if (registered !== '0') {
  const rows = psql('SELECT count(*) FROM teach.online_shoppers')
  console.log(JSON.stringify({ ok: true, skipped: true, rows: Number(rows), csv }, null, 2))
  process.exit(0)
}

const result = spawnSync(process.execPath, [
  'scripts/import-teaching-sample.mjs',
  '--csv', csv,
  '--table', 'teach.online_shoppers',
  '--version', '1',
  '--license', 'CC BY 4.0',
  '--source', 'UCI ML Repository id=468 Online Shoppers Purchasing Intention',
  '--pii-reviewed',
  '--apply',
  '--note', 'Real open dataset; attribution in sample-data/LICENSE.txt'
], { cwd: root, encoding: 'utf8' })
process.stdout.write(result.stdout || '')
process.stderr.write(result.stderr || '')
process.exit(result.status ?? 1)