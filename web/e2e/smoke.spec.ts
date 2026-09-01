import { expect, test } from '@playwright/test'
import { API } from './support'

/**
 * The Phase 0 smoke test, updated for a real app.
 *
 * It used to assert the page showed "api: UP", which was the whole of the
 * Phase 0 shell. That page no longer exists: the first thing a visitor now
 * sees is a login screen, so the smoke test checks that instead.
 */
test('the app loads and shows the sign in screen', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Gridwork' })).toBeVisible()
  await expect(page.getByTestId('email')).toBeVisible()
})

test('the api is reachable through the proxy and echoes a request id', async ({ request }) => {
  const response = await request.get('/actuator/health', {
    headers: { 'X-Request-Id': 'playwright-smoke' },
  })
  expect(response.status()).toBe(200)
  expect(response.headers()['x-request-id']).toBe('playwright-smoke')
})

test('a bad path is reported as not found, not as a server failure', async ({ request }) => {
  const response = await request.get(`${API}/definitely-not-a-real-endpoint`)
  expect(response.status()).toBeLessThan(500)
})
