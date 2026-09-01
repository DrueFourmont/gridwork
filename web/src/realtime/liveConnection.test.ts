import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createLiveConnection, type LiveDeps } from './liveConnection'

/**
 * The reconnect and cursor rules, tested with a fake socket. No server, no
 * network, no React, for the same reason the write queue is tested this way.
 */

class FakeSocket {
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  readonly sent: string[] = []
  closed = false

  send(payload: string): void {
    this.sent.push(payload)
  }
  close(): void {
    this.closed = true
  }
  /** Simulate the server pushing a frame. */
  receive(message: unknown): void {
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent<string>)
  }
  /** Simulate the connection dropping from the other end. */
  drop(): void {
    this.onclose?.()
  }
}

function change(sequence: number, value: string) {
  return {
    type: 'cellChanged' as const,
    sheetId: 's',
    rowId: 'r',
    columnId: 'c',
    value,
    version: sequence,
    sequence,
    changedBy: 'someone',
  }
}

function setup() {
  const sockets: FakeSocket[] = []
  const deps: LiveDeps = {
    open: () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket as unknown as WebSocket
    },
    onCellChanged: vi.fn(),
    onResync: vi.fn(),
    onStatus: vi.fn(),
    backoffMs: [10, 20, 40],
  }
  return { deps, sockets, live: createLiveConnection(deps) }
}

describe('live connection', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('authenticates in the first frame, not in the url', () => {
    // A token in a query string ends up in access logs and proxy logs.
    const { sockets, live } = setup()
    live.connect('ws://x/ws/sheet', 'the-token', 'sheet-1')
    sockets[0]?.onopen?.()

    expect(sockets[0]?.sent).toHaveLength(1)
    expect(JSON.parse(sockets[0]?.sent[0] ?? '{}')).toEqual({
      token: 'the-token',
      sheetId: 'sheet-1',
      lastSeen: null,
    })
  })

  it('applies a live change and remembers its sequence', () => {
    const { deps, sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.onopen?.()
    sockets[0]?.receive(change(42, 'hello'))

    expect(deps.onCellChanged).toHaveBeenCalledWith(expect.objectContaining({ value: 'hello' }))
    expect(live.lastSequence()).toBe(42)
  })

  it('sends the remembered sequence when it reconnects, so the server can replay the gap', async () => {
    // The entire point of tracking the cursor. Without it every reconnect
    // would mean refetching the whole sheet.
    const { sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.onopen?.()
    sockets[0]?.receive(change(7, 'a'))
    sockets[0]?.drop()

    await vi.advanceTimersByTimeAsync(50)
    sockets[1]?.onopen?.()

    expect(JSON.parse(sockets[1]?.sent[0] ?? '{}')).toMatchObject({ lastSeen: 7 })
  })

  it('applies replayed changes in order and then goes live', () => {
    const { deps, sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.onopen?.()
    sockets[0]?.receive({
      type: 'replayed',
      sequence: 9,
      changes: [change(8, 'first'), change(9, 'second')],
    })

    expect(deps.onCellChanged).toHaveBeenCalledTimes(2)
    expect(live.lastSequence()).toBe(9)
    expect(deps.onStatus).toHaveBeenCalledWith('live')
  })

  it('asks the caller to refetch when the server says resync', () => {
    const { deps, sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.onopen?.()
    sockets[0]?.receive({ type: 'resync', reason: 'TOO_FAR_BEHIND', sequence: 5000 })

    expect(deps.onResync).toHaveBeenCalledWith('TOO_FAR_BEHIND')
    // The cursor is adopted, or the next reconnect would ask for the same
    // impossible replay and be refused again forever.
    expect(live.lastSequence()).toBe(5000)
  })

  it('backs off between reconnect attempts instead of hammering the server', async () => {
    const { sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.drop()

    await vi.advanceTimersByTimeAsync(5)
    expect(sockets).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(10)
    expect(sockets).toHaveLength(2)

    sockets[1]?.drop()
    await vi.advanceTimersByTimeAsync(15)
    expect(sockets).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(10)
    expect(sockets).toHaveLength(3)
  })

  it('resets the backoff after a successful connection', async () => {
    const { sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.drop()
    await vi.advanceTimersByTimeAsync(20)
    sockets[1]?.onopen?.()
    expect(live.attempts()).toBe(0)
  })

  it('does not reconnect after the caller closes it', async () => {
    // Navigating away must not leave a socket quietly reconnecting forever.
    const { sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    live.close()
    sockets[0]?.drop()

    await vi.advanceTimersByTimeAsync(1000)
    expect(sockets).toHaveLength(1)
  })

  it('ignores a frame it cannot parse rather than crashing the page', () => {
    const { deps, sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    sockets[0]?.onopen?.()
    sockets[0]?.receive({ type: 'somethingNew', surprise: true })

    expect(deps.onCellChanged).not.toHaveBeenCalled()
  })

  it('reports offline while disconnected and live once ready', () => {
    const { deps, sockets, live } = setup()
    live.connect('ws://x', 't', 's')
    expect(deps.onStatus).toHaveBeenCalledWith('connecting')

    sockets[0]?.onopen?.()
    sockets[0]?.receive({ type: 'ready', sheetId: 's', sequence: 3 })
    expect(deps.onStatus).toHaveBeenCalledWith('live')

    sockets[0]?.drop()
    expect(deps.onStatus).toHaveBeenCalledWith('offline')
  })
})
