import { useEffect, useState } from 'react'
import { useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { createLiveConnection, type LiveStatus } from '../realtime/liveConnection'
import { useAuthStore } from '../state/authStore'
import { rowsKey } from './useRows'
import type { Row } from '../api/types'

type RowPage = { items: Row[]; nextCursor?: string | null }
type RowsCache = InfiniteData<RowPage, string | null>

function websocketUrl(): string {
  // Same host and port as the page, so the Vite proxy in development and the
  // ingress in production both carry it without extra configuration.
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${window.location.host}/ws/sheet`
}

/**
 * Keeps the grid in step with other people's edits.
 *
 * Writes still go over REST, so they get the same validation, versioning, and
 * idempotency as any other write. The socket is one way: it only carries
 * changes down. That keeps one write path rather than two that have to agree.
 *
 * A remote change is written straight into the query cache, exactly as an
 * optimistic local edit is, so the grid has one notion of what a cell holds.
 */
export function useLiveSheet(sheetId: string) {
  const queryClient = useQueryClient()
  const token = useAuthStore((state) => state.token)
  const [status, setStatus] = useState<LiveStatus>('connecting')

  useEffect(() => {
    if (token === null) return

    const live = createLiveConnection({
      open: (url) => new WebSocket(url),
      // setStatus from useState has a stable identity, so it can be closed
      // over directly. A ref would be ceremony with no benefit.
      onStatus: setStatus,
      onResync: () => {
        // The gap was too large to replay. Refetching is the honest recovery,
        // and it is cheap because the rows query already knows how to page.
        void queryClient.invalidateQueries({ queryKey: rowsKey(sheetId) })
      },
      onCellChanged: (change) => {
        // Apply only if this carries a version the grid has not already got.
        //
        // This is what suppresses the echo of your own write: by the time it
        // comes back the cache already holds that version, so it is a no op.
        // Filtering on "did this user make the change" looks equivalent and is
        // not: two tabs signed in as the same person would then ignore each
        // other, which is wrong and is exactly what the two browser test
        // caught.
        queryClient.setQueryData<RowsCache>(rowsKey(sheetId), (cache) => {
          if (!cache) return cache
          return {
            ...cache,
            pages: cache.pages.map((page) => ({
              ...page,
              items: page.items.map((row) => {
                if (row.id !== change.rowId) return row
                return {
                  ...row,
                  cells: row.cells.map((cell) => {
                    if (cell.columnId !== change.columnId) return cell
                    if (cell.version >= change.version) return cell
                    return { ...cell, value: change.value, version: change.version }
                  }),
                }
              }),
            })),
          }
        })
      },
    })

    live.connect(websocketUrl(), token, sheetId)
    return () => {
      live.close()
    }
  }, [sheetId, token, queryClient])

  return { status }
}
