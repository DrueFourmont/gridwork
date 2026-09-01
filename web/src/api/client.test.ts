import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { ApiError, ConflictError, request, setTokenReader, setUnauthorizedHandler } from './client'

const okSchema = z.object({ ok: z.boolean() })

function respond(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType },
  })
}

function problem(status: number, extra: Record<string, unknown> = {}) {
  return {
    type: 'https://gridwork.dfsystems.co/problems/x',
    title: 'X',
    status,
    detail: 'something went wrong',
    instance: '/api/v1/thing',
    requestId: 'req-123',
    timestamp: '2026-09-01T00:00:00Z',
    ...extra,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  setTokenReader(() => null)
  setUnauthorizedHandler(() => {})
})

describe('api client', () => {
  it('attaches the bearer token when there is one', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    setTokenReader(() => 'a-token')

    await request('/v1/thing', okSchema)

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer a-token')
  })

  it('sends no Authorization header when there is no token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await request('/v1/thing', okSchema)

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('turns a 409 with conflicts into a ConflictError the UI can branch on', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        respond(
          409,
          problem(409, {
            conflicts: [
              {
                rowId: 'r',
                columnId: 'c',
                expectedVersion: 1,
                actualVersion: 2,
                actualValue: 'theirs',
              },
            ],
          }),
        ),
      ),
    )

    const thrown = await request('/v1/thing', okSchema).catch((error: unknown) => error)

    expect(thrown).toBeInstanceOf(ConflictError)
    expect((thrown as ConflictError).conflicts[0]?.actualValue).toBe('theirs')
  })

  it('keeps the request id off every error, so a user can quote one string', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(500, problem(500))))

    const thrown = (await request('/v1/thing', okSchema).catch((e: unknown) => e)) as ApiError

    expect(thrown.requestId).toBe('req-123')
  })

  it('surfaces field level validation errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          respond(422, problem(422, { errors: [{ field: 'password', message: 'too short' }] })),
        ),
    )

    const thrown = (await request('/v1/thing', okSchema).catch((e: unknown) => e)) as ApiError

    expect(thrown.fieldErrors[0]).toEqual({ field: 'password', message: 'too short' })
  })

  it('signs the user out on any 401, in one place rather than at every call site', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(401, problem(401))))

    await request('/v1/thing', okSchema).catch(() => undefined)

    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not throw while handling an error body that is not JSON', async () => {
    // A client that crashes while reporting a failure hides the failure.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('<html>gateway timeout</html>', { status: 504 })),
    )

    const thrown = (await request('/v1/thing', okSchema).catch((e: unknown) => e)) as ApiError

    expect(thrown).toBeInstanceOf(ApiError)
    expect(thrown.status).toBe(504)
  })

  it('rejects a response whose shape does not match the schema', async () => {
    // The reason the API layer uses zod rather than a cast. A silently wrong
    // shape surfaces three renders later as undefined; this surfaces here.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(200, { ok: 'not a boolean' })))

    await expect(request('/v1/thing', okSchema)).rejects.toThrow()
  })
})
