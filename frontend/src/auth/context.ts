import { createContext } from 'react'
import type { Role, UserResponse } from '../api/types'

export interface AuthState {
  user: UserResponse | null
  /** True until the first refresh attempt has finished, so routes do not redirect before we know. */
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

/**
 * Lives in its own module so the provider file exports only a component: anything else in it breaks Vite's
 * fast refresh, which is the warning oxlint raises for a mixed module.
 */
export const AuthContext = createContext<AuthState | null>(null)

/** True when the signed-in user has any of these roles. Used for navigation and buttons, never for data. */
export function hasRole(user: UserResponse | null, ...roles: Role[]): boolean {
  return user !== null && roles.includes(user.role)
}
