import type { AxiosResponse } from 'axios'
import type { Mock } from 'vitest'
import http from './http'
import {
  changeGitRepositoryStatus, changeGitRepositoryTrust, changeSparkRunnerStatus, listExecutionAttempts, listGitRevisions,
  listSparkArtifactVerifications, registerGitRepository, registerSparkArtifact, registerSparkRunner,
  synchronizeGitRepository, verifySparkArtifact,
  type ExecutionAttempt, type GitRepository, type ManagedArtifact, type SparkRunner
} from './workflow'

vi.mock('./http', () => ({
  default: { get: vi.fn(), post: vi.fn() }
}))

const mockedHttp = http as unknown as { get: Mock; post: Mock }

describe('workflow api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads immutable execution attempts for one logical instance', async () => {
    const attempts = [{
      id: 12,
      instanceId: 7,
      attemptNo: 2,
      status: 'succeeded',
      fencingToken: 2,
      workerId: 'worker-1',
      triggerType: 'retry',
      taskName: '订单加工',
      datasourceId: 1,
      definitionDigest: 'a'.repeat(64),
      parametersJson: '{"bizDate":"2026-09-08"}',
      parameterDigest: 'b'.repeat(64)
    }] satisfies ExecutionAttempt[]
    mockedHttp.get.mockResolvedValueOnce({ data: attempts } as AxiosResponse<ExecutionAttempt[]>)

    await expect(listExecutionAttempts(7)).resolves.toEqual(attempts)
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/workflow/tasks/instances/7/attempts')
  })

  it('uses manager-only runner registration and lifecycle endpoints', async () => {
    const runner = { id: 3, status: 'active' } as SparkRunner
    mockedHttp.post.mockResolvedValue({ data: runner } as AxiosResponse<SparkRunner>)
    const input = {
      environmentId: 1, runnerKey: 'runner-02', name: 'Runner 02',
      baseUrl: 'http://runner-02:18081', maxCores: 4, maxMemoryMb: 4096, maxConcurrency: 2
    }

    await expect(registerSparkRunner(input)).resolves.toBe(runner)
    await expect(changeSparkRunnerStatus(3, 'draining')).resolves.toBe(runner)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(1, '/api/workflow/spark/runners', input)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(2, '/api/workflow/spark/runners/3/status', { status: 'draining' })
  })

  it('uses repository lifecycle and immutable source binding endpoints', async () => {
    const repository = { id: 4, status: 'active' } as GitRepository
    const artifact = { id: 7, status: 'draft' } as ManagedArtifact
    mockedHttp.post
      .mockResolvedValueOnce({ data: repository } as AxiosResponse<GitRepository>)
      .mockResolvedValueOnce({ data: repository } as AxiosResponse<GitRepository>)
      .mockResolvedValueOnce({ data: repository } as AxiosResponse<GitRepository>)
      .mockResolvedValueOnce({ data: repository } as AxiosResponse<GitRepository>)
      .mockResolvedValueOnce({ data: artifact } as AxiosResponse<ManagedArtifact>)
      .mockResolvedValueOnce({ data: artifact } as AxiosResponse<ManagedArtifact>)
    mockedHttp.get
      .mockResolvedValueOnce({ data: [] } as AxiosResponse<[]>)
      .mockResolvedValueOnce({ data: [] } as AxiosResponse<[]>)
    const repositoryInput = {
      repositoryKey: 'jobs', name: 'Jobs', remoteUrl: 'https://github.com/acme/jobs.git', defaultBranch: 'main',
      signaturePolicy: 'optional' as const, trustedSignerFingerprints: []
    }
    const artifactInput = {
      name: 'Daily', version: '1.0.0', artifactKey: 'daily.py', sha256: 'a'.repeat(64),
      repositoryId: 4, commitSha: 'b'.repeat(40), sourcePath: 'spark/daily.py'
    }

    await registerGitRepository(repositoryInput)
    await synchronizeGitRepository(4)
    await changeGitRepositoryStatus(4, 'disabled')
    await changeGitRepositoryTrust(4, { signaturePolicy: 'optional', trustedSignerFingerprints: [] })
    await listGitRevisions(4)
    await registerSparkArtifact(artifactInput)
    await verifySparkArtifact(7)
    await listSparkArtifactVerifications(7)

    expect(mockedHttp.post).toHaveBeenNthCalledWith(1, '/api/workflow/spark/repositories', repositoryInput)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(2, '/api/workflow/spark/repositories/4/sync')
    expect(mockedHttp.post).toHaveBeenNthCalledWith(3, '/api/workflow/spark/repositories/4/status', { status: 'disabled' })
    expect(mockedHttp.post).toHaveBeenNthCalledWith(4, '/api/workflow/spark/repositories/4/trust', { signaturePolicy: 'optional', trustedSignerFingerprints: [] })
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/workflow/spark/repositories/4/revisions')
    expect(mockedHttp.post).toHaveBeenNthCalledWith(5, '/api/workflow/spark/artifacts', artifactInput)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(6, '/api/workflow/spark/artifacts/7/verify')
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/workflow/spark/artifacts/7/verifications')
  })
})
