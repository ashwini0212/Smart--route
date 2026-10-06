import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, query, request, setAccessToken, setAuthLostHandler } from './client'
import { json } from '../test/render'

describe('API client', () => {
  beforeEach(() => {
    setAccessToken(null)
    setAuthLostHandler(null)
  })

  it('sends the access token when there is one', async () => {
    setAccessToken('token-123')
    const fetchStub = vi.fn().mockResolvedValue(json({ ok: true }))
    globalThis.fetch = fetchStub as unknown as typeof fetch

    await request('/api/orders')

    const headers = fetchStub.mock.calls[0][1].headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer token-123')
  })

  it('refreshes once after a 401 and retries the original request', async () => {
    const fetchStub = vi
      .fn()
      .mockResolvedValueOnce(json({ code: 'UNAUTHORIZED', message: 'expired', status: 401 }, 401))
      .mockResolvedValueOnce(json({ accessToken: 'fresh' }))
      .mockResolvedValueOnce(json({ id: 5 }))
    globalThis.fetch = fetchStub as unknown as typeof fetch

    const result = await request<{ id: number }>('/api/orders/5')

    expect(result.id).toBe(5)
    expect(fetchStub.mock.calls.map((call) => call[0])).toEqual(['/api/orders/5', '/api/auth/refresh', '/api/orders/5'])
    // The retry carries the new token, which is the whole point of refreshing.
    expect((fetchStub.mock.calls[2][1].headers as Record<string, string>).Authorization).toBe('Bearer fresh')
  })

  it('gives up and reports the session lost when the refresh fails', async () => {
    const lost = vi.fn()
    setAuthLostHandler(lost)
    globalThis.fetch = vi.fn().mockResolvedValue(json({ code: 'UNAUTHORIZED', message: 'nope', status: 401 }, 401)) as unknown as typeof fetch

    await expect(request('/api/orders')).rejects.toBeInstanceOf(ApiError)
    expect(lost).toHaveBeenCalledOnce()
  })

  it('shares one refresh between requests that fail at the same time', async () => {
    const fetchStub = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') return json({ accessToken: 'shared' })
      // Unauthorized until a token is set; afterwards the retry succeeds.
      return json({ ok: true })
    })
    let first = true
    const routed = vi.fn(async (url: string) => {
      if (url !== '/api/auth/refresh' && first) {
        first = false
        return json({ code: 'UNAUTHORIZED', message: 'expired', status: 401 }, 401)
      }
      return fetchStub(url)
    })
    globalThis.fetch = routed as unknown as typeof fetch

    await Promise.all([request('/api/a'), request('/api/b')])

    // Exactly one refresh: a second rotation would make the backend treat the first token as reused and revoke it.
    const refreshes = routed.mock.calls.filter((call) => call[0] === '/api/auth/refresh')
    expect(refreshes).toHaveLength(1)
  })

  it('turns an error body into an ApiError with the field errors', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      json(
        {
          status: 400,
          code: 'VALIDATION_FAILED',
          message: 'Request validation failed',
          traceId: 'abc-123',
          fieldErrors: [{ field: 'weightKg', message: 'must be greater than 0' }],
        },
        400,
      ),
    ) as unknown as typeof fetch

    const error = await request('/api/orders', { method: 'POST', body: {} }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(400)
    expect(apiError.message).toBe('Request validation failed')
    expect(apiError.traceId).toBe('abc-123')
    expect(apiError.byField()).toEqual({ weightKg: 'must be greater than 0' })
  })

  it('falls back to the status when the body is not the usual error shape', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 })) as unknown as typeof fetch

    await expect(request('/api/orders')).rejects.toThrow('Request failed (HTTP 502)')
  })

  it('leaves empty filters out of the query string', () => {
    expect(query({ status: 'CREATED', priority: '', driverId: null, page: 0 })).toBe('?status=CREATED&page=0')
    expect(query({})).toBe('')
  })
})
