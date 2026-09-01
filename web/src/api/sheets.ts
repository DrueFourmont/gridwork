import { request } from './client'
import { pageSchema, sheetSchema, columnSchema, type Column, type Sheet } from './types'

export function listSheets(cursor?: string | null) {
  const query = cursor ? `?limit=50&cursor=${encodeURIComponent(cursor)}` : '?limit=50'
  return request(`/v1/sheets${query}`, pageSchema(sheetSchema))
}

export function getSheet(sheetId: string): Promise<Sheet> {
  return request(`/v1/sheets/${sheetId}`, sheetSchema)
}

export function createSheet(name: string, idempotencyKey: string): Promise<Sheet> {
  return request('/v1/sheets', sheetSchema, {
    method: 'POST',
    body: { name },
    idempotencyKey,
  })
}

export function addColumn(
  sheetId: string,
  name: string,
  type: Column['type'],
  idempotencyKey: string,
): Promise<Column> {
  return request(`/v1/sheets/${sheetId}/columns`, columnSchema, {
    method: 'POST',
    body: { name, type },
    idempotencyKey,
  })
}
