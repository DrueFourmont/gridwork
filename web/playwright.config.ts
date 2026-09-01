import { defineConfig, devices } from '@playwright/test'

// Smoke only. Playwright drives the built bundle served by `vite preview`,
// which proxies /api to a real API, so a pass means the production build
// works, not just the dev server.
export default defineConfig({
  testDir: './e2e',
  // The clip recorder is not a test. It needs two Vite servers and two API
  // replicas running by hand, it records video, and it types slowly so the
  // result is watchable. Run it with `npm run clip`, which uses
  // playwright.clip.config.ts. Excluding it here is what makes the comment in
  // that file true; without this line CI picks it up and fails on a port that
  // only exists on a developer's laptop.
  testIgnore: ['**/two-replica-clip.spec.ts'],
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'list' : 'html',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.PLAYWRIGHT_BASE_URL
    ? undefined
    : {
        command: 'npm run build && npm run preview',
        url: 'http://localhost:4173',
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
})
