import http from './http'

export interface LearningPathSummary {
  code: string
  title: string
  description?: string
  targetRole?: string
  level: string
  version: number
  estimatedHours: number
  labCount: number
  completedLabs: number
  progressPercent: number
}

export interface LabCard {
  code: string
  title: string
  level: string
  difficulty: string
  summary?: string
  estimatedMinutes: number
  orderNo: number
  status: 'not_started' | 'in_progress' | 'passed'
  bestScore?: number
  passed: boolean
}

export interface ModuleView {
  code: string
  title: string
  objective?: string
  level: string
  /** 本模块对应平台里的功能页面，例如「SQL开发 · 元数据管理」。 */
  relatedFeatures?: string[]
  labs: LabCard[]
}

export interface SkillView {
  code: string
  title: string
  level: string
  knowledgePoints: string[]
  outcome?: string
  labs: number
  passedLabs: number
  bestScore: number
}

export interface LearningPathDetail {
  code: string
  title: string
  description?: string
  targetRole?: string
  level: string
  estimatedHours: number
  modules: ModuleView[]
  skills: SkillView[]
}

export interface LabStep {
  title: string
  detail: string
}

export interface RubricItem {
  key: string
  name: string
  weight: number
}

export interface LabDetail {
  code: string
  title: string
  level: string
  difficulty: string
  taskType: string
  summary?: string
  scenario?: string
  objective?: string
  steps: LabStep[]
  tableRefs: string[]
  starterSql?: string
  passScore: number
  estimatedMinutes: number
  version: number
  projectCode?: string
  rubric: RubricItem[]
  sandboxSchema?: string
  sandboxActive: boolean
  bestScore?: number
  passed: boolean
  lastStatus: string
  /** 本任务对应平台里的功能页面。 */
  relatedFeatures?: string[]
  /** 随任务讲解的知识点（SQL 基础、数仓环节概念等）。 */
  knowledge?: LabKnowledge[]
  /** 任务绑定的样例数据集引用（如 teach.orders_raw@1），多表任务会有多项。 */
  datasetRefs?: string[]
}

export interface LabKnowledge {
  title: string
  detail: string
}

export interface SandboxInfo {
  schemaName: string
  status: string
  tier?: string
  expiresAt: string
  createdAt: string
  lastResetAt?: string
}

export interface GradingItemView {
  key: string
  name: string
  weight: number
  passed: boolean
  detail: string
}

export interface GradingView {
  score: number
  passed: boolean
  summary: string
  items: GradingItemView[]
  sampleColumns: string[]
  sampleRows: string[][]
  hints: string[]
}

export interface SubmissionView {
  id: number
  labCode: string
  attempt: number
  sql?: string
  notes?: string
  status: string
  score?: number
  passed?: boolean
  createdAt?: string
  gradedAt?: string
  result?: GradingView
}

export interface ProgressView {
  completed: number
  total: number
  percent: number
  averageScore: number
  skills: SkillView[]
  credentials: { id: number; type: string; title: string; issuedAt: string }[]
}

export interface HintView {
  level: number
  title: string
  message: string
  lastHint: boolean
}

export const listLearningPaths = () => http.get<LearningPathSummary[]>('/api/learning/paths').then((res) => res.data)

export const getLearningPath = (code: string) =>
  http.get<LearningPathDetail>(`/api/learning/paths/${code}`).then((res) => res.data)

export const getLab = (code: string) => http.get<LabDetail>(`/api/learning/labs/${code}`).then((res) => res.data)

export const startLab = (code: string) =>
  http.post<SandboxInfo>(`/api/learning/labs/${code}/start`).then((res) => res.data)

export const resetLab = (code: string) =>
  http.post<SandboxInfo>(`/api/learning/labs/${code}/reset`).then((res) => res.data)

export const submitLab = (code: string, sql: string, notes?: string) =>
  http.post<SubmissionView>(`/api/learning/labs/${code}/submit`, { sql, notes }).then((res) => res.data)

export const getSubmission = (id: number) =>
  http.get<SubmissionView>(`/api/learning/submissions/${id}`).then((res) => res.data)

