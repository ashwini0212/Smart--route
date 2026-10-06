import { describe, expect, it } from 'vitest'
import { applyFrame, emptyLiveState } from './useLiveStream'
import type { LiveState } from './useLiveStream'
import type { LivePosition } from '../api/types'

function position(driverId: number, at: string, latitude = 12.97): LivePosition {
  return { driverId, latitude, longitude: 77.59, at, source: 'API' }
}

function envelope(type: string, aggregateId: string, payload: Record<string, unknown>, eventId = aggregateId) {
  return { eventId, type, aggregateType: 'order', aggregateId, occurredAt: '2026-10-06T10:00:00Z', payload }
}

describe('folding live frames into state', () => {
  it('records a driver position', () => {
    const state = applyFrame(emptyLiveState, 'driver-moved', position(4, '2026-10-06T10:00:00Z'))

    expect(state.positions.get(4)?.latitude).toBe(12.97)
    expect(state.framesReceived).toBe(1)
  })

  it('ignores a position older than the one already shown', () => {
    const newer = applyFrame(emptyLiveState, 'driver-moved', position(4, '2026-10-06T10:00:10Z', 13.1))
    const older = applyFrame(newer, 'driver-moved', position(4, '2026-10-06T10:00:00Z', 12.5))

    // A replay must not make the marker jump backwards.
    expect(older.positions.get(4)?.latitude).toBe(13.1)
    expect(older.framesReceived).toBe(2)
  })

  it('accepts a newer position for the same driver', () => {
    const first = applyFrame(emptyLiveState, 'driver-moved', position(4, '2026-10-06T10:00:00Z', 12.5))
    const second = applyFrame(first, 'driver-moved', position(4, '2026-10-06T10:00:10Z', 13.1))

    expect(second.positions.get(4)?.latitude).toBe(13.1)
  })

  it('keeps one delay alert per order, newest first', () => {
    const first = applyFrame(emptyLiveState, 'delivery-delayed', envelope('DELIVERY_DELAYED', '77', { lateBySeconds: 120 }, 'e1'))
    const second = applyFrame(first, 'delivery-delayed', envelope('DELIVERY_DELAYED', '77', { lateBySeconds: 600 }, 'e2'))
    const other = applyFrame(second, 'delivery-delayed', envelope('DELIVERY_DELAYED', '78', { lateBySeconds: 90 }, 'e3'))

    expect(other.alerts.map((alert) => alert.aggregateId)).toEqual(['78', '77'])
    expect(other.alerts.find((alert) => alert.aggregateId === '77')?.payload.lateBySeconds).toBe(600)
  })

  it('caps the notice lists so a dashboard left open does not grow without limit', () => {
    let state: LiveState = emptyLiveState
    for (let index = 0; index < 60; index++) {
      state = applyFrame(state, 'route-recalculated', envelope('ROUTE_RECALCULATED', String(index), {}, `e${index}`))
    }

    expect(state.recalculations).toHaveLength(40)
    expect(state.recalculations[0].aggregateId).toBe('59')
  })

  it('treats the greeting and the heartbeat as proof the stream is open', () => {
    expect(applyFrame(emptyLiveState, 'hello', 'connected').connection).toBe('open')
    expect(applyFrame(emptyLiveState, 'heartbeat', '1').connection).toBe('open')
  })

  it('counts an unknown event without changing anything else', () => {
    const state = applyFrame(emptyLiveState, 'something-new', { a: 1 })

    expect(state.framesReceived).toBe(1)
    expect(state.positions.size).toBe(0)
    expect(state.alerts).toHaveLength(0)
  })

  it('ignores a frame that is not shaped the way the server sends it', () => {
    const state = applyFrame(emptyLiveState, 'delivery-delayed', 'not an envelope')
    expect(state.alerts).toHaveLength(0)

    const noId = applyFrame(emptyLiveState, 'driver-moved', { latitude: 1, longitude: 2 })
    expect(noId.positions.size).toBe(0)
  })
})
