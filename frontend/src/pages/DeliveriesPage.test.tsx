import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { DeliveriesPage } from './DeliveriesPage'
import { json, renderWithProviders, stubFetch, testUser } from '../test/render'

const DELIVERY = {
  id: 4,
  code: 'ORD-0004',
  warehouseId: 1,
  customerName: 'Fictional Customer',
  dropAddress: '7 Test Avenue',
  dropLatitude: 12.97,
  dropLongitude: 77.59,
  priority: 'NORMAL',
  status: 'ASSIGNED',
  weightKg: '8.00',
  volumeM3: '0.40',
  requiredVehicleType: null,
  windowStart: null,
  windowEnd: '2026-10-06T12:00:00Z',
  driverId: 7,
  assignedAt: '2026-10-06T09:10:00Z',
  createdAt: '2026-10-06T09:00:00Z',
  updatedAt: '2026-10-06T09:10:00Z',
}

const ROUTE = {
  mode: 'FASTEST',
  returnToStart: false,
  departAt: '2026-10-06T10:00:00Z',
  totalDistanceMeters: 8200,
  totalDurationSeconds: 1500,
  serviceSeconds: 240,
  finishAt: '2026-10-06T10:29:00Z',
  algorithm: 'nearest neighbour + 2-opt [HEURISTIC]',
  optimal: false,
  algorithmSteps: 12,
  sequencingMillis: 1,
  stopCount: 1,
  visits: [
    {
      sequence: 1,
      stopIndex: 0,
      label: 'ORD-0004',
      latitude: 12.97,
      longitude: 77.59,
      arriveAt: '2026-10-06T10:25:00Z',
      departAt: '2026-10-06T10:29:00Z',
      snapDistanceMeters: 12,
    },
  ],
  legs: [],
  lateStops: [],
  comparedTo: null,
  graphVersion: 3,
}

describe('Driver deliveries page', () => {
  it('shows the assigned deliveries and the suggested order', async () => {
    stubFetch({
      '/api/deliveries/mine/route': () => json(ROUTE),
      '/api/deliveries/mine': () => json([DELIVERY]),
    })
    renderWithProviders(<DeliveriesPage />, { user: testUser('DRIVER') })

    expect(await screen.findByText('ORD-0004')).toBeInTheDocument()
    expect(screen.getByText('7 Test Avenue')).toBeInTheDocument()
    // The heuristic order must not be presented as the best possible one.
    expect(await screen.findByText(/not a proven best one/)).toBeInTheDocument()
  })

  it('offers only the transitions the server accepts from the current status', async () => {
    stubFetch({
      '/api/deliveries/mine/route': () => json(ROUTE),
      '/api/deliveries/mine': () => json([DELIVERY]),
    })
    renderWithProviders(<DeliveriesPage />, { user: testUser('DRIVER') })
    await screen.findByText('ORD-0004')

    expect(screen.getByRole('button', { name: 'Picked up' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delivered' })).not.toBeInTheDocument()
  })

  it('reports a status without sending a driver id', async () => {
    let status = 'PICKED_UP'
    const calls = stubFetch({
      '/api/deliveries/mine/route': () => json(ROUTE),
      '/api/deliveries/mine': () => json([{ ...DELIVERY, status: status === 'PICKED_UP' ? 'ASSIGNED' : status }]),
      '/api/deliveries/4/status': () => {
        status = 'IN_TRANSIT'
        return json({ ...DELIVERY, status: 'PICKED_UP' })
      },
    })
    renderWithProviders(<DeliveriesPage />, { user: testUser('DRIVER') })
    await screen.findByText('ORD-0004')

    await userEvent.click(screen.getByRole('button', { name: 'Picked up' }))

    await waitFor(() => expect(calls.some((call) => call.url === '/api/deliveries/4/status')).toBe(true))
    const put = calls.find((call) => call.url === '/api/deliveries/4/status')
    // The driver comes from the signed token; a body carrying one would be a way to act for someone else.
    expect(JSON.parse(String(put?.init?.body))).toEqual({ status: 'PICKED_UP' })
  })

  it('says there is nothing assigned rather than showing an empty route', async () => {
    const calls = stubFetch({ '/api/deliveries/mine': () => json([]) })
    renderWithProviders(<DeliveriesPage />, { user: testUser('DRIVER') })

    expect(await screen.findByText('Nothing assigned to you right now')).toBeInTheDocument()
    expect(calls.some((call) => call.url.includes('/route'))).toBe(false)
  })

  it('marks a stop the server predicts will be late', async () => {
    stubFetch({
      '/api/deliveries/mine/route': () =>
        json({
          ...ROUTE,
          lateStops: [
            { stopIndex: 0, label: 'ORD-0004', dueBy: '2026-10-06T10:00:00Z', arriveAt: '2026-10-06T10:25:00Z', lateBySeconds: 1500 },
          ],
        }),
      '/api/deliveries/mine': () => json([DELIVERY]),
    })
    renderWithProviders(<DeliveriesPage />, { user: testUser('DRIVER') })

    expect(await screen.findByText('predicted 25 min late')).toBeInTheDocument()
  })
})
