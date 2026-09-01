import { formatDateTime, formatNumber } from './format'

describe('formatDateTime', () => {
  it('空值返回占位符', () => {
    expect(formatDateTime()).toBe('—')
    expect(formatDateTime('')).toBe('—')
  })

  it('非法日期字符串原样返回', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date')
  })

  it('格式化为 yyyy-MM-dd HH:mm（本地时区）', () => {
    // 不带时区的日期时间字符串按本地时区解析
    expect(formatDateTime('2026-01-02T03:04:00')).toBe('2026-01-02 03:04')
    expect(formatDateTime('2026-12-31T23:59:00')).toBe('2026-12-31 23:59')
  })

  it('单位数月/日/时/分补零', () => {
    expect(formatDateTime('2026-03-05T07:08:00')).toBe('2026-03-05 07:08')
  })
})

describe('formatNumber', () => {
  it('按 zh-CN 千分位格式化', () => {
    expect(formatNumber(0)).toBe('0')
    expect(formatNumber(999)).toBe('999')
    expect(formatNumber(1234567)).toBe('1,234,567')
  })
})
