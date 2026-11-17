#!/usr/bin/env node
// 真实样例数据导入工具（L09 预留）：校验 CSV → 备份 → 导入 teach 表 → 登记清单。
// 默认 dry-run；确认无误后加 --apply。导入与校验通过 docker exec psql 执行。
// 已验证：合成 CSV 的 dry-run 校验通过；apply 会写入教学表，需在授权的执行环境运行。
// 用法：
//   node scripts/import-teaching-sample.mjs --csv sample.csv --table teach.orders_raw \
//     --version 2 --license internal-training --source desensitized --pii-reviewed [--apply] [--note "..."]
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const container = process.env.AETHERIS_PG_CONTAINER ?? 'aetheris-postgres-1'
const dbUser = process.env.AETHERIS_PG_USER ?? 'aetheris'
const dbName = process.env.AETHERIS_PG_DATABASE ?? 'aetheris'
const MAX_ROWS = 50000
const MAX_BYTES = 20 * 1024 * 1024

function parseArgs(argv) {
  const args = {}
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]
    if (!token.startsWith('--')) continue
    const key = token.slice(2)
    if (key === 'apply' || key === 'pii-reviewed') { args[key] = true; continue }
    args[key] = argv[++i]
  }
  return args
}

function parseCsv(text) {
  const records = []
  let field = ''
  let record = []
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++ } else quoted = false
      } else field += c
      continue
    }
    if (c === '"') { quoted = true; continue }
    if (c === ',') { record.push(field); field = ''; continue }
    if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      record.push(field); field = ''
      if (record.length > 1 || record[0] !== '') records.push(record)
      record = []
      continue
    }
    field += c
  }
  if (field !== '' || record.length > 0) { record.push(field); records.push(record) }
  return records
}

