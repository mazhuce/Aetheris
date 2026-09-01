import { render, screen } from '@testing-library/react'
import StatusBadge from './StatusBadge'

describe('StatusBadge', () => {
  it('渲染内容并默认带圆点和 neutral 色调', () => {
    render(<StatusBadge>运行中</StatusBadge>)
    const badge = screen.getByText('运行中').closest('span.status-badge')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveClass('status-badge', 'is-neutral')
    expect(badge?.querySelector('.status-badge-dot')).toBeInTheDocument()
  })

  it('应用自定义色调与附加 className', () => {
    render(<StatusBadge tone="danger" className="extra">失败</StatusBadge>)
    const badge = screen.getByText('失败').closest('span.status-badge')
    expect(badge).toHaveClass('is-danger', 'extra')
  })

  it('dot 为 false 时不渲染圆点', () => {
    render(<StatusBadge dot={false}>成功</StatusBadge>)
    expect(document.querySelector('.status-badge-dot')).not.toBeInTheDocument()
  })
})
