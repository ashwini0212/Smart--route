import type { ApiErrorBody } from './types'

/**
 * The one place that talks to the backend.
 *
 * Two decisions worth knowing about:
 *
 * 1. The access token lives in a module variable, not in localStorage. A token in localStorage is readable by
 *    any script that ends up on the page, and it survives the tab; this one dies with the tab and is restored
 *    on load from the refresh cookie, which the browser will not hand to JavaScript at all.
 * 2. A 401 is retried exactly once, after refreshing. Concurrent 401s share one refresh (see `refreshing`),
 *    because five pages mounting at once must not rotate the refresh token five times — the backend treats a
 *    reused refresh token as theft and revokes the family (Phase 5).
 */

let accessToken: string | null = null
let refreshing: Promise<string | null> | null = null
let onAuthLost: (() => void) | null = null

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function getAccessToken(): string | null {
  return accessToken
}

/** Called when a refresh fails, so the app can send the user back to the login page. */
export function setAuthLostHandler(handler: (() => void) | null): void {
  onAuthLost = handler
}

/** An error carrying what the server said, so a page can show the message instead of inventing one. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId?: string
  readonly fieldErrors: { field: string; message: string }[]

  constructor(status: number, body: Partial<ApiErrorBody> | null, fallback: string) {
    super(body?.message ?? fallback)
    this.name = 'ApiError'
    this.status = status
    this.code = body?.code ?? 'UNKNOWN'
    this.traceId = body?.traceId
    this.fieldErrors = body?.fieldErrors ?? []
  }

  /** Field errors keyed by field name, which is the shape a form wants. */
  byField(): Record<string, string> {
    return Object.fromEntries(this.fieldErrors.map((e) => [e.field, e.message]))
  }
}

export interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
  /** Set for /api/auth/refresh itself, which must not try to refresh when it fails. */
  skipRefresh?: boolean
}

function headersFor(body: unknown): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  return headers
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const text = await response.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

async function errorFor(response: Response, fallback: string): Promise<ApiError> {
  let body: Partial<ApiErrorBody> | null = null
  try {
    const text = await response.text()
    if (text) body = JSON.parse(text) as Partial<ApiErrorBody>
  } catch {
    // A proxy error page or an empty body: the status alone has to carry the message.
  }
  return new ApiError(response.status, body, fallback)
}

/** Refreshes the access token, sharing one in-flight request between every caller. */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= fetch('/api/auth/refresh', { method: 'POST', headers: { Accept: 'application/json' } })
    .then(async (response) => {
      if (!response.ok) return null
      const body = (await response.json()) as { accessToken: string }
      accessToken = body.accessToken
      return body.accessToken
    })
    .catch(() => null)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, skipRefresh = false } = options
  const send = () =>
    fetch(path, {
      method,
      headers: headersFor(body),
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })

  let response = await send()
  if (response.status === 401 && !skipRefresh) {
    const token = await refreshAccessToken()
    if (!token) {
      accessToken = null
      onAuthLost?.()
      throw await errorFor(response, 'Your session has expired. Please sign in again.')
    }
    response = await send()
  }
  if (!response.ok) {
    throw await errorFor(response, `Request failed (HTTP ${response.status})`)
  }
  return parse<T>(response)
}

/** Builds a query string, leaving out anything empty so the backend sees "not filtered" rather than "". */
export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}
