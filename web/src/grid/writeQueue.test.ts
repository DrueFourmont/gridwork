import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createWriteQueue, cellKey, type QueueDeps } from './writeQueue'
import { ConflictError } from '../api/client'
import type { BatchUpdateResponse } from '../api/types'

/**
 * The rules behind an optimistic grid edit, tested without React, a DOM, or a
 * server, for the same reason the Kotlin domain module has no Spring in it: if
 * the rule can be stated on its own, it can be tested on its own, and then the
 * component is only wiring.
 */

const ROW = 'row-1'
const COL = 'col-1'

function applied(rowId: string, columnId: string, value: string | null, version: number) {
  return { rowId, columnId, value, version }
}

type SendMock = ReturnType<typeof vi.fn<QueueDeps['send']>>

function makeDeps(send: SendMock = vi.fn<QueueDeps['send']>().mockResolvedValue({ updated: [] })) {
  const deps = {
    send,
    onOptimistic: vi.fn(),
    onApplied: vi.fn(),
    onRollback: vi.fn(),
    onConflict: vi.fn(),
    onError: vi.fn(),
  } satisfies QueueDeps
  return { deps, send }
}

describe('write queue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('shows the new value immediately, before the request is even sent', async () => {
    // The whole point of an optimistic edit. If the cell waits for the server,
    // typing feels laggy on a good connection and broken on a bad one.
    const { deps, send } = makeDeps()
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'hello', knownVersion: 1 })

    expect(deps.onOptimistic).toHaveBeenCalledWith(ROW, COL, 'hello')
    expect(send).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300)
    expect(send).toHaveBeenCalledOnce()
  })

  it('coalesces rapid edits to one cell into a single request with the last value', async () => {
    const { deps, send } = makeDeps()
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'h', knownVersion: 1 })
    queue.enqueue({ rowId: ROW, columnId: COL, value: 'he', knownVersion: 1 })
    queue.enqueue({ rowId: ROW, columnId: COL, value: 'hello', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    expect(send).toHaveBeenCalledOnce()
    expect(send).toHaveBeenCalledWith([
      { rowId: ROW, columnId: COL, value: 'hello', expectedVersion: 1 },
    ])
  })

  it('sends edits to different cells as one batch, not one request each', async () => {
    const { deps, send } = makeDeps()
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: 'col-a', value: 'a', knownVersion: 1 })
    queue.enqueue({ rowId: ROW, columnId: 'col-b', value: 'b', knownVersion: 3 })
    await vi.advanceTimersByTimeAsync(300)

    expect(send).toHaveBeenCalledOnce()
    expect(send.mock.calls[0]?.[0]).toHaveLength(2)
  })

  it('holds a second edit until the in flight request settles, so it cannot race itself', async () => {
    // The subtle bug this prevents: edit a cell, then edit it again before the
    // first response lands. The second write must expect the version the first
    // one produces. Holding the batch means the version is never a guess.
    let resolveFirst: (value: BatchUpdateResponse) => void = () => {}
    const send = vi
      .fn<QueueDeps['send']>()
      .mockImplementationOnce(
        () =>
          new Promise<BatchUpdateResponse>((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockResolvedValue({ updated: [] })
    const { deps } = makeDeps(send)
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'first', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)
    expect(send).toHaveBeenCalledOnce()

    // Second edit arrives while the first is still in the air.
    queue.enqueue({ rowId: ROW, columnId: COL, value: 'second', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)
    expect(send).toHaveBeenCalledOnce()

    resolveFirst({ updated: [applied(ROW, COL, 'first', 2)] })
    await vi.advanceTimersByTimeAsync(300)

    expect(send).toHaveBeenCalledTimes(2)
    // Version 2, learned from the first response, not the stale 1 the caller passed.
    expect(send.mock.calls[1]?.[0]).toEqual([
      { rowId: ROW, columnId: COL, value: 'second', expectedVersion: 2 },
    ])
  })

  it('learns the new version from a success so the next write does not conflict with itself', async () => {
    const { deps, send } = makeDeps(
      vi
        .fn<QueueDeps['send']>()
        .mockResolvedValueOnce({ updated: [applied(ROW, COL, 'one', 2)] })
        .mockResolvedValue({ updated: [] }),
    )
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'one', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)
    expect(deps.onApplied).toHaveBeenCalledWith([applied(ROW, COL, 'one', 2)])

    // The caller still believes version 1. The queue knows better.
    queue.enqueue({ rowId: ROW, columnId: COL, value: 'two', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    expect(send.mock.calls[1]?.[0]).toEqual([
      { rowId: ROW, columnId: COL, value: 'two', expectedVersion: 2 },
    ])
  })

  it('rolls the cell back and reports the conflict when someone else got there first', async () => {
    const conflicts = [
      { rowId: ROW, columnId: COL, expectedVersion: 1, actualVersion: 2, actualValue: 'theirs' },
    ]
    const { deps } = makeDeps(
      vi.fn<QueueDeps['send']>().mockRejectedValue(new ConflictError(409, null, conflicts)),
    )
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'mine', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    // The rollback carries the value to restore, so the caller does not have
    // to keep a parallel copy of what the server last confirmed.
    expect(deps.onRollback).toHaveBeenCalledWith([{ rowId: ROW, columnId: COL, value: null }])
    expect(deps.onConflict).toHaveBeenCalledWith(conflicts)
  })

  it('adopts the actual version from a conflict so a retry can succeed', async () => {
    // Without this the user resolves the conflict, retries, and conflicts again
    // forever, because the client is still holding the version it started with.
    const { deps, send } = makeDeps(
      vi
        .fn<QueueDeps['send']>()
        .mockRejectedValueOnce(
          new ConflictError(409, null, [
            { rowId: ROW, columnId: COL, expectedVersion: 1, actualVersion: 7, actualValue: 'x' },
          ]),
        )
        .mockResolvedValue({ updated: [] }),
    )
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'mine', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'mine again', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    expect(send.mock.calls[1]?.[0]).toEqual([
      { rowId: ROW, columnId: COL, value: 'mine again', expectedVersion: 7 },
    ])
  })

  it('rolls back on any failure, not just a conflict', async () => {
    // A 500 or a dropped connection must not leave the grid showing a value
    // that was never saved. That is worse than an error, because it looks fine.
    const { deps } = makeDeps(
      vi.fn<QueueDeps['send']>().mockRejectedValue(new Error('network down')),
    )
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'mine', knownVersion: 1 })
    await vi.advanceTimersByTimeAsync(300)

    expect(deps.onRollback).toHaveBeenCalledWith([{ rowId: ROW, columnId: COL, value: null }])
    expect(deps.onError).toHaveBeenCalled()
    expect(deps.onConflict).not.toHaveBeenCalled()
  })

  it('does nothing at all when a cell is set to the value it already has', async () => {
    // Clicking into a cell and tabbing out without typing must not burn a
    // version, or a user who reads a sheet makes everyone else conflict.
    const { deps, send } = makeDeps()
    const queue = createWriteQueue(deps)

    queue.enqueue({ rowId: ROW, columnId: COL, value: 'same', knownVersion: 1, previous: 'same' })
    await vi.advanceTimersByTimeAsync(300)

    expect(send).not.toHaveBeenCalled()
    expect(deps.onOptimistic).not.toHaveBeenCalled()
  })

  it('builds a stable key for a cell', () => {
    expect(cellKey(ROW, COL)).toBe(cellKey(ROW, COL))
    expect(cellKey(ROW, COL)).not.toBe(cellKey(COL, ROW))
  })
})
