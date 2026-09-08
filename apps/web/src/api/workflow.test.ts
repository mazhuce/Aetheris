import type { AxiosResponse } from 'axios'
import type { Mock } from 'vitest'
import http from './http'
import { listExecutionAttempts, type ExecutionAttempt } from './workflow'

vi.mock('./http', () => ({
  default: { get: vi.fn() }
}))

const mockedHttp = http as unknown as { get: Mock }

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
})
