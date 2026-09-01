import { z } from 'zod'
import { problemSchema, type CellConflict, type Problem } from './types'

/**
 * A failed request, carrying the parsed problem+json body.
 *
 * `requestId` is kept deliberately: it is the string a user quotes when
 * something breaks, and it ties this failure to the exact server log lines.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  get requestId(): string | null {
    return this.problem?.requestId ?? null
  }

  get fieldErrors(): { field: string; message: string }[] {
    return this.problem?.errors ?? []
  }
}

/** A 409 from cells:batchUpdate. Separate type so the UI can branch on it without sniffing status codes. */
export class ConflictError extends ApiError {
  constructor(
    status: number,
    problem: Problem | null,
    readonly conflicts: CellConflict[],
  ) {
    super(status, problem, problem?.detail ?? 'Conflict')
    this.name = 'ConflictError'
  }
}

/** Set by the auth store. A function rather than a value so the client always reads the current token. */
let tokenReader: () => string | null = () => null

export function setTokenReader(reader: () => string | null): void {
  tokenReader = reader
}

/** Called when any request comes back 401, so the app can drop a dead session. */
let onUnauthorized: () => void = () => {}

export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler
}

interface RequestOptions {
  method?: string
  body?: unknown
  idempotencyKey?: string
  signal?: AbortSignal
}

export async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  options: RequestOptions = {},
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }

  const token = tokenReader()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey

  // Built up conditionally rather than passing undefined: with
  // exactOptionalPropertyTypes on, an explicit undefined is not the same as an
  // absent property, and RequestInit does not accept one.
  const init: RequestInit = { method: options.method ?? 'GET', headers }
  if (options.body !== undefined) init.body = JSON.stringify(options.body)
  if (options.signal) init.signal = options.signal

  const response = await fetch(`/api${path}`, init)

  if (!response.ok) throw await toError(response)

  if (response.status === 204) return schema.parse(undefined)
  return schema.parse(await response.json())
}

async function toError(response: Response): Promise<ApiError> {
  const problem = await readProblem(response)

  if (response.status === 401) {
    // The token is gone or expired. Tell the app once, here, rather than
    // making every call site remember to check.
    onUnauthorized()
  }

  const conflicts = problem?.conflicts
  if (response.status === 409 && conflicts && conflicts.length > 0) {
    return new ConflictError(response.status, problem, conflicts)
  }

  return new ApiError(
    response.status,
    problem,
    problem?.detail ?? `Request failed with ${String(response.status)}`,
  )
}

async function readProblem(response: Response): Promise<Problem | null> {
  try {
    const parsed = problemSchema.safeParse(await response.json())
    return parsed.success ? parsed.data : null
  } catch {
    // A body that is not JSON at all. Should not happen given the API always
    // returns problem+json, but a client that throws while handling an error
    // is a client that hides the original failure.
    return null
  }
}
