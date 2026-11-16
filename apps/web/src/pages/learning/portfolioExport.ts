import type { ProgressView, ProjectView } from '@/api/learning'

export interface PortfolioSnapshot {
  progress: ProgressView
  projects: ProjectView[]
  username?: string
  generatedAt?: Date
}

export function buildPortfolioMarkdown({
  progress,
  projects,
  username,
  generatedAt = new Date()
}: PortfolioSnapshot): string {
  const lines: string[] = []
  lines.push('# Aetheris 学习与求职证据包', '')
  lines.push(`- 学员：${username || '未署名'}`)
  lines.push(`- 生成时间：${generatedAt.toLocaleString('zh-CN', { hour12: false })}`)
  lines.push(`- 完成进度：${progress.completed}/${progress.total}（${progress.percent}%），平均得分 ${progress.averageScore}`)
  lines.push('', '> 证据来自平台真实判分记录；不代表企业部署、第三方认证或雇佣承诺。', '')
  lines.push('## 能力清单', '', '| 能力 | 等级 | 通过任务 | 最佳得分 |', '| --- | --- | --- | --- |')
  if (progress.skills.length === 0) {
    lines.push('| （暂无） |  |  |  |')
  } else {
    for (const skill of progress.skills) {
      lines.push(`| ${skill.title} | ${skill.level} | ${skill.passedLabs}/${skill.labs} | ${skill.bestScore} |`)
    }
  }
  lines.push('', '## 项目与交付物', '')
  if (projects.length === 0) {
    lines.push('- （暂无项目）')
  } else {
    for (const project of projects) {
      lines.push(`### ${project.title}（${project.level}）`)
      if (project.summary) lines.push('', project.summary)
      lines.push('', `- 任务进度：${project.passedLabs}/${project.labCount}`)
      lines.push(`- 交付物：${project.deliverables.length ? project.deliverables.join('、') : '暂无'}`)
      lines.push('')
    }
  }
  lines.push('## 凭证', '')
  if (progress.credentials.length === 0) {
    lines.push('- （暂无凭证）')
  } else {
    for (const credential of progress.credentials) {
      lines.push(`- ${credential.title}（${new Date(credential.issuedAt).toLocaleDateString('zh-CN')}）`)
    }
  }
  return lines.join('\n') + '\n'
}