import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

function mockFetchResponse(status: number, body: unknown, contentType = 'application/json') {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'content-type': contentType } }),
  )
}

describe('App system status', () => {
  it('shows a checking state while the request is in flight', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))
    render(<App />)
    expect(screen.getByText('Checking…')).toBeInTheDocument()
  })

  it('shows Operational when the backend reports UP', async () => {
    mockFetchResponse(200, { status: 'UP' })
    render(<App />)
    expect(await screen.findByText('Operational')).toBeInTheDocument()
  })

  it('shows Degraded when the backend reports DOWN with HTTP 503', async () => {
    mockFetchResponse(503, { status: 'DOWN' })
    render(<App />)
    expect(await screen.findByText('Degraded (DOWN)')).toBeInTheDocument()
  })

  it('shows Unreachable when the request fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    render(<App />)
    expect(await screen.findByText('Unreachable')).toBeInTheDocument()
    expect(screen.getByText('Failed to fetch')).toBeInTheDocument()
  })

  it('shows Unreachable when a proxy returns a non-JSON error page', async () => {
    mockFetchResponse(502, '<html>Bad Gateway</html>', 'text/html')
    render(<App />)
    expect(await screen.findByText('Unreachable')).toBeInTheDocument()
  })
})
