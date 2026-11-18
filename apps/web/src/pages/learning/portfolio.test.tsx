import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningPortfolioPage from './portfolio'

vi.mock('@/api/learning', () => ({
  getProgress: vi.fn(),
  listProjects: vi.fn(),
  listLearningPaths: vi.fn(),
  getLearningPath: vi.fn(),
  trackLearningEvent: vi.fn()
}))
vi.mock('@/hooks/useAuthSession', () => ({
  useAuthSession: () => ({ status: 'authenticated', user: { username: 'alice', role: 'viewer' } })
}))

const progress: api.ProgressView = {
  completed: 3,
  total: 8,
  percent: 38,
  averageScore: 86,
  skills: [
    { code: 'sql-basics', title: 'SQL 基础', level: 'L1', knowledgePoints: ['SELECT'], labs: 2, passedLabs: 2, bestScore: 92 }
  ],
  credentials: [
    { id: 11, type: 'path-completion', title: 'SQL 数据分析入门', issuedAt: '2026-11-10T08:00:00Z' }
  ]
}

const projects: api.ProjectView[] = [
  {
    code: 'proj-order',
    title: '订单分析项目',
    summary: '从明细到指标',
    level: 'L3',
    deliverables: ['结业看板', '口径说明'],
    labCount: 4,
    passedLabs: 2
  },
  {
    code: 'proj-idle',
    title: '未参与项目',
    summary: '尚未开始',
    level: 'L2',
    deliverables: ['未交付物'],
    labCount: 2,
    passedLabs: 0
  }
]

const paths: api.LearningPathSummary[] = [
  { code: 'da-basic', title: '数据分析师入门', level: 'L1-L2', version: 1, estimatedHours: 36,
    labCount: 6, completedLabs: 1, progressPercent: 17 }
]

const pathDetail: api.LearningPathDetail = {
  code: 'da-basic', title: '数据分析师入门', level: 'L1-L2', estimatedHours: 36, skills: [],
  modules: [{
    code: 'da-m1', title: '指标口径', level: 'L1',
    labs: [{
      code: 'da-l1-01', title: '支付成功率日报', level: 'L1', difficulty: 'easy',
      estimatedMinutes: 15, orderNo: 1, status: 'passed', bestScore: 90, passed: true
    }]
  }]
}

function page() {
  return render(
    <MemoryRouter>
      <LearningPortfolioPage />
    </MemoryRouter>
  )
}

describe('learning portfolio page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLearningPaths).mockResolvedValue([])
  })

  it('renders real progress, skills, projects and credentials', async () => {
    vi.mocked(api.getProgress).mockResolvedValue(progress)
    vi.mocked(api.listProjects).mockResolvedValue(projects)
    vi.mocked(api.listLearningPaths).mockResolvedValue(paths)
    vi.mocked(api.getLearningPath).mockResolvedValue(pathDetail)
    page()

    expect(await screen.findByText('SQL 基础')).toBeInTheDocument()
    expect(await screen.findByText('支付成功率日报')).toBeInTheDocument()
    expect(screen.getByText('3/8')).toBeInTheDocument()
    expect(screen.getByText('订单分析项目')).toBeInTheDocument()
    expect(screen.queryByText('未参与项目')).not.toBeInTheDocument()
    expect(screen.getByText('0/1')).toBeInTheDocument()
    expect(screen.getByText('SQL 数据分析入门')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /导出证据包/ })).toBeEnabled()
  })

  it('shows a retryable error state when loading fails', async () => {
    vi.mocked(api.getProgress).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listProjects).mockResolvedValue([])
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('求职证据加载失败')
    expect(screen.getByRole('button', { name: /重新加载/ })).toBeInTheDocument()
  })
  it('exports a markdown evidence bundle without secrets', async () => {
    vi.mocked(api.getProgress).mockResolvedValue(progress)
    vi.mocked(api.listProjects).mockResolvedValue(projects)
    vi.mocked(api.listLearningPaths).mockResolvedValue(paths)
    vi.mocked(api.getLearningPath).mockResolvedValue(pathDetail)
    const create = vi.fn((_blob: Blob) => 'blob:mock')
    const revoke = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revoke })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    page()

    fireEvent.click(await screen.findByRole('button', { name: /导出证据包/ }))

    expect(create).toHaveBeenCalledTimes(1)
    const blob = create.mock.calls[0][0] as Blob
    const text = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(blob)
    })
    expect(text).toContain('# Aetheris 学习与求职证据包')
    expect(text).toContain('不代表企业部署、第三方认证或雇佣承诺')
    expect(text).toContain('| 数据分析师入门 | 支付成功率日报 | L1 | 90 |')
    expect(api.trackLearningEvent).toHaveBeenCalledWith('portfolio_exported', expect.any(Object))
    expect(text).not.toMatch(/password|token|cookie/i)
    click.mockRestore()
  })
  it('shows explicit empty states before any learning activity', async () => {
    vi.mocked(api.getProgress).mockResolvedValue({
      completed: 0, total: 0, percent: 0, averageScore: 0, skills: [], credentials: []
    })
    vi.mocked(api.listProjects).mockResolvedValue([])
    page()

    expect(await screen.findByText('完成第一个任务后生成能力画像。')).toBeInTheDocument()
    expect(screen.getByText('暂无已完成任务。')).toBeInTheDocument()
    expect(screen.getByText('还没有已参与的项目。')).toBeInTheDocument()
    expect(screen.getByText('暂无凭证。')).toBeInTheDocument()
  })
})