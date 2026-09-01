import type { Page, APIRequestContext } from '@playwright/test'

export const API = 'http://localhost:8080/api/v1'

export interface Account {
  email: string
  password: string
  token: string
}

/** Creates a throwaway account straight against the API, so a UI test is not also a signup test. */
export async function createAccount(request: APIRequestContext): Promise<Account> {
  const email = `e2e-${String(Date.now())}-${String(Math.floor(Math.random() * 100000))}@example.com`
  const password = 'correct-horse-battery'
  await request.post(`${API}/auth/register`, {
    data: { email, password, displayName: 'E2E' },
  })
  const login = await request.post(`${API}/auth/login`, { data: { email, password } })
  const body = (await login.json()) as { token: string }
  return { email, password, token: body.token }
}

/**
 * Puts the token where the app expects it and loads the page already signed in.
 *
 * addInitScript runs before any of the app's own code, so the auth store reads
 * the token during its very first render and the login screen never appears.
 */
export async function signIn(page: Page, account: Account): Promise<void> {
  await page.addInitScript((token: string) => {
    sessionStorage.setItem('gridwork.token', token)
  }, account.token)
}

export async function createSheetWithGrid(
  request: APIRequestContext,
  account: Account,
  name: string,
): Promise<{ sheetId: string; columnId: string; rowId: string }> {
  const headers = { Authorization: `Bearer ${account.token}` }

  const sheet = await request.post(`${API}/sheets`, { headers, data: { name } })
  const { id: sheetId } = (await sheet.json()) as { id: string }

  const column = await request.post(`${API}/sheets/${sheetId}/columns`, {
    headers,
    data: { name: 'Task', type: 'TEXT' },
  })
  const { id: columnId } = (await column.json()) as { id: string }

  const row = await request.post(`${API}/sheets/${sheetId}/rows`, { headers })
  const { id: rowId } = (await row.json()) as { id: string }

  return { sheetId, columnId, rowId }
}

/**
 * Leaves the websocket open but delivers nothing from the server.
 *
 * Needed by the conflict tests from Phase 3 onwards. Live updates make
 * conflicts rare on purpose: if the other person's change reaches your browser
 * before you type, you are editing the current version and there is nothing to
 * conflict with. So to test the conflict path at all, the client has to be one
 * that did not get the update, which is a real situation whenever the socket is
 * degraded, and it is precisely when the version check earns its place.
 */
export async function suppressLiveUpdates(page: Page): Promise<void> {
  await page.routeWebSocket(/\/ws\/sheet/, () => {
    // Accept the connection and then say nothing. The client authenticates
    // into the void and never learns about anyone else's writes.
  })
}
