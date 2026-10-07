import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { EventsPage } from './EventsPage'
import type { SystemEventResponse } from '../api/types'
import { json, renderWithProviders, stubFetch, testUser } from '../test/render'

function event(id: number): SystemEventResponse {
  return {
    id,
    eventId: `11111111-1111-1111-1111-00000000000${id}`,
    eventType: 'ORDER_CREATED',
    eventVersion: 1,
    aggregateType: 'order',
    aggregateId: '42',
    topic: 'smartroute.order.created',
    correlationId: 'corr-1',
    summary: 'Order ORD-000042 created',
    payload: '{"orderId":42}',
    occurredAt: '2026-10-06T11:59:00Z',
    recordedAt: '2026-10-06T11:59:01Z',
  }
}

/** The server returns a slice here, not a page: no total, only whether there is more. */
function logPage(page: number, hasNext: boolean) {
  return { content: [event(page + 1)], page, size: 20, hasNext }
}

describe('Events page', () => {
  it('pages through an uncounted log, and says so rather than inventing a total', async () => {
    const calls = stubFetch({
      '/api/events': (_init, url) => json(logPage(url.includes('page=1') ? 1 : 0, !url.includes('page=1'))),
      '/api/events/outbox': () => json({ pending: 3, publishedEstimate: 299000, failing: 0, at: '2026-10-06T12:00:00Z' }),
    })
    renderWithProviders(<EventsPage />, { user: testUser('ADMIN') })

    await screen.findByText('Order ORD-000042 created')
    expect(screen.getByText('page 1')).toBeInTheDocument()
    expect(screen.queryByText(/results?/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(screen.getByText('page 2')).toBeInTheDocument())
    expect(calls.some((call) => call.url.includes('/api/events?page=1'))).toBe(true)
    // The slice said there is nothing after this page, so there is nothing to click.
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('shows the outbox depth to an admin, with the published total marked as approximate', async () => {
    stubFetch({
      '/api/events': () => json(logPage(0, false)),
      '/api/events/outbox': () => json({ pending: 3, publishedEstimate: 299000, failing: 0, at: '2026-10-06T12:00:00Z' }),
    })
    renderWithProviders(<EventsPage />, { user: testUser('ADMIN') })

    expect(await screen.findByText(/3 waiting to publish/)).toBeInTheDocument()
    expect(screen.getByText(/about 299,000 published/)).toBeInTheDocument()
  })

  it('does not ask for the outbox as a dispatcher, who may not read it', async () => {
    const calls = stubFetch({ '/api/events': () => json(logPage(0, false)) })
    renderWithProviders(<EventsPage />, { user: testUser('DISPATCHER') })

    await screen.findByText('Order ORD-000042 created')
    expect(calls.some((call) => call.url.includes('/api/events/outbox'))).toBe(false)
  })
})
