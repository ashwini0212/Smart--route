import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { OrdersPage } from './OrdersPage'
import type { OrderResponse } from '../api/types'
import { json, page, renderWithProviders, stubFetch, testUser } from '../test/render'

function order(overrides: Partial<OrderResponse> = {}): OrderResponse {
  return {
    id: 1,
    code: 'ORD-0001',
    warehouseId: 1,
    customerName: 'Fictional Customer',
    dropAddress: '1 Test Street',
    dropLatitude: 12.97,
    dropLongitude: 77.59,
    priority: 'NORMAL',
    status: 'CREATED',
    weightKg: '10.00',
    volumeM3: '0.50',
    requiredVehicleType: null,
    windowStart: null,
    windowEnd: null,
    driverId: null,
    assignedAt: null,
    createdAt: '2026-10-06T09:00:00Z',
    updatedAt: '2026-10-06T09:00:00Z',
    ...overrides,
  }
}

describe('Orders page', () => {
  it('shows a loading state while the list is in flight', () => {
    stubFetch({ '/api/orders': () => new Promise<Response>(() => {}) })
    renderWithProviders(<OrdersPage />)

    expect(screen.getByText(/Loading orders/)).toBeInTheDocument()
  })

  it('lists the orders the server returned', async () => {
    stubFetch({
      '/api/orders': () => json(page([order(), order({ id: 2, code: 'ORD-0002', status: 'DELIVERED', priority: 'URGENT' })])),
    })
    renderWithProviders(<OrdersPage />)

    expect(await screen.findByText('ORD-0001')).toBeInTheDocument()
    expect(screen.getByText('ORD-0002')).toBeInTheDocument()
    // Scoped to the table: the status and priority filters contain the same words as options.
    const table = within(screen.getByRole('table'))
    expect(table.getByText('Delivered')).toBeInTheDocument()
    expect(table.getByText('Urgent')).toBeInTheDocument()
  })

  it('says so when nothing matches instead of showing an empty table', async () => {
    stubFetch({ '/api/orders': () => json(page([])) })
    renderWithProviders(<OrdersPage />)

    expect(await screen.findByText('No orders match these filters')).toBeInTheDocument()
  })

  it('shows the server message and a retry when the request fails', async () => {
    stubFetch({
      '/api/orders': () => json({ status: 503, code: 'SERVICE_UNAVAILABLE', message: 'Database is unavailable' }, 503),
    })
    renderWithProviders(<OrdersPage />)

    expect(await screen.findByText('Database is unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('asks the server to filter, rather than filtering the page it already has', async () => {
    const calls = stubFetch({ '/api/orders': () => json(page([order()])) })
    renderWithProviders(<OrdersPage />)
    await screen.findByText('ORD-0001')

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'ASSIGNED')

    await waitFor(() => expect(calls.some((call) => call.url.includes('status=ASSIGNED'))).toBe(true))
  })

  it('offers dispatch and creation to staff only', async () => {
    stubFetch({ '/api/orders': () => json(page([order()])) })
    const { unmount } = renderWithProviders(<OrdersPage />, { user: testUser('DISPATCHER') })
    expect(await screen.findByRole('button', { name: 'New order' })).toBeInTheDocument()
    unmount()

    renderWithProviders(<OrdersPage />, { user: testUser('VIEWER') })
    await screen.findByText('ORD-0001')
    expect(screen.queryByRole('button', { name: 'New order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Auto-dispatch waiting' })).not.toBeInTheDocument()
  })

  it('reports what auto-dispatch did, without calling it optimal', async () => {
    stubFetch({
      '/api/orders': () => json(page([order()])),
      '/api/assignments/auto': () =>
        json({
          startedAt: '2026-10-06T09:00:00Z',
          waitingAtStart: 4,
          assignedCount: 3,
          notAssignedCount: 1,
          stillWaiting: 1,
          durationMillis: 42,
          assigned: [],
          notAssigned: [],
          algorithm: 'greedy [HEURISTIC]',
        }),
    })
    renderWithProviders(<OrdersPage />)
    await screen.findByText('ORD-0001')

    await userEvent.click(screen.getByRole('button', { name: 'Auto-dispatch waiting' }))

    expect(await screen.findByText(/Assigned 3 of 4 waiting orders/)).toBeInTheDocument()
    expect(screen.getByText(/not a proven best allocation/)).toBeInTheDocument()
  })

  it('shows the field errors the server rejected a new order with', async () => {
    stubFetch({
      '/api/orders': (init) =>
        init?.method === 'POST'
          ? json(
              {
                status: 400,
                code: 'VALIDATION_FAILED',
                message: 'Request validation failed',
                fieldErrors: [{ field: 'weightKg', message: 'must be greater than 0' }],
              },
              400,
            )
          : json(page([order()])),
      '/api/warehouses': () => json([{ id: 1, code: 'WH-1', name: 'Hub', address: 'x', latitude: 12.9, longitude: 77.5, active: true }]),
    })
    renderWithProviders(<OrdersPage />)
    await screen.findByText('ORD-0001')

    await userEvent.click(screen.getByRole('button', { name: 'New order' }))
    await userEvent.type(screen.getByLabelText('Customer name'), 'Fictional Customer')
    await userEvent.type(screen.getByLabelText('Drop address'), '2 Test Road')
    await userEvent.selectOptions(screen.getByLabelText('Pickup warehouse'), '1')
    await userEvent.clear(screen.getByLabelText('Weight (kg)'))
    await userEvent.type(screen.getByLabelText('Weight (kg)'), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Create order' }))

    expect(await screen.findByText('must be greater than 0')).toBeInTheDocument()
  })
})
