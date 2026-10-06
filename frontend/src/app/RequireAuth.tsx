import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { Card, Loading } from '../ui'
import type { Role } from '../api/types'

/**
 * Keeps a signed-out user out of the application shell, and sends them back where they were going afterwards.
 *
 * This is navigation, not security. Every endpoint behind these pages checks the role itself, from the signed
 * token, so removing this component would make the app ugly rather than unsafe.
 */
export function RequireAuth({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) return <Loading label="Restoring your session" />
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (roles && !hasRole(user, ...roles)) {
    return (
      <Card title="Not available for your role">
        <p className="text-sm text-slate-600">
          This page is for {roles.join(' and ').toLowerCase()} accounts. Your account is a {user.role.toLowerCase()}.
        </p>
      </Card>
    )
  }
  return <>{children}</>
}
