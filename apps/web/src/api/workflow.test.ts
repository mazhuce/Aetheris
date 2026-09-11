import type { AxiosResponse } from 'axios'
import type { Mock } from 'vitest'
import http from './http'
import { changeSparkRunnerStatus, listExecutionAttempts, registerSparkRunner, type ExecutionAttempt, type SparkRunner } from './workflow'

vi.mock('./http', () => ({
  default: { get: vi.fn(), post: vi.fn() }
}))

const mockedHttp = http as unknown as { get: Mock; post: Mock }

describe('workflow api', () => {
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
})