export const listSubmissions = (code: string) =>
  http.get<SubmissionView[]>(`/api/learning/labs/${code}/submissions`).then((res) => res.data)

export const requestHint = (code: string) =>
  http.post<HintView>(`/api/learning/labs/${code}/hint`).then((res) => res.data)

export const getProgress = () => http.get<ProgressView>('/api/learning/progress').then((res) => res.data)

export interface ProjectView {
  code: string
  title: string
  summary?: string
  level: string
  scenario?: string
  deliverables: string[]
  labCount: number
  passedLabs: number
}

export interface ProjectDetail extends ProjectView {
  labs: LabCard[]
}

export interface LabAdminView {
  id: number
  code: string
  moduleCode: string
  projectCode?: string
  title: string
  level: string
  difficulty: string
  summary?: string
  objective?: string
  status: string
  reviewStatus: string
  reviewNote?: string
  author?: string
  version: number
  passScore: number
  estimatedMinutes: number
  expectedSql?: string
  updatedAt?: string
}

export interface ApprovalView {
  id: number
  type: string
  targetCode: string
  payloadJson?: string
  reason?: string
  status: string
  requestedBy: number
  requestedByName: string
  decidedByName?: string
  decisionNote?: string
  createdAt: string
  decidedAt?: string
}

export interface TutorMessage {
  role: 'user' | 'assistant'
  content: string
  model?: string
  createdAt?: string
}

export interface TutorChat {
  reply: string
  model: string
  fallback: boolean
  history: TutorMessage[]
}

export interface LabAnalytics {
  labCode: string
  title: string
  level: string
  attempts: number
  learners: number
  passedLearners: number
  averageScore: number
  topFailure?: string
}

export interface AdminOverview {
  paths: number
  labs: number
  publishedLabs: number
  submissions: number
  gradedSubmissions: number
  activeSandboxes: number
  learners: number
  pendingApprovals: number
  activeLearners7d: number
  passRate: number
}

export const listProjects = () => http.get<ProjectView[]>('/api/learning/projects').then((res) => res.data)

export const getProject = (code: string) =>
  http.get<ProjectDetail>(`/api/learning/projects/${code}`).then((res) => res.data)

export const chatWithTutor = (code: string, message: string) =>
  http.post<TutorChat>(`/api/learning/labs/${code}/tutor`, { message }).then((res) => res.data)

export const getTutorHistory = (code: string) =>
  http.get<TutorMessage[]>(`/api/learning/labs/${code}/tutor/messages`).then((res) => res.data)

export const listAdminLabs = () => http.get<LabAdminView[]>('/api/learning/admin/labs').then((res) => res.data)

export const createLab = (payload: Record<string, unknown>) =>
  http.post<LabAdminView>('/api/learning/admin/labs', payload).then((res) => res.data)

export const updateLab = (code: string, payload: Record<string, unknown>) =>
  http.put<LabAdminView>(`/api/learning/admin/labs/${code}`, payload).then((res) => res.data)

export const reviewLab = (code: string, approve: boolean, note?: string) =>
  http.post<LabAdminView>(`/api/learning/admin/labs/${code}/review`, { approve, note }).then((res) => res.data)

export const publishLab = (code: string) =>
  http.post<LabAdminView>(`/api/learning/admin/labs/${code}/publish`).then((res) => res.data)

export const unpublishLab = (code: string) =>
  http.post<{ code: string; status: string }>(`/api/learning/admin/labs/${code}/unpublish`).then((res) => res.data)

export const listApprovals = (status?: string) =>
  http.get<ApprovalView[]>('/api/learning/admin/approvals', { params: status ? { status } : undefined })
    .then((res) => res.data)

export const decideApproval = (id: number, approve: boolean, note?: string) =>
  http.post<{ id: number; status: string; labPublished: boolean; message: string }>(
    `/api/learning/admin/approvals/${id}/decision`, { approve, note }
  ).then((res) => res.data)

export const getAdminAnalytics = () =>
  http.get<{ overview: AdminOverview; labs: LabAnalytics[]; summary: Record<string, unknown> }>(
    '/api/learning/admin/analytics'
  ).then((res) => res.data)
