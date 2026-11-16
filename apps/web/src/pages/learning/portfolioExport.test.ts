import { describe, expect, it } from 'vitest'
import { buildPortfolioMarkdown } from './portfolioExport'
import type { ProgressView, ProjectView } from '@/api/learning'

const progress: ProgressView = {
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

const projects: ProjectView[] = [
  {
    code: 'proj-order',
    title: '订单分析项目',
    summary: '从明细到指标',
    level: 'L3',
    deliverables: ['结业看板', '口径说明'],
    labCount: 4,
    passedLabs: 2
  }
]

describe('portfolio export', () => {
  it('includes progress, skills, projects, credentials and the evidence disclaimer', () => {
    const markdown = buildPortfolioMarkdown({
      progress,
      projects,
      labEvidence: [{ pathTitle: '数据分析师入门', code: 'da-l1-01', title: '支付成功率日报', level: 'L1', score: 90 }],
      username: 'alice',
      generatedAt: new Date('2026-11-17T10:00:00Z')
    })

    expect(markdown).toContain('# Aetheris 学习与求职证据包')
    expect(markdown).toContain('学员：alice')
    expect(markdown).toContain('完成进度：3/8（38%）')
    expect(markdown).toContain('| SQL 基础 | L1 | 2/2 | 92 |')
    expect(markdown).toContain('| 数据分析师入门 | 支付成功率日报 | L1 | 90 |')
    expect(markdown).toContain('订单分析项目')
    expect(markdown).toContain('交付物：结业看板、口径说明')
    expect(markdown).toContain('SQL 数据分析入门')
    expect(markdown).toContain('不代表企业部署、第三方认证或雇佣承诺')
    expect(markdown).not.toMatch(/password|token|cookie/i)
  })
})