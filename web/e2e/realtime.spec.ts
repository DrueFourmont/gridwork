import { expect, test } from '@playwright/test'
import { API, createAccount, createSheetWithGrid, signIn } from './support'

/**
 * Two browsers, one sheet, and an edit in one appearing in the other.
 *
 * These are two independent browser contexts, not two tabs sharing a page, so
 * each has its own websocket, its own session storage, and its own React tree.
 * Anything that arrives in the second browser genuinely travelled through the
 * server.
 */
test.describe('live updates', () => {
  test('an edit in one browser appears in another without a refresh', async ({
    browser,
    request,
  }) => {
    const account = await createAccount(request)
    const { columnId, rowId } = await createSheetWithGrid(request, account, 'Live test')

    const alice = await browser.newContext()
    const bob = await browser.newContext()
    const alicePage = await alice.newPage()
    const bobPage = await bob.newPage()

    try {
      await signIn(alicePage, account)
      await signIn(bobPage, account)
      await alicePage.goto('/')
      await bobPage.goto('/')
      await alicePage.getByTestId('open-sheet-Live test').click()
      await bobPage.getByTestId('open-sheet-Live test').click()

      // Both sockets have to be up before the write, or the change happens
      // while nobody is listening and pub/sub correctly drops it.
      await expect(alicePage.getByTestId('live-status')).toHaveAttribute('data-status', 'live')
      await expect(bobPage.getByTestId('live-status')).toHaveAttribute('data-status', 'live')

      const bobCell = bobPage.getByTestId(`cell-${rowId}-${columnId}`)
      await expect(bobCell).toHaveText('')

      await alicePage.getByTestId(`cell-${rowId}-${columnId}`).dblclick()
      await alicePage.getByTestId(`cell-input-${rowId}-${columnId}`).fill('typed by alice')
      await alicePage.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

      // Bob never reloaded. This can only arrive over the socket.
      await expect(bobCell).toHaveText('typed by alice', { timeout: 10_000 })
      await expect(bobCell).toHaveAttribute('data-version', '2')
    } finally {
      await alice.close()
      await bob.close()
    }
  })

  test('your own edit is not disturbed when it echoes back over the socket', async ({
    page,
    request,
  }) => {
    // Every write you make comes back to you over the socket, because the
    // server broadcasts to everyone watching the sheet including you. The
    // version check has to make that a no op. If it did not, the cell would
    // visibly flicker, and an edit still in progress could be clobbered by
    // the echo of the previous one.
    const account = await createAccount(request)
    const { columnId, rowId } = await createSheetWithGrid(request, account, 'Echo test')

    await signIn(page, account)
    await page.goto('/')
    await page.getByTestId('open-sheet-Echo test').click()
    await expect(page.getByTestId('live-status')).toHaveAttribute('data-status', 'live')

    const cell = page.getByTestId(`cell-${rowId}-${columnId}`)
    await cell.dblclick()
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).fill('my own edit')
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

    await expect(cell).toHaveText('my own edit')
    await expect(cell).toHaveAttribute('data-version', '2')

    // Still correct well after the echo has had time to arrive.
    await page.waitForTimeout(2000)
    await expect(cell).toHaveText('my own edit')
    await expect(cell).toHaveAttribute('data-version', '2')
  })

  test('a change made while disconnected is replayed on reconnect', async ({ page, request }) => {
    // The replay protocol, end to end in a browser.
    //
    // The socket is cut with routeWebSocket rather than with setOffline,
    // because Chromium's offline emulation blocks new requests but leaves an
    // established websocket open, so the client never notices. Cutting the
    // route is a real close from the client's point of view.
    const account = await createAccount(request)
    const { sheetId, columnId, rowId } = await createSheetWithGrid(request, account, 'Replay test')

    // Held in a single element array rather than a plain `let`, because
    // TypeScript cannot see that the route callback runs before the read and
    // narrows a nullable local to never.
    const cut: { close: (() => void) | null } = { close: null }
    await page.routeWebSocket(/\/ws\/sheet/, (ws) => {
      const server = ws.connectToServer()
      ws.onMessage((message) => {
        server.send(message)
      })
      server.onMessage((message) => {
        ws.send(message)
      })
      cut.close ??= () => {
        // close() returns a promise the route callback cannot await, and the
        // test does not need to: the client observes the drop either way.
        void ws.close()
      }
    })

    await signIn(page, account)
    await page.goto('/')
    await page.getByTestId('open-sheet-Replay test').click()
    await expect(page.getByTestId('live-status')).toHaveAttribute('data-status', 'live')

    cut.close?.()
    await expect(page.getByTestId('live-status')).toHaveAttribute('data-status', 'offline', {
      timeout: 15_000,
    })

    // A change lands while nobody is listening. Redis pub/sub drops it, which
    // is exactly the hole the replay protocol exists to fill.
    await request.patch(`${API}/sheets/${sheetId}/cells:batchUpdate`, {
      headers: { Authorization: `Bearer ${account.token}` },
      data: { updates: [{ rowId, columnId, value: 'missed while offline', expectedVersion: 1 }] },
    })

    // The client reconnects on its own and asks for everything after the
    // sequence it last saw.
    await expect(page.getByTestId('live-status')).toHaveAttribute('data-status', 'live', {
      timeout: 30_000,
    })
    await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText('missed while offline', {
      timeout: 15_000,
    })
  })
})
