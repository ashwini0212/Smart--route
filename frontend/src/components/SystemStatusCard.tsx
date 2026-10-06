import { useBackendHealth } from '../hooks/useBackendHealth'
import { StatusBadge } from './StatusBadge'

export function SystemStatusCard() {
  const health = useBackendHealth()

  return (
    <section aria-labelledby="system-status-heading" className="rounded-lg border border-slate-200 bg-white p-5">
      <h2 id="system-status-heading" className="text-base font-semibold text-slate-900">
        System status
      </h2>
      <div className="mt-3 flex items-center justify-between gap-4" aria-live="polite">
        <span className="text-sm text-slate-600">Backend API</span>
        {health.state === 'loading' && <StatusBadge tone="neutral" label="Checking…" />}
        {health.state === 'loaded' && (
          <StatusBadge
            tone={health.status === 'UP' ? 'success' : 'danger'}
            label={health.status === 'UP' ? 'Operational' : `Degraded (${health.status})`}
          />
        )}
        {health.state === 'unreachable' && <StatusBadge tone="danger" label="Unreachable" />}
      </div>
      {health.state === 'unreachable' && (
        <p className="mt-2 text-sm text-red-700">{health.message}</p>
      )}
    </section>
  )
}
