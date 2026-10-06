import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { OrderDetailPage } from './OrderDetailPage'
import { json, renderWithProviders, stubFetch, testUser } from '../test/render'

const ORDER = {
  id: 9,
  code: 'ORD-0009',
  warehouseId: 1,
  customerName: 'Fictional Customer',
  dropAddress: '3 Test Lane',
  dropLatitude: 12.97,
  dropLongitude: 77.59,
  priority: 'HIGH',
  status: 'CREATED',
  weightKg: '12.00',
  volumeM3: '0.60',
  requiredVehicleType: null,
  windowStart: null,
  windowEnd: null,
  driverId: null,
  assignedAt: null,
  createdAt: '2026-10-06T09:00:00Z',
  updatedAt: '2026-10-06T09:00:00Z',
}

const RANKING = {
  orderId: 9,
  orderCode: 'ORD-0009',
  pickupLatitude: 12.9,
  pickupLongitude: 77.5,
  driversInRadius: 12,
  eligible: 3,
  excluded: { CAPACITY: 5, OFF_SHIFT: 4 },
  candidates: [
    {
      rank: 1,
      driverId: 31,
      driverCode: 'DRV-031',
      vehicleType: 'VAN',
      etaSeconds: 420,
      straightLineMeters: 2300,
      activeDeliveries: 1,
      remainingKg: '480.00',
      remainingM3: '7.50',
      score: 0.812,
      etaScore: 0.91,
      workloadScore: 0.75,
      capacityScore: 0.68,
    },
  ],
  algorithm: 'weighted score (ETA, workload, capacity fit) [HEURISTIC]',
}

function renderPage(routes: Parameters<typeof stubFetch>[0], role: 'ADMIN' | 'DISPATCHER' | 'VIEWER' = 'DISPATCHER') {
  const calls = stubFetch(routes)
  renderWithProviders(
    <Routes>
      <Route path="/orders/:id" element={<OrderDetailPage />} />
    </Routes>,
    { route: '/orders/9', user: testUser(role) },
  )
  return calls
}

describe('Order detail page', () => {
  it('shows the order and its history', async () => {
    renderPage({
      '/api/orders/9/history': () =>
        json([{ fromStatus: null, toStatus: 'CREATED', reason: 'Order created', changedAt: '2026-10-06T09:00:00Z' }]),
      '/api/orders/9': () => json(ORDER),
    })

    expect(await screen.findByText('ORD-0009')).toBeInTheDocument()
    expect(screen.getByText(/3 Test Lane/)).toBeInTheDocument()
    expect(await screen.findByText('Order created')).toBeInTheDocument()
  })

  it('ranks candidates only when asked, and shows the score breakdown', async () => {
    const calls = renderPage({
      '/api/orders/9/history': () => json([]),
      '/api/orders/9': () => json(ORDER),
      '/api/assignments/candidates': () => json(RANKING),
    })
    await screen.findByText('ORD-0009')
    // Ranking runs a Dijkstra per request, so it is not done on page load.
    expect(calls.some((call) => call.url.includes('candidates'))).toBe(false)

    await userEvent.click(screen.getByRole('button', { name: 'Find drivers' }))

    expect(await screen.findByText('DRV-031')).toBeInTheDocument()
    expect(screen.getByText('0.812')).toBeInTheDocument()
    expect(screen.getByText(/eta 0.91 · load 0.75 · fit 0.68/)).toBeInTheDocument()
    expect(screen.getByText(/HEURISTIC/)).toBeInTheDocument()
  })

  it('explains what ruled everyone out when there is no eligible driver', async () => {
    renderPage({
      '/api/orders/9/history': () => json([]),
      '/api/orders/9': () => json(ORDER),
      '/api/assignments/candidates': () => json({ ...RANKING, candidates: [], eligible: 0 }),
    })
    await screen.findByText('ORD-0009')

    await userEvent.click(screen.getByRole('button', { name: 'Find drivers' }))

    expect(await screen.findByText('No eligible driver')).toBeInTheDocument()
    expect(screen.getByText(/5 capacity/)).toBeInTheDocument()
  })

  it('assigns the chosen driver and reloads the order', async () => {
    let status = 'CREATED'
    const calls = renderPage({
      '/api/orders/9/history': () => json([]),
      '/api/orders/9': () => json({ ...ORDER, status, driverId: status === 'ASSIGNED' ? 31 : null }),
      '/api/assignments/candidates': () => json(RANKING),
      '/api/assignments': () => {
        status = 'ASSIGNED'
        return json({ id: 5, orderId: 9, driverId: 31, method: 'MANUAL', createdAt: '2026-10-06T09:05:00Z' }, 201)
      },
    })
    await screen.findByText('ORD-0009')
    await userEvent.click(screen.getByRole('button', { name: 'Find drivers' }))
    await screen.findByText('DRV-031')

    await userEvent.click(screen.getByRole('button', { name: 'Assign' }))

    await waitFor(() => expect(screen.getByText('Assigned')).toBeInTheDocument())
    const posted = calls.find((call) => call.url === '/api/assignments' && call.init?.method === 'POST')
    expect(JSON.parse(String(posted?.init?.body))).toMatchObject({ orderId: 9, driverId: 31 })
  })

  it('shows the server error when an assignment is refused', async () => {
    renderPage({
      '/api/orders/9/history': () => json([]),
      '/api/orders/9': () => json(ORDER),
      '/api/assignments/candidates': () => json(RANKING),
      '/api/assignments': () =>
        json({ status: 409, code: 'ORDER_ALREADY_ASSIGNED', message: 'This order was assigned by someone else' }, 409),
    })
    await screen.findByText('ORD-0009')
    await userEvent.click(screen.getByRole('button', { name: 'Find drivers' }))
    await screen.findByText('DRV-031')

    await userEvent.click(screen.getByRole('button', { name: 'Assign' }))

    expect(await screen.findByText('This order was assigned by someone else')).toBeInTheDocument()
  })

  it('offers no assignment controls to a viewer', async () => {
    renderPage(
      {
        '/api/orders/9/history': () => json([]),
        '/api/orders/9': () => json(ORDER),
      },
      'VIEWER',
    )
    await screen.findByText('ORD-0009')

    expect(screen.queryByRole('button', { name: 'Find drivers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })
})
