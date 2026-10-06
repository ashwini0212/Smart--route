import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AnalyticsPage } from './AnalyticsPage'
import { json, renderWithProviders, stubFetch, testUser } from '../test/render'

const OVERVIEW = {
  from: '2026-09-29T12:00:00Z',
  to: '2026-10-06T12:00:00Z',
  days: 7,
  ordersByStatus: { CREATED: 4, DELIVERED: 12 },
  created: 16,
  delivered: 12,
  failed: 1,
  cancelled: 0,
  waitingNow: 4,
  activeNow: 3,
  deliveredWithWindow: 10,
  onTime: 8,
  late: 2,
  onTimeRate: 0.8,
  assignedToDeliveredMinutes: { samples: 12, p50: 48.5, p90: 95.0, mean: 55.2 },
  definitions: ['On time means delivered at or before the window end, counted only over deliveries that had a window.'],
}

const THROUGHPUT = [
  { day: '2026-10-05', created: 6, delivered: 4, failed: 0, cancelled: 0 },
  { day: '2026-10-06', created: 10, delivered: 8, failed: 1, cancelled: 0 },
]

const FLEET = {
  driverCount: 4,
  driversWithDeliveries: 2,
  shareOfFleetUsed: 0.5,
  deliveriesPerActiveDriver: 6,
  perDriver: [
    { driverId: 1, driverCode: 'DRV-001', status: 'AVAILABLE', delivered: 7, late: 2, failed: 0, activeNow: 1, medianMinutes: 42.5 },
    { driverId: 2, driverCode: 'DRV-002', status: 'ON_BREAK', delivered: 5, late: 0, failed: 1, activeNow: 0, medianMinutes: null },
  ],
  definitions: ['Share of fleet used is driversWithDeliveries / driverCount. It is not a share of anyone\'s time.'],
}

const ETA = {
  samples: 9,
  predictedMedianMinutes: 11.5,
  actualMedianMinutes: 26.0,
  medianDifferenceMinutes: 14.5,
  p90DifferenceMinutes: 40.0,
  withinFiveMinutes: 2,
  definitions: ['Actual includes finishing earlier deliveries, so the difference is an upper bound on the routing error.'],
}

function stubAll(overrides: Partial<Record<string, unknown>> = {}) {
  return stubFetch({
    '/api/analytics/overview': () => json(overrides.overview ?? OVERVIEW),
    '/api/analytics/throughput': () => json(overrides.throughput ?? THROUGHPUT),
    '/api/analytics/fleet': () => json(overrides.fleet ?? FLEET),
    '/api/analytics/eta-accuracy': () => json(overrides.eta ?? ETA),
  })
}

describe('Analytics page', () => {
  it('shows the headline numbers with what they are counted over', async () => {
    stubAll()
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByText('80.0%')).toBeInTheDocument()
    // The rate alone is not enough: the denominator is the deliveries that had a deadline at all.
    expect(screen.getByText('8 of 10 deliveries that had a window')).toBeInTheDocument()
    expect(screen.getByText('48.5 min')).toBeInTheDocument()
    expect(screen.getByText(/90th percentile 95 min over 12 deliveries/)).toBeInTheDocument()
  })

  it('prints the definitions the server computed the numbers with', async () => {
    stubAll()
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByText(/counted only over deliveries that had a window/)).toBeInTheDocument()
    expect(screen.getByText(/not a share of anyone's time/)).toBeInTheDocument()
    expect(screen.getByText(/upper bound on the routing error/)).toBeInTheDocument()
  })

  it('says "no data" rather than 0% when nothing could be judged', async () => {
    stubAll({
      overview: {
        ...OVERVIEW,
        delivered: 0,
        deliveredWithWindow: 0,
        onTime: 0,
        late: 0,
        onTimeRate: null,
        assignedToDeliveredMinutes: { samples: 0, p50: null, p90: null, mean: null },
      },
    })
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findAllByText('no data')).toHaveLength(2)
    expect(screen.getByText('Nothing delivered in this window had a delivery window')).toBeInTheDocument()
  })

  it('draws a chart with an axis at zero and names the tallest bar', async () => {
    stubAll()
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByRole('img', { name: 'Orders created and delivered per day' })).toBeInTheDocument()
    expect(screen.getByText('axis starts at zero; tallest bar is 10')).toBeInTheDocument()
  })

  it('shows an empty chart state instead of a flat line', async () => {
    stubAll({ throughput: [{ day: '2026-10-06', created: 0, delivered: 0, failed: 0, cancelled: 0 }] })
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByText('Nothing was created or delivered in this window')).toBeInTheDocument()
  })

  it('lists what each driver did, with a missing median shown as missing', async () => {
    stubAll()
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByText('DRV-001')).toBeInTheDocument()
    expect(screen.getByText('42.5 min')).toBeInTheDocument()
    expect(screen.getByText('On break')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('asks the server for a different window when one is chosen', async () => {
    const calls = stubAll()
    renderWithProviders(<AnalyticsPage />)
    await screen.findByText('80.0%')

    await userEvent.selectOptions(screen.getByLabelText('Window'), '30')

    await waitFor(() => expect(calls.some((call) => call.url.includes('/api/analytics/overview?days=30'))).toBe(true))
    expect(calls.some((call) => call.url.includes('/api/analytics/fleet?days=30'))).toBe(true)
  })

  it('says there is nothing to compare rather than showing an empty ETA table', async () => {
    stubAll({
      eta: { samples: 0, predictedMedianMinutes: null, actualMedianMinutes: null, medianDifferenceMinutes: null, p90DifferenceMinutes: null, withinFiveMinutes: 0, definitions: [] },
    })
    renderWithProviders(<AnalyticsPage />)

    expect(await screen.findByText('No pickups in this window to compare')).toBeInTheDocument()
  })

  it('is available to a viewer, who is who this page is for', async () => {
    stubAll()
    renderWithProviders(<AnalyticsPage />, { user: testUser('VIEWER') })

    expect(await screen.findByText('80.0%')).toBeInTheDocument()
  })
})
