import { useEffect, useRef, useState } from 'react'
import { listDataSourceColumns, previewDataSourceTable } from '@/api/metadata'
import type { DataSourceColumn, MetadataTable, QueryResult } from '@/api/metadata'

interface DetailPart<T> {
  loading: boolean
  data?: T
  error?: string
}

interface TableDetail {
  requestId: number
  table: MetadataTable
  columns: DetailPart<DataSourceColumn[]>
  preview: DetailPart<QueryResult>
}

type Part = 'columns' | 'preview'
interface DetailRequest {
  id: number
  table: MetadataTable
  controllers: Partial<Record<Part, AbortController>>
}

function readError(error: unknown, label: string): string {
  const failure = error as { response?: { status?: number }; code?: string } | null
  if (failure?.response?.status === 403) return `无权读取${label}`
  if (failure?.response?.status === 503) return `${label}服务暂时不可用`
  if (failure?.code === 'ECONNABORTED' || failure?.code === 'ETIMEDOUT') return `${label}加载超时`
  return `${label}加载失败，请重试`
}

/** A selection instance owns both reads; same-table reopens receive a fresh instance. */
export function useTableDetail() {
  const [detail, setDetail] = useState<TableDetail>()
  const sequence = useRef(0)
  const current = useRef<DetailRequest>()
  const mounted = useRef(false)

  function discard() {
    const previous = current.current
    current.current = undefined
    previous?.controllers.columns?.abort()
    previous?.controllers.preview?.abort()
  }

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      const previous = current.current
      current.current = undefined
      previous?.controllers.columns?.abort()
      previous?.controllers.preview?.abort()
    }
  }, [])

  function load<T>(request: DetailRequest, part: Part, label: string,
    query: (signal: AbortSignal) => Promise<T>, apply: (state: DetailPart<T>) => void) {
    // The ref changes synchronously, before a render, so repeated clicks cannot overlap reads.
    if (!mounted.current || current.current !== request || request.controllers[part]) return
    const controller = new AbortController()
    request.controllers[part] = controller
    apply({ loading: true })
    const isCurrent = () => mounted.current && current.current === request && !controller.signal.aborted
    async function readPart() {
      try {
        const data = await query(controller.signal)
        if (isCurrent()) apply({ loading: false, data })
      } catch (error) {
        // Errors from a discarded selection must not overwrite the new selection either.
        if (isCurrent()) apply({ loading: false, error: readError(error, label) })
      } finally {
        if (request.controllers[part] === controller) delete request.controllers[part]
      }
    }
    readPart().catch(() => {
      if (isCurrent()) apply({ loading: false, error: `${label}加载失败，请重试` })
    })
  }

  function loadColumns(request: DetailRequest) {
    const table = request.table
    if (!table.datasourceId) return
    load(request, 'columns', '字段结构',
      (signal) => listDataSourceColumns(table.datasourceId!, table.schemaName ?? 'public', table.tableName, { signal }),
      (columns) => setDetail((value) => value?.requestId === request.id ? { ...value, columns } : value))
  }

  function loadPreview(request: DetailRequest) {
    const table = request.table
    if (!table.datasourceId) return
    load(request, 'preview', '数据预览',
      (signal) => previewDataSourceTable(table.datasourceId!, table.schemaName ?? 'public', table.tableName, { signal }),
      (preview) => setDetail((value) => value?.requestId === request.id ? { ...value, preview } : value))
  }

  function open(table: MetadataTable) {
    discard()
    const request: DetailRequest = { id: ++sequence.current, table: { ...table }, controllers: {} }
    current.current = request
    setDetail({ requestId: request.id, table: request.table, columns: { loading: false }, preview: { loading: false } })
    loadColumns(request)
    loadPreview(request)
  }

  function close() {
    discard()
    setDetail(undefined)
  }

  function retry(part: Part) {
    const request = current.current
    // A handler from an old render cannot execute an operation for the new table.
    if (!request || request.id !== detail?.requestId || !detail[part].error) return
    if (part === 'columns') loadColumns(request)
    else loadPreview(request)
  }

  return { detail, open, close, retry }
}
