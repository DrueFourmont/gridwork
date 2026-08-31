import { z } from 'zod'

// Parse the response rather than trusting it. zod turns "the API said
// something unexpected" into a typed failure at the boundary instead of an
// undefined halfway down a component tree. Every API response in later
// phases goes through a schema like this one.
const healthSchema = z.object({
  status: z.string(),
})

export type Health = z.infer<typeof healthSchema>

export const HEALTH_QUERY_KEY = ['health'] as const

export async function fetchHealth(): Promise<Health> {
  const response = await fetch('/api/actuator/health')
  if (!response.ok) {
    throw new Error(`health check failed with ${String(response.status)}`)
  }
  return healthSchema.parse(await response.json())
}