function psql(sql, { input, quiet = true } = {}) {
  const flags = ['exec', '-i', container, 'psql', '-U', dbUser, '-d', dbName, '-v', 'ON_ERROR_STOP=1', '-t', '-A']
  if (quiet) flags.push('-q')
  flags.push('-c', sql)
  const result = spawnSync('docker', flags, { input, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`psql 失败：${(result.stderr || result.stdout || '').trim().slice(0, 400)}`)
  return result.stdout.trim()
}

function quote(value) {
  return `'${String(value).replace(/'/g, "''")}'`
}

const args = parseArgs(process.argv.slice(2))
const failures = []
const table = String(args.table ?? '')
const version = Number.parseInt(String(args.version ?? ''), 10)
const csvPath = args.csv ? path.resolve(root, args.csv) : ''
if (!csvPath || !fs.existsSync(csvPath)) failures.push('缺少 --csv 或文件不存在')
if (!/^teach\.[a-z_][a-z0-9_]*$/.test(table)) failures.push('--table 必须是 teach.<表名>')
if (!Number.isInteger(version) || version < 1) failures.push('--version 必须是正整数')
if (!args.license) failures.push('缺少 --license')
if (!args.source) failures.push('缺少 --source')
if (!args['pii-reviewed']) failures.push('真实样例必须先完成 PII 复核并显式传 --pii-reviewed')

let records = []
let sizeBytes = 0
if (failures.length === 0) {
  sizeBytes = fs.statSync(csvPath).size
  if (sizeBytes > MAX_BYTES) failures.push(`CSV 超过 20MB：${sizeBytes} 字节`)
  records = parseCsv(fs.readFileSync(csvPath, 'utf8'))
  const dataRows = Math.max(0, records.length - 1)
  if (dataRows > MAX_ROWS) failures.push(`数据行超过 5 万：${dataRows} 行`)
  if (dataRows === 0) failures.push('CSV 没有数据行')
}

let tableColumns = []
if (failures.length === 0) {
  const [, schema, name] = table.match(/^(teach)\.([a-z_][a-z0-9_]*)$/)
  const exists = psql(`SELECT to_regclass('${table}') IS NOT NULL`)
  if (exists !== 't') failures.push(`目标表不存在：${table}`)
  else {
    tableColumns = psql(`SELECT column_name FROM information_schema.columns WHERE table_schema='${schema}' AND table_name='${name}' ORDER BY ordinal_position`).split('\n').filter(Boolean)
    const header = records[0].map((column) => column.trim())
    const unknown = header.filter((column) => !tableColumns.includes(column))
    if (unknown.length > 0) failures.push(`CSV 列不存在于目标表：${unknown.join(', ')}`)
    const duplicates = header.filter((column, index) => header.indexOf(column) !== index)
    if (duplicates.length > 0) failures.push(`CSV 表头重复列：${duplicates.join(', ')}`)
  }
}

if (failures.length === 0) {
  const existing = psql(`SELECT count(*) FROM learning.dataset_manifest WHERE code='${table}' AND version=${version}`)
  if (existing !== '0') failures.push(`清单已存在 ${table}@${version}，请换一个版本号`)
}

const dataRows = Math.max(0, records.length - 1)
const summary = {
  table,
  version,
  csv: csvPath,
  sizeBytes,
  dataRows,
  columns: records[0] ?? [],
  mode: args.apply ? 'apply' : 'dry-run'
}

if (failures.length > 0) {
  console.error(JSON.stringify({ ...summary, ok: false, failures }, null, 2))
  process.exit(1)
}

if (!args.apply) {
  console.log(JSON.stringify({
    ...summary,
    ok: true,
    checks: ['大小与行数在限额内', 'CSV 列与目标表匹配', '版本号未被占用'],
    next: '确认无误后加 --apply 执行导入；导入前会自动备份到 teach_import_backup。'
  }, null, 2))
  process.exit(0)
}

const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14)
const backupTable = `teach_import_backup.${table.split('.')[1]}_${stamp}`
const columnList = records[0].map((column) => column.trim()).join(', ')
let imported = false
try {
  psql(`CREATE SCHEMA IF NOT EXISTS teach_import_backup`)
  psql(`CREATE TABLE ${backupTable} AS TABLE ${table}`)
  psql(`TRUNCATE TABLE ${table}`)
  psql(`\\copy ${table} (${columnList}) FROM STDIN WITH (FORMAT csv, HEADER true)`, { input: fs.readFileSync(csvPath, 'utf8') })
  imported = true
  const stats = psql(`SELECT count(*) || '|' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM ${table} t`).split('|')
  const rowCount = Number.parseInt(stats[0], 10)
  const checksum = stats[1] || null
  psql(`INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, status, note)
        VALUES ('${table}', ${version}, '${table}', ${rowCount}, ${checksum ? quote(checksum) : 'NULL'}, ${quote(args.license)}, ${quote(args.source)}, true, 'published', ${args.note ? quote(args.note) : 'NULL'})`)
  console.log(JSON.stringify({
    ...summary,
    ok: true,
    rowCount,
    checksum,
    backupTable,
    next: ['内容运营 → 数据样例 → 校验一致性', '对绑定该表的任务各跑一次标准答案与错误答案', `回滚：TRUNCATE ${table}; INSERT INTO ${table} SELECT * FROM ${backupTable};`]
  }, null, 2))
} catch (error) {
  if (imported || psqlSupportsRollback(backupTable, table)) {
    try {
      psql(`TRUNCATE TABLE ${table}`)
      psql(`INSERT INTO ${table} SELECT * FROM ${backupTable}`)
      console.error(JSON.stringify({ ...summary, ok: false, error: String(error).slice(0, 400), restoredFrom: backupTable }, null, 2))
    } catch (restoreError) {
      console.error(JSON.stringify({ ...summary, ok: false, error: String(error).slice(0, 400), restoreError: String(restoreError).slice(0, 300), backupTable }, null, 2))
    }
  } else {
    console.error(JSON.stringify({ ...summary, ok: false, error: String(error).slice(0, 400) }, null, 2))
  }
  process.exit(1)
}

function psqlSupportsRollback(backup, target) {
  try {
    return psql(`SELECT to_regclass('${backup}') IS NOT NULL`) === 't'
  } catch {
    return false
  }
}