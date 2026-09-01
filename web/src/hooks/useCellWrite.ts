import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { batchUpdate } from '../api/cells'
import { createWriteQueue, type CellRef, type WriteQueue } from '../grid/writeQueue'
import type { CellConflict, Row } from '../api/types'
import { rowsKey } from './useRows'

type RowPage = { items: Row[]; nextCursor?: string | null }
type RowsCache = InfiniteData<RowPage, string | null>

/**
 * Wires the write queue to the React Query cache.
 *
 * The cache is the single source of truth for what the grid shows. An
 * optimistic edit writes into it, a rollback writes the old value back, and a
 * success writes the authoritative version. Keeping a second copy of cell
 * values in component state would mean two things to keep in step, and they
 * would drift the first time a refetch landed mid edit.
 */
export function useCellWrite(sheetId: string) {
  const queryClient = useQueryClient()
  const [conflicts, setConflicts] = useState<CellConflict[]>([])
  const [error, setError] = useState<string | null>(null)
  const [pendingCount, setPendingCount] = useState(0)

  const editCache = useCallback(
    (mutate: (row: Row) => Row, matches: (row: Row) => boolean) => {
      queryClient.setQueryData<RowsCache>(rowsKey(sheetId), (cache) => {
        if (!cache) return cache
        return {
          ...cache,
          pages: cache.pages.map((page) => ({
            ...page,
            items: page.items.map((row) => (matches(row) ? mutate(row) : row)),
          })),
        }
      })
    },
    [queryClient, sheetId],
  )

  const setCellValue = useCallback(
    (rowId: string, columnId: string, value: string | null, version?: number) => {
      editCache(
        (row) => ({
          ...row,
          cells: row.cells.map((cell) =>
            cell.columnId === columnId
              ? { ...cell, value, version: version ?? cell.version }
              : cell,
          ),
        }),
        (row) => row.id === rowId,
      )
    },
    [editCache],
  )

  const queue: WriteQueue = useMemo(
    () =>
      createWriteQueue({
        send: (updates) => batchUpdate(sheetId, updates),
        onOptimistic: (rowId, columnId, value) => {
          setCellValue(rowId, columnId, value)
          setPendingCount((count) => count + 1)
        },
        onApplied: (updated) => {
          for (const cell of updated) {
            setCellValue(cell.rowId, cell.columnId, cell.value, cell.version)
          }
          setPendingCount((count) => Math.max(0, count - updated.length))
          setError(null)
        },
        onRollback: (cells) => {
          // The queue tells us what to restore. Keeping our own copy of the
          // last confirmed value would be a second source of truth that drifts
          // the first time a refetch lands mid edit.
          for (const cell of cells) {
            setCellValue(cell.rowId, cell.columnId, cell.value)
          }
          setPendingCount((count) => Math.max(0, count - cells.length))
        },
        onConflict: (found) => {
          // The 409 carries the other person's value, so the merge prompt can
          // be shown without going back to the server for it.
          for (const conflict of found) {
            setCellValue(
              conflict.rowId,
              conflict.columnId,
              conflict.actualValue,
              conflict.actualVersion,
            )
          }
          setConflicts(found)
        },
        onError: (thrown) => {
          setError(thrown instanceof Error ? thrown.message : 'The change could not be saved.')
        },
      }),
    [sheetId, setCellValue],
  )

  // Anything still queued when the tab closes would be lost silently. Flushing
  // is best effort, but silently dropping an edit is not acceptable.
  useEffect(() => {
    const flush = () => {
      if (queue.hasPending()) void queue.flushNow()
    }
    window.addEventListener('beforeunload', flush)
    return () => {
      window.removeEventListener('beforeunload', flush)
    }
  }, [queue])

  const write = useCallback(
    (
      rowId: string,
      columnId: string,
      value: string | null,
      cell: { value: string | null; version: number },
    ) => {
      queue.enqueue({
        rowId,
        columnId,
        value,
        knownVersion: cell.version,
        previous: cell.value,
      })
    },
    [queue],
  )

  const resolveConflict = useCallback(
    (conflict: CellConflict, choice: 'mine' | 'theirs', myValue: string | null) => {
      setConflicts((current) =>
        current.filter((c) => !(c.rowId === conflict.rowId && c.columnId === conflict.columnId)),
      )
      if (choice === 'theirs') return
      // "Keep mine" is just the same edit again, this time expecting the
      // version the server told us about in the conflict.
      queue.enqueue({
        rowId: conflict.rowId,
        columnId: conflict.columnId,
        value: myValue,
        knownVersion: conflict.actualVersion,
      })
    },
    [queue],
  )

  const dismissError = useCallback(() => {
    setError(null)
  }, [])

  return {
    write,
    conflicts,
    resolveConflict,
    error,
    dismissError,
    pendingCount,
    flushNow: queue.flushNow,
  }
}

export type { CellRef }
