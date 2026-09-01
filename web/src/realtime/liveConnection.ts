import { z } from 'zod'

export const liveMessageSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('ready'), sheetId: z.string(), sequence: z.number() }),
  z.object({
    type: z.literal('replayed'),
    sequence: z.number(),
    changes: z.array(
      z.object({
        type: z.literal('cellChanged'),
        sheetId: z.string(),
        rowId: z.string(),
        columnId: z.string(),
        value: z.string().nullable(),
        version: z.number(),
        sequence: z.number(),
        changedBy: z.string(),
      }),
    ),
  }),
  z.object({ type: z.literal('resync'), reason: z.string(), sequence: z.number() }),
  z.object({
    type: z.literal('cellChanged'),
    sheetId: z.string(),
    rowId: z.string(),
    columnId: z.string(),
    value: z.string().nullable(),
    version: z.number(),
    sequence: z.number(),
    changedBy: z.string(),
  }),
  z.object({ type: z.literal('error'), reason: z.string() }),
])

export type LiveMessage = z.infer<typeof liveMessageSchema>
export type CellChanged = Extract<LiveMessage, { type: 'cellChanged' }>

export interface LiveDeps {
  open: (url: string) => WebSocket
  onCellChanged: (change: CellChanged) => void
  /** The server could not fill the gap. Refetch the sheet. */
  onResync: (reason: string) => void
  onStatus: (status: LiveStatus) => void
  /** Backoff schedule in milliseconds, one entry per attempt. The last is reused. */
  backoffMs?: number[]
}

export type LiveStatus = 'connecting' | 'live' | 'offline'

/**
 * The browser half of the live protocol.
 *
 * Deliberately free of React so the reconnect and cursor rules can be tested
 * without rendering anything, the same split as the write queue in ADR 0006.
 *
 * Two things it must get right. It has to remember the last sequence it
 * applied, because that is what lets the server replay the gap after a
 * reconnect rather than making the client refetch a 2,000 row sheet. And it
 * has to back off when reconnecting, because a client that retries in a tight
 * loop turns a brief server restart into a stampede.
 */
export function createLiveConnection(deps: LiveDeps) {
  const backoff = deps.backoffMs ?? [500, 1000, 2000, 5000, 10000]

  let socket: WebSocket | null = null
  let lastSequence: number | null = null
  let attempt = 0
  let closedByUs = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let current: { url: string; token: string; sheetId: string } | null = null

  function connect(url: string, token: string, sheetId: string): void {
    current = { url, token, sheetId }
    closedByUs = false
    deps.onStatus('connecting')

    const ws = deps.open(url)
    socket = ws

    ws.onopen = () => {
      attempt = 0
      // The token goes in the first frame rather than the URL, because a
      // query string ends up in access logs and a bearer token in a log is a
      // credential in a log.
      ws.send(JSON.stringify({ token, sheetId, lastSeen: lastSequence }))
    }

    ws.onmessage = (event: MessageEvent<string>) => {
      const parsed = liveMessageSchema.safeParse(JSON.parse(event.data))
      if (!parsed.success) return
      handle(parsed.data)
    }

    ws.onclose = () => {
      socket = null
      if (closedByUs) return
      deps.onStatus('offline')
      scheduleReconnect()
    }

    ws.onerror = () => {
      // onclose always follows, so reconnection is handled in one place.
    }
  }

  function handle(message: LiveMessage): void {
    switch (message.type) {
      case 'ready':
        lastSequence = message.sequence
        deps.onStatus('live')
        break
      case 'replayed':
        for (const change of message.changes) {
          lastSequence = Math.max(lastSequence ?? 0, change.sequence)
          deps.onCellChanged(change)
        }
        lastSequence = message.sequence
        deps.onStatus('live')
        break
      case 'resync':
        // The gap was too big to replay, so the cursor is adopted and the
        // caller refetches. Adopting it matters: without it the next
        // reconnect would ask for the same impossible replay again.
        lastSequence = message.sequence
        deps.onResync(message.reason)
        deps.onStatus('live')
        break
      case 'cellChanged':
        lastSequence = Math.max(lastSequence ?? 0, message.sequence)
        deps.onCellChanged(message)
        break
      case 'error':
        deps.onStatus('offline')
        break
    }
  }

  function scheduleReconnect(): void {
    if (current === null) return
    const delay = backoff[Math.min(attempt, backoff.length - 1)] ?? 10000
    attempt += 1
    timer = setTimeout(() => {
      timer = null
      if (current !== null) connect(current.url, current.token, current.sheetId)
    }, delay)
  }

  return {
    connect,
    close: () => {
      closedByUs = true
      current = null
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      socket?.close()
      socket = null
    },
    lastSequence: () => lastSequence,
    attempts: () => attempt,
  }
}

export type LiveConnection = ReturnType<typeof createLiveConnection>
