import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { DriversPage } from './DriversPage'
import type { DriverResponse } from '../api/types'
import { json, page, renderWithProviders, stubFetch, testUser } from '../test/render'

function driver(overrides: Partial<DriverResponse> = {}): DriverResponse {
  return {
    id: 3,
    code: 'DRV-003',
    fullName: 'Test Driver',
    phone: '+91 90000 00001',
    homeWarehouseId: 1,
    vehicleId: 2,
    status: 'AVAILABLE',
    currentLoadKg: '12.00',
    currentLoadM3: '0.80',
    activeDeliveryCount: 1,
    lastLatitude: 12.97,
    lastLongitude: 77.59,
    lastLocationAt: '2026-10-06T11:59:00Z',
    ...overrides,
  }
}

describe('Drivers page', () => {
  it('offers exactly the statuses the server defines', async () => {
    stubFetch({ '/api/drivers': () => json(page([driver()])) })
    renderWithProviders(<DriversPage />)
    await screen.findByText('DRV-003')

    const options = [...screen.getByLabelText('Status').querySelectorAll('option')].map((option) => option.value)
    // These four are the DriverStatus enum. A value that is not one of them is a 400 from the server.
    expect(options).toEqual(['', 'OFFLINE', 'AVAILABLE', 'ON_DELIVERY', 'ON_BREAK'])
  })

  it('filters on the server and shows the driver load', async () => {
    const calls = stubFetch({ '/api/drivers': () => json(page([driver()])) })
    renderWithProviders(<DriversPage />)
    await screen.findByText('DRV-003')
    expect(screen.getByText('12.00 kg · 0.80 m³')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'ON_DELIVERY')

    await waitFor(() => expect(calls.some((call) => call.url.includes('status=ON_DELIVERY'))).toBe(true))
  })

  it('says a driver has never reported a position rather than showing a blank', async () => {
    stubFetch({
      '/api/drivers': () => json(page([driver({ lastLatitude: null, lastLongitude: null, lastLocationAt: null })])),
    })
    renderWithProviders(<DriversPage />)

    expect(await screen.findByText('never reported')).toBeInTheDocument()
  })

  it('lets staff change a status but offers a viewer nothing to click', async () => {
    stubFetch({ '/api/drivers': () => json(page([driver()])) })
    const { unmount } = renderWithProviders(<DriversPage />, { user: testUser('DISPATCHER') })
    expect(await screen.findByLabelText('Status of DRV-003')).toBeInTheDocument()
    unmount()

    renderWithProviders(<DriversPage />, { user: testUser('VIEWER') })
    await screen.findByText('DRV-003')
    expect(screen.queryByLabelText('Status of DRV-003')).not.toBeInTheDocument()
  })
})
