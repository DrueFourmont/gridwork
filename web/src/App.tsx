import { useQuery } from '@tanstack/react-query'
import { fetchHealth, HEALTH_QUERY_KEY } from './api/health'

/**
 * Phase 0 shell. It renders the product name and the result of one call to
 * the API health endpoint, which is enough to prove the whole path end to
 * end: browser, Vite proxy, Spring filter chain, actuator. The grid itself
 * arrives in Phase 2.
 */
export default function App() {
  const { data, isPending, isError } = useQuery({
    queryKey: HEALTH_QUERY_KEY,
    queryFn: fetchHealth,
    retry: false,
  })

  const apiStatus = isPending ? 'checking' : isError ? 'unreachable' : (data?.status ?? 'unknown')

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-2">
      <h1 className="text-3xl font-semibold">Gridwork</h1>
      <p data-testid="api-status" className="text-sm text-neutral-500">
        api: {apiStatus}
      </p>
    </main>
  )
}
