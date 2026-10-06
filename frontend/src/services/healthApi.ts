import type { HealthResponse } from '../types/health'

/**
 * Reads the backend's health from Spring Boot Actuator.
 * A non-2xx response still carries a body (e.g. 503 with status DOWN), so we parse it when present.
 */
export async function fetchHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const response = await fetch('/actuator/health', { signal, headers: { Accept: 'application/json' } })
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('json')) {
    throw new Error(`Unexpected response from health endpoint (HTTP ${response.status})`)
  }
  return (await response.json()) as HealthResponse
}
