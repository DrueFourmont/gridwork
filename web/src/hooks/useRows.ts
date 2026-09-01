import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { listRows } from '../api/rows'
import { getSheet } from '../api/sheets'
import type { Row } from '../api/types'

export const rowsKey = (sheetId: string) => ['rows', sheetId] as const
export const sheetKey = (sheetId: string) => ['sheet', sheetId] as const

export function useSheet(sheetId: string | null) {
  return useQuery({
    queryKey: sheetKey(sheetId ?? ''),
    queryFn: () => getSheet(sheetId ?? ''),
    enabled: sheetId !== null,
  })
}

/**
 * Every row of a sheet, fetched a page at a time and held as one list.
 *
 * The cursor from the API maps straight onto getNextPageParam. Pages are
 * fetched eagerly to the end rather than as the user scrolls: 2,000 rows is
 * four requests, and fetching mid-scroll is exactly how a grid drops frames at
 * the moment someone is looking at it. The DOM is virtualised; the data is not.
 */
export function useRows(sheetId: string | null) {
  const query = useInfiniteQuery({
    queryKey: rowsKey(sheetId ?? ''),
    enabled: sheetId !== null,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => listRows(sheetId ?? '', pageParam),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  })

  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query
  if (hasNextPage && !isFetchingNextPage) {
    void fetchNextPage()
  }

  const rows: Row[] = query.data?.pages.flatMap((page) => page.items) ?? []
  return { ...query, rows, isComplete: !query.hasNextPage && !query.isFetching }
}
