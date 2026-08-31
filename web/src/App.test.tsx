import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function renderApp() {
  // A fresh client per test, retries off, so a failing fetch surfaces
  // immediately instead of being retried into a timeout.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('App', () => {
  it('renders the product name', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    renderApp()
    expect(screen.getByRole('heading', { name: 'Gridwork' })).toBeInTheDocument()
  })

  it('shows the api status once the health check resolves', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"status":"UP"}', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    renderApp()
    expect(await screen.findByText('api: UP')).toBeInTheDocument()
  })

  it('reports the api as unreachable when the call fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connection refused')))
    renderApp()
    expect(await screen.findByText('api: unreachable')).toBeInTheDocument()
  })
})
