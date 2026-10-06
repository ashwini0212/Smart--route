import { useEffect, useState } from 'react'
import { fetchHealth } from '../services/healthApi'
import type { HealthStatus } from '../types/health'

export type BackendHealth =
  | { state: 'loading' }
  | { state: 'loaded'; status: HealthStatus }
  | { state: 'unreachable'; message: string }

/** Fetches backend health once on mount; aborts the request if the component unmounts. */
export function useBackendHealth(): BackendHealth {
  const [health, setHealth] = useState<BackendHealth>({ state: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchHealth(controller.signal)
      .then((body) => setHealth({ state: 'loaded', status: body.status }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        const message = error instanceof Error ? error.message : 'Unknown error'
        setHealth({ state: 'unreachable', message })
      })
    return () => controller.abort()
  }, [])

  return health
}
