import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addColumn, createSheet, listSheets } from '../api/sheets'
import { appendRow } from '../api/rows'
import type { Column } from '../api/types'
import { rowsKey, sheetKey } from './useRows'

export const sheetsKey = ['sheets'] as const

export function useSheetList() {
  return useQuery({ queryKey: sheetsKey, queryFn: () => listSheets() })
}

/**
 * Every mutation here sends a fresh idempotency key.
 *
 * The key is generated once per attempt, so a retry of the same attempt is
 * safe, which is the case a dropped connection creates. A genuinely new
 * "create another sheet" click gets a new key and correctly creates another.
 */
export function useCreateSheet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => createSheet(name, crypto.randomUUID()),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sheetsKey }),
  })
}

export function useAddColumn(sheetId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; type: Column['type'] }) =>
      addColumn(sheetId, input.name, input.type, crypto.randomUUID()),
    onSuccess: async () => {
      // A new column gives every existing row a new empty cell, so the rows
      // are stale too, not just the sheet.
      await queryClient.invalidateQueries({ queryKey: sheetKey(sheetId) })
      await queryClient.invalidateQueries({ queryKey: rowsKey(sheetId) })
    },
  })
}

export function useAppendRow(sheetId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => appendRow(sheetId, crypto.randomUUID()),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: rowsKey(sheetId) }),
  })
}
