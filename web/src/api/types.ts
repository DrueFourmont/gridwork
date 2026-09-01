import { z } from 'zod'

/**
 * The API contract, as schemas rather than as interfaces.
 *
 * A TypeScript interface is a promise the compiler cannot keep: it describes
 * what you hope arrives, and is erased at runtime. A zod schema checks. If the
 * API changes shape, this fails loudly at the boundary with a useful message,
 * instead of surfacing as `undefined is not an object` somewhere deep in the
 * grid three renders later.
 */

export const columnTypeSchema = z.enum(['TEXT', 'NUMBER', 'DATE', 'CHECKBOX'])
export type ColumnType = z.infer<typeof columnTypeSchema>

export const sheetRoleSchema = z.enum(['OWNER', 'EDITOR', 'VIEWER'])
export type SheetRole = z.infer<typeof sheetRoleSchema>

export const loginResponseSchema = z.object({
  token: z.string(),
  tokenType: z.string(),
  expiresAt: z.string(),
})
export type LoginResponse = z.infer<typeof loginResponseSchema>

export const registerResponseSchema = z.object({
  userId: z.string(),
  email: z.string(),
  displayName: z.string(),
})

export const columnSchema = z.object({
  id: z.string(),
  name: z.string(),
  type: columnTypeSchema,
  position: z.number(),
  version: z.number(),
})
export type Column = z.infer<typeof columnSchema>

export const sheetSchema = z.object({
  id: z.string(),
  name: z.string(),
  ownerId: z.string(),
  version: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  columns: z.array(columnSchema).nullable().optional(),
})
export type Sheet = z.infer<typeof sheetSchema>

export const cellSchema = z.object({
  columnId: z.string(),
  value: z.string().nullable(),
  version: z.number(),
})
export type Cell = z.infer<typeof cellSchema>

export const rowSchema = z.object({
  id: z.string(),
  position: z.number(),
  version: z.number(),
  cells: z.array(cellSchema),
})
export type Row = z.infer<typeof rowSchema>

export const pageSchema = <T extends z.ZodTypeAny>(item: T) =>
  z.object({
    items: z.array(item),
    nextCursor: z.string().nullable().optional(),
  })

export const batchUpdateResponseSchema = z.object({
  updated: z.array(
    z.object({
      rowId: z.string(),
      columnId: z.string(),
      value: z.string().nullable(),
      version: z.number(),
    }),
  ),
})
export type BatchUpdateResponse = z.infer<typeof batchUpdateResponseSchema>

/**
 * RFC 7807. Every error the API returns has this shape, including the 401 and
 * 403 that come out of the security filter chain, so the client only ever has
 * one error parser.
 */
export const problemSchema = z.object({
  type: z.string(),
  title: z.string(),
  status: z.number(),
  detail: z.string(),
  instance: z.string(),
  requestId: z.string().nullable().optional(),
  timestamp: z.string(),
  errors: z
    .array(z.object({ field: z.string(), message: z.string() }))
    .nullable()
    .optional(),
  conflicts: z
    .array(
      z.object({
        rowId: z.string(),
        columnId: z.string(),
        expectedVersion: z.number(),
        actualVersion: z.number(),
        actualValue: z.string().nullable(),
      }),
    )
    .nullable()
    .optional(),
})
export type Problem = z.infer<typeof problemSchema>
export type CellConflict = NonNullable<Problem['conflicts']>[number]
