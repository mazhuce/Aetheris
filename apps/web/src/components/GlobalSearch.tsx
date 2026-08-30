import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Empty, Input, Modal, Spin, Tag } from 'antd'
import type { InputRef } from 'antd'
import {
  ApartmentOutlined,
  FolderOutlined,
  SearchOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { navGroups } from '@/constants/navigation'
import { searchTables, warehouseLayerLabel, type MetadataTable } from '@/api/metadata'

interface NavResult {
  kind: 'nav'
  key: string
  title: string
  subtitle: string
  path: string
}

interface TableResult {
  kind: 'table'
  key: string
  title: string
  subtitle: string
  path: string
  table: MetadataTable
}

type Result = NavResult | TableResult

/**
 * 控制台顶部全局搜索：⌘K / Ctrl+K 唤起，同时检索导航入口与元数据表。
 *
 * 导航入口在前端本地匹配、即时返回；元数据表走后端 /search 接口（表名/表注释/业务域）。
 * 两种结果合并到同一列表，用键盘上下键选择、回车跳转。
 */
export default function GlobalSearch() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [tableLoading, setTableLoading] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const inputRef = useRef<InputRef>(null)

  // ⌘K / Ctrl+K 唤起，与顶部触发按钮上标注的快捷键一致。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault()
        setOpen(true)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  // 打开时清空上次结果并聚焦输入框。
  useEffect(() => {
    if (open) {
      setKeyword('')
      setTables([])
      setActiveIndex(0)
      // Modal 的 focus 有延迟，等待动画结束后再聚焦。
      const timer = setTimeout(() => inputRef.current?.focus(), 80)
      return () => clearTimeout(timer)
    }
  }, [open])

  // 元数据表搜索做防抖，避免每敲一个字都打一次后端。
  useEffect(() => {
    const trimmed = keyword.trim()
    if (!trimmed) {
      setTables([])
      setTableLoading(false)
      return
    }
    setTableLoading(true)
    const timer = setTimeout(async () => {
      try {
        setTables(await searchTables(trimmed, 8))
      } catch {
        setTables([])
      } finally {
        setTableLoading(false)
      }
    }, 250)
    return () => clearTimeout(timer)
  }, [keyword])

  const navResults = useMemo<NavResult[]>(() => {
    const trimmed = keyword.trim().toLowerCase()
    const matches: NavResult[] = []
    for (const group of navGroups) {
      for (const item of group.items) {
        if (!trimmed || item.label.toLowerCase().includes(trimmed) || group.label.toLowerCase().includes(trimmed)) {
          matches.push({
            kind: 'nav',
            key: `nav-${item.key}`,
            title: item.label,
            subtitle: group.label,
            path: item.path
          })
        }
      }
    }
    return matches
  }, [keyword])

  const results = useMemo<Result[]>(() => {
    const tableResults: TableResult[] = tables.map((table) => ({
      kind: 'table',
      key: `table-${table.id}`,
      title: table.tableName,
      subtitle: table.tableComment || table.schemaName || table.datasourceName,
      path: '/metadata/tables',
      table
    }))
    return [...navResults, ...tableResults]
  }, [navResults, tables])

  // 结果变化时把选中项重置到首条，避免 activeIndex 越界。
  useEffect(() => {
    setActiveIndex(0)
  }, [results.length])

  const goTo = useCallback(
    (result: Result) => {
      setOpen(false)
      if (result.kind === 'nav') {
        navigate(result.path)
      } else {
        navigate(result.path, { state: { searchKeyword: result.table.tableName } })
      }
    },
    [navigate]
  )

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActiveIndex((index) => Math.min(index + 1, results.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActiveIndex((index) => Math.max(index - 1, 0))
    } else if (event.key === 'Enter' && results.length > 0) {
      event.preventDefault()
      goTo(results[activeIndex])
    }
  }

  const iconOf = (result: Result) =>
    result.kind === 'nav' ? <FolderOutlined /> : <ApartmentOutlined />

  return (
    <>
      <button className="command-trigger" onClick={() => setOpen(true)}>
        <SearchOutlined />
        <span>搜索或跳转</span>
        <kbd>⌘ K</kbd>
      </button>
      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        closable={false}
        width={620}
        className="global-search-modal"
        destroyOnClose
      >
        <div className="global-search">
          <Input
            ref={inputRef}
            size="large"
            prefix={<SearchOutlined style={{ color: '#707075' }} />}
            placeholder="搜索导航或元数据表…"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onKeyDown={onKeyDown}
            allowClear
            variant="borderless"
          />
          <div className="global-search-results">
            {results.length === 0 && !tableLoading && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={keyword.trim() ? '未找到匹配结果' : '输入关键词开始搜索'} style={{ margin: '32px 0' }} />
            )}
            {tableLoading && results.length === 0 && (
              <div style={{ textAlign: 'center', padding: 32 }}><Spin size="small" /></div>
            )}
            <ul className="global-search-list">
              {results.map((result, index) => (
                <li
                  key={result.key}
                  className={`global-search-item${index === activeIndex ? ' is-active' : ''}`}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => goTo(result)}
                >
                  <span className="global-search-item-icon">{iconOf(result)}</span>
                  <span className="global-search-item-text">
                    <span className="global-search-item-title">{result.title}</span>
                    <span className="global-search-item-subtitle">{result.subtitle}</span>
                  </span>
                  {result.kind === 'nav' ? (
                    <Tag>导航</Tag>
                  ) : (
                    <Tag color="blue">{warehouseLayerLabel(result.table.layer)}</Tag>
                  )}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </Modal>
    </>
  )
}
