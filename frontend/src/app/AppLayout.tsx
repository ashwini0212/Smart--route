import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { simulation } from '../api/endpoints'
import type { Role } from '../api/types'
import { Badge, Button } from '../ui'
import { humanize } from '../ui/format'

interface NavItem {
  to: string
  label: string
  roles?: Role[]
}

/** The navigation mirrors what the server allows, so a user is not offered a page that will answer 403. */
const navigation: NavItem[] = [
  { to: '/', label: 'Dashboard' },
  { to: '/map', label: 'Live map' },
  { to: '/orders', label: 'Orders' },
  { to: '/drivers', label: 'Drivers' },
  { to: '/vehicles', label: 'Vehicles' },
  { to: '/planner', label: 'Route planner' },
  { to: '/deliveries', label: 'My deliveries', roles: ['DRIVER'] },
  { to: '/analytics', label: 'Analytics' },
  { to: '/events', label: 'Events' },
  { to: '/admin', label: 'Admin', roles: ['ADMIN'] },
]

export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  // Shown in the header whenever anything is simulated, so nobody reads synthetic movement as real.
  const simulated = useQuery({
    queryKey: ['simulation-status'],
    queryFn: () => simulation.status(),
    refetchInterval: 60_000,
  })

  const visible = navigation.filter((item) => !item.roles || hasRole(user, ...item.roles))
  const anySimulation = simulated.data?.driverMovement || simulated.data?.traffic

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-3 px-4 py-3">
          <img src="/favicon.svg" alt="" className="h-7 w-7" />
          <span className="font-semibold">SmartRoute</span>
          {anySimulation && (
            <Badge tone="warning">
              SIMULATION: {simulated.data?.driverMovement ? 'driver movement' : ''}
              {simulated.data?.driverMovement && simulated.data?.traffic ? ' and ' : ''}
              {simulated.data?.traffic ? 'traffic' : ''}
            </Badge>
          )}
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="text-slate-600">
              {user?.fullName} · {user ? humanize(user.role) : ''}
            </span>
            <Button
              variant="secondary"
              onClick={() => {
                void logout().then(() => navigate('/login'))
              }}
            >
              Sign out
            </Button>
          </div>
        </div>
        <nav aria-label="Main" className="mx-auto max-w-7xl px-4">
          <ul className="flex gap-1 overflow-x-auto">
            {visible.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    `-mb-px inline-block border-b-2 px-3 py-2 text-sm font-medium whitespace-nowrap ${
                      isActive
                        ? 'border-slate-900 text-slate-900'
                        : 'border-transparent text-slate-600 hover:border-slate-300 hover:text-slate-900'
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
