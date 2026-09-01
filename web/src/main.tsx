import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
// Imported for its side effects: wiring the api client's token reader and
// its 401 handler to the store. Without this the client sends no token.
import './state/authStore'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The grid refetches on demand, not on every window focus. A refetch
      // mid edit would fight the optimistic value the user is looking at.
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

const root = document.getElementById('root')
if (!root) {
  throw new Error('missing #root element in index.html')
}

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
