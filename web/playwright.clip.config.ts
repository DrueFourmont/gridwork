import { defineConfig, devices } from '@playwright/test'

/**
 * A separate config for recording the Phase 3 clip.
 *
 * It is deliberately not part of the normal suite: it needs two Vite servers
 * and two API replicas running, it records video, and it types slowly so the
 * result is watchable. None of that belongs in a test run that has to be fast
 * and hermetic.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  reporter: 'list',
  outputDir: '../docs/clips',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://localhost:5173',
    video: 'on',
  },
})
