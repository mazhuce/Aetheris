import type { AxiosResponse } from 'axios'
import type { Mock } from 'vitest'
import http from './http'
import {
  changeGitRepositoryStatus, changeGitRepositoryTrust, changeSparkRunnerStatus, getFlinkHealth, listExecutionAttempts, listGitRevisions, probeFlinkHealth,
  createSparkJob, disableSparkJob, listSparkArtifactVerifications, publishSparkJob, registerGitRepository, registerSparkArtifact, registerSparkRunner,
  synchronizeGitRepository, verifySparkArtifact,
  pauseFlink, resumeFlink, upgradeFlink,
  type ExecutionAttempt, type FlinkGatewayHealth, type GitRepository, type ManagedArtifact, type SparkRunner
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

  it('loads Flink SQL Gateway capability health separately from Spark Runner health', async () => {
    const health = { status: 'UP', version: '1.20.1', endpoint: 'http://flink:8083' } satisfies FlinkGatewayHealth
    mockedHttp.get.mockResolvedValueOnce({ data: health } as AxiosResponse<FlinkGatewayHealth>)
    await expect(getFlinkHealth()).resolves.toBe(health)
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/workflow/spark/flink/health')
  })

  it('probes the Gateway endpoint supplied by a Flink task form', async () => {
    const health = { status: 'UP', version: '1.20.1', endpoint: 'http://flink-task:8083' } satisfies FlinkGatewayHealth
    mockedHttp.get.mockResolvedValueOnce({ data: health } as AxiosResponse<FlinkGatewayHealth>)
    await expect(probeFlinkHealth('http://flink-task:8083')).resolves.toBe(health)
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/workflow/spark/flink/health', { params: { endpoint: 'http://flink-task:8083' } })
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

  it('submits Spark JAR job definitions with their main class and lifecycle actions', async () => {
    const job = { id: 9, status: 'draft' } as any
    mockedHttp.post.mockResolvedValue({ data: job } as AxiosResponse<typeof job>)
    const input = {
      name: 'Orders', environmentId: 1, artifactId: 7, inputTables: ['hive_prod.dwd.orders'],
      outputUri: 'hdfs://localhost:9000/user/aetheris/managed/orders', mainClass: 'com.acme.OrdersJob', runnerId: 3,
      parameterSchema: { properties: { bizDate: { type: 'date', required: true } } }, defaultParameters: {},
      driverCores: 1, driverMemoryMb: 768, timeoutSeconds: 1800, maxRetries: 0
    }
    await expect(createSparkJob(input)).resolves.toBe(job)
    await expect(publishSparkJob(9)).resolves.toBe(job)
    await expect(disableSparkJob(9)).resolves.toBe(job)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(1, '/api/workflow/spark/jobs', input)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(2, '/api/workflow/spark/jobs/9/publish')
    expect(mockedHttp.post).toHaveBeenNthCalledWith(3, '/api/workflow/spark/jobs/9/disable')
  })

  it('uses explicit Flink pause and savepoint-resume lifecycle endpoints', async () => {
    const paused = { jobId: 'job-1', requestId: 'req-1', intent: 'pause' } as any
    const resumed = {
      attemptId: 11, sessionHandle: 'session-2', operationHandle: 'op-2',
      savepoint: 's3://warehouse/savepoints/sp-1', state: 'running' as const
    }
    mockedHttp.post
      .mockResolvedValueOnce({ data: paused } as AxiosResponse<typeof paused>)
      .mockResolvedValueOnce({ data: resumed } as AxiosResponse<typeof resumed>)
    await expect(pauseFlink(7, 11, 's3://warehouse/savepoints')).resolves.toBe(paused)
    await expect(resumeFlink(7, 11)).resolves.toBe(resumed)
    expect(mockedHttp.post).toHaveBeenNthCalledWith(
      1, '/api/workflow/tasks/instances/7/attempts/11/flink-pause',
      { targetDirectory: 's3://warehouse/savepoints' },
    )
    expect(mockedHttp.post).toHaveBeenNthCalledWith(
      2, '/api/workflow/tasks/instances/7/attempts/11/flink-resume',
    )
  })

  it('submits a versioned Flink upgrade contract', async () => {
    const result = { jobId: 'job-1', requestId: 'req-2', intent: 'upgrade', definitionDigest: 'a'.repeat(64) } as any
    const payload = {
      targetDirectory: 's3://warehouse/savepoints',
      replacementSql: 'INSERT INTO bronze.orders SELECT * FROM source_orders'
    }
    mockedHttp.post.mockResolvedValueOnce({ data: result } as AxiosResponse<typeof result>)
    await expect(upgradeFlink(7, 11, payload)).resolves.toBe(result)
    expect(mockedHttp.post).toHaveBeenCalledWith(
      '/api/workflow/tasks/instances/7/attempts/11/flink-upgrade', payload,
    )
  })
})
