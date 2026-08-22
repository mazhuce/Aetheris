export function formatDateTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}
