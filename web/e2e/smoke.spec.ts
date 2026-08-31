import { expect, test } from '@playwright/test'

test('the app loads and reaches the api', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Gridwork' })).toBeVisible()

  // Not just "the page rendered". This asserts the browser reached the API
  // through the proxy and got a healthy answer back.
  await expect(page.getByTestId('api-status')).toHaveText('api: UP')
})

test('the api echoes a request id', async ({ request }) => {
  const response = await request.get('/api/actuator/health', {
    headers: { 'X-Request-Id': 'playwright-smoke' },
  })

  expect(response.status()).toBe(200)
  expect(response.headers()['x-request-id']).toBe('playwright-smoke')
})
