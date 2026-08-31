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
  '/api': {
    target: apiTarget,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ''),
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
