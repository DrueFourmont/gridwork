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
