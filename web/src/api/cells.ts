import { request } from './client'
import { batchUpdateResponseSchema, type BatchUpdateResponse } from './types'

export interface CellWrite {
  rowId: string
  columnId: string
  value: string | null
  expectedVersion: number
}

/**
 * One request, many cells, all or nothing.
 *
 * Deliberately batched rather than one request per cell: it is the endpoint the
 * API actually exposes, it is what the p95 budget in CLAUDE.md measures, and a
 * paste over a range in a later phase is a batch by nature.
 */
export function batchUpdate(sheetId: string, updates: CellWrite[]): Promise<BatchUpdateResponse> {
  return request(`/v1/sheets/${sheetId}/cells:batchUpdate`, batchUpdateResponseSchema, {
    method: 'PATCH',
    body: { updates },
  })
}
