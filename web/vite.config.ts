import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The API runs on 8080, the web app on 5173. Proxying /api to the API keeps
// every browser call same origin, so there is no CORS configuration to get
// wrong and no preflight on every request. The same proxy is configured for
// `vite preview`, so the Playwright smoke test hits the built bundle through
// the identical path the dev server uses.
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8080'

const proxy = {
  // No rewrite. The API genuinely lives under /api/v1, so stripping the /api
  // prefix here would send /v1/sheets to a server that has no such route.
  // Phase 0 stripped it because the only call was /actuator/health, which sits
  // at the root; that made this look correct right up until the first real
  // endpoint was called.
  '/api': {
    target: apiTarget,
    changeOrigin: true,
  },
  // Health and probes are at the root rather than under /api.
  '/actuator': {
    target: apiTarget,
    changeOrigin: true,
  },
  // The live update socket. ws: true is what makes the proxy forward the
  // upgrade handshake instead of treating it as a normal request, and without
  // it the browser gets a 400 that looks like the endpoint does not exist.
  '/ws': {
    target: apiTarget,
    changeOrigin: true,
    ws: true,
  },
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Playwright owns e2e. Vitest must not try to run those specs.
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
})
