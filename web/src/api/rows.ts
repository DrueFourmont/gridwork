import { request } from './client'
import { pageSchema, rowSchema, type Row } from './types'

/** The API caps a page at 500. Asking for the maximum means 2,000 rows is four requests, not forty. */
export const ROW_PAGE_SIZE = 500

export function listRows(sheetId: string, cursor?: string | null) {
  const query = cursor
    ? `?limit=${String(ROW_PAGE_SIZE)}&cursor=${encodeURIComponent(cursor)}`
    : `?limit=${String(ROW_PAGE_SIZE)}`
  return request(`/v1/sheets/${sheetId}/rows${query}`, pageSchema(rowSchema))
}

export function appendRow(sheetId: string, idempotencyKey: string): Promise<Row> {
  return request(`/v1/sheets/${sheetId}/rows`, rowSchema, {
    method: 'POST',
    idempotencyKey,
  })
}
