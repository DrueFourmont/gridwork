import { ConflictError } from '../api/client'
import type { CellWrite } from '../api/cells'
import type { BatchUpdateResponse, CellConflict } from '../api/types'

export interface CellRef {
  rowId: string
  columnId: string
}

/** A cell being rolled back, carrying the value to restore. */
export interface RestoredCell extends CellRef {
  value: string | null
}

export interface EnqueuedEdit extends CellRef {
  value: string | null
  /** The version the caller believes this cell is at, usually from the query cache. */
  knownVersion: number
  /** The value already in the cell, if known. Used to drop no-op edits. */
  previous?: string | null
}

export interface QueueDeps {
  send: (updates: CellWrite[]) => Promise<BatchUpdateResponse>
  /** Paint the new value now, before the server has seen it. */
  onOptimistic: (rowId: string, columnId: string, value: string | null) => void
  /** The server accepted these, with authoritative versions. */
  onApplied: (updated: BatchUpdateResponse['updated']) => void
  /** Put these cells back, to the value the server last confirmed. */
  onRollback: (cells: RestoredCell[]) => void
  onConflict: (conflicts: CellConflict[]) => void
  onError: (error: unknown, attempted: CellRef[]) => void
  debounceMs?: number
}

export function cellKey(rowId: string, columnId: string): string {
  return `${rowId}::${columnId}`
}

/**
 * Turns keystrokes into batched, versioned writes.
 *
 * Three jobs, in order of how much trouble each saves:
 *
 * 1. **Coalesce.** Typing "hello" is five edits and one request. Without this,
 *    every keystroke is a round trip and a version bump, and two people typing
 *    in nearby cells would conflict constantly over nothing.
 *
 * 2. **Serialise.** Only one batch is ever in flight. A second edit to a cell
 *    whose first write has not returned waits for it. That is what stops a
 *    write racing itself: the version it expects is always one the server has
 *    already confirmed, never a guess about what is about to happen.
 *
 * 3. **Remember versions.** After a write succeeds, the queue knows the new
 *    version before the query cache does. After a conflict, it adopts the
 *    server's actual version, so the retry can succeed instead of failing the
 *    same way forever.
 *
 * Deliberately free of React and of fetch, so all of the above is testable
 * without a DOM, a server, or a render.
 */
export function createWriteQueue(deps: QueueDeps) {
  const debounceMs = deps.debounceMs ?? 250

  /** Cell key to the edit waiting to be sent. A later edit to the same cell replaces the earlier one. */
  const pending = new Map<string, EnqueuedEdit>()
  /** The best version this queue knows for a cell, which can be ahead of the query cache. */
  const versions = new Map<string, number>()
  /**
   * The last value the server confirmed for a cell. Lives here rather than in
   * the component, because the queue is what performs rollbacks and it should
   * not have to ask anyone else what to roll back to.
   */
  const confirmed = new Map<string, string | null>()

  let timer: ReturnType<typeof setTimeout> | null = null
  let inFlight = false

  function enqueue(edit: EnqueuedEdit): void {
    // Tabbing through a cell without changing it must not burn a version.
    // If it did, simply reading a sheet would make everyone else conflict.
    if (edit.previous !== undefined && edit.previous === edit.value) return

    const key = cellKey(edit.rowId, edit.columnId)
    if (!versions.has(key)) versions.set(key, edit.knownVersion)
    if (!confirmed.has(key) && edit.previous !== undefined) confirmed.set(key, edit.previous)

    pending.set(key, edit)
    deps.onOptimistic(edit.rowId, edit.columnId, edit.value)
    schedule()
  }

  function schedule(): void {
    if (timer !== null) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      void flush()
    }, debounceMs)
  }

  async function flush(): Promise<void> {
    if (inFlight || pending.size === 0) return

    const batch = [...pending.values()]
    pending.clear()
    inFlight = true

    const updates: CellWrite[] = batch.map((edit) => ({
      rowId: edit.rowId,
      columnId: edit.columnId,
      value: edit.value,
      // The queue's own version wins over the caller's, because the queue has
      // seen responses the caller's cache may not have processed yet.
      expectedVersion: versions.get(cellKey(edit.rowId, edit.columnId)) ?? edit.knownVersion,
    }))

    try {
      const response = await deps.send(updates)
      for (const cell of response.updated) {
        const key = cellKey(cell.rowId, cell.columnId)
        versions.set(key, cell.version)
        confirmed.set(key, cell.value)
      }
      deps.onApplied(response.updated)
    } catch (error) {
      const attempted: RestoredCell[] = batch.map(({ rowId, columnId }) => ({
        rowId,
        columnId,
        value: confirmed.get(cellKey(rowId, columnId)) ?? null,
      }))
      // Roll back on every failure, not just conflicts. A grid still showing a
      // value that was never saved is worse than an error message, because it
      // looks like it worked.
      deps.onRollback(attempted)

      if (error instanceof ConflictError) {
        for (const conflict of error.conflicts) {
          versions.set(cellKey(conflict.rowId, conflict.columnId), conflict.actualVersion)
        }
        deps.onConflict(error.conflicts)
      } else {
        deps.onError(error, attempted)
      }
    } finally {
      inFlight = false
      // Anything that arrived while this batch was in the air goes now, with
      // versions that are no longer guesses.
      if (pending.size > 0) schedule()
    }
  }

  return {
    enqueue,
    /** Send immediately rather than waiting out the debounce. Used on blur and before navigating away. */
    flushNow: () => {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      return flush()
    },
    /** Tell the queue state it could not have learned itself, for example after a refetch. */
    observe: (rowId: string, columnId: string, version: number, value: string | null) => {
      const key = cellKey(rowId, columnId)
      versions.set(key, version)
      confirmed.set(key, value)
    },
    hasPending: () => pending.size > 0 || inFlight,
  }
}

export type WriteQueue = ReturnType<typeof createWriteQueue>
