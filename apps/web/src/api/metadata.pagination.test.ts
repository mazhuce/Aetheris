import { describe, expect, it, vi } from 'vitest'
import { collectVisiblePages, clearQueryHistory, VisibleListCollectionError } from './metadata'
import http from './http'

describe('legacy complete-array compatibility', () => {
  it('continues after an empty nonterminal page and returns all later rows', async () => {
    const load = vi.fn().mockResolvedValueOnce({ items: [{ id: 1 }], nextCursor: 'a', hasMore: true })
      .mockResolvedValueOnce({ items: [], nextCursor: 'b', hasMore: true })
      .mockResolvedValueOnce({ items: [{ id: 1001 }], nextCursor: null, hasMore: false })
    expect(await collectVisiblePages(load)).toEqual([{ id: 1 }, { id: 1001 }])
    expect(load.mock.calls.map((call) => call[0])).toEqual([undefined, 'a', 'b'])
  })
  it('rejects over-limit, repeated cursors and dependency failure without partial success', async () => {
    await expect(collectVisiblePages(vi.fn().mockResolvedValue({ items: [1, 2], nextCursor: null, hasMore: false }), { maxRows: 1 })).rejects.toBeInstanceOf(VisibleListCollectionError)
    await expect(collectVisiblePages(vi.fn().mockResolvedValue({ items: [], nextCursor: 'same', hasMore: true }))).rejects.toThrow('游标')
    const load = vi.fn().mockResolvedValueOnce({ items: [1], nextCursor: 'a', hasMore: true }).mockRejectedValueOnce(new Error('503'))
    await expect(collectVisiblePages(load)).rejects.toThrow('503')
  })
  it('honors cancellation before invoking another request', async () => {
    const controller = new AbortController()
    controller.abort()
    const load = vi.fn()
    await expect(collectVisiblePages(load, { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
    expect(load).not.toHaveBeenCalled()
  })
})

describe('bounded history clear response contract', () => {
  it('requires an explicit boolean before reporting clearing completion', async () => {
    const remove = vi.spyOn(http, 'delete')
    for (const data of [null, 1, [], { deleted: 5 }, { deleted: 5, hasMore: 'false' },
      { deleted: -1, hasMore: false }, { deleted: 1.5, hasMore: false }, { deleted: 501, hasMore: false }]) {
      remove.mockResolvedValueOnce({ data })
      await expect(clearQueryHistory()).rejects.toThrow('无法确认清理完成')
    }
    remove.mockResolvedValueOnce({ data: { deleted: 500, hasMore: true } })
    expect(await clearQueryHistory()).toEqual({ deleted: 500, hasMore: true })
    remove.mockResolvedValueOnce({ data: { deleted: 0, hasMore: false } })
    expect(await clearQueryHistory()).toEqual({ deleted: 0, hasMore: false })
  })
})
