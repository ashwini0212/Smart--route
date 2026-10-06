import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../auth/context'
import type { AuthState } from '../auth/context'
import type { Role, UserResponse } from '../api/types'

/** A signed-in user for tests. Fictional, like the seed data. */
export function testUser(role: Role = 'DISPATCHER', overrides: Partial<UserResponse> = {}): UserResponse {
  return {
    id: 1,
    email: `${role.toLowerCase()}@test.local`,
    fullName: 'Test User',
    role,
    driverId: role === 'DRIVER' ? 7 : null,
    enabled: true,
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export interface RenderOptions {
  user?: UserResponse | null
  loading?: boolean
  route?: string
}

/**
 * Renders a page with the providers it expects.
 *
 * `retry: false` matters: with retries on, a test asserting an error state waits for three attempts and the
 * assertion times out instead of failing usefully.
 */
export function renderWithProviders(element: ReactElement, options: RenderOptions = {}) {
  const { user = testUser(), loading = false, route = '/' } = options
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const auth: AuthState = {
    user,
    loading,
    login: async () => undefined,
    logout: async () => undefined,
  }
  const result = render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={auth}>
        <MemoryRouter initialEntries={[route]}>{element}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}

/** A fetch stub routed by URL, so a test says what each endpoint answers and nothing else is reachable. */
export function stubFetch(routes: Record<string, (input: RequestInit | undefined) => Response | Promise<Response>>) {
  const calls: { url: string; init?: RequestInit }[] = []
  const stub = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : String(input)
    calls.push({ url, init })
    const match = Object.keys(routes).find((pattern) => url.startsWith(pattern))
    if (!match) return new Response('{}', { status: 404, headers: { 'content-type': 'application/json' } })
    return routes[match](init)
  }
  globalThis.fetch = stub as typeof fetch
  return calls
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

export function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number; totalPages: number }> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...overrides,
  }
}
