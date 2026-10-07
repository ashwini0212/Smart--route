import { useEffect, useRef, useState } from 'react'
import { getAccessToken, refreshAccessToken } from '../api/client'
import { readEventStream } from '../api/sse'
import type { LivePosition } from '../api/types'

export type ConnectionState = 'connecting' | 'open' | 'retrying'

/**
 * Every frame except `driver-moved` arrives in the same envelope shape the server sends
 * (`{eventId, type, aggregateType, aggregateId, occurredAt, payload}`), so one type covers them all.
 */
export interface EventFrame {
  eventId: string
  type: string
  aggregateType: string
  aggregateId: string
  occurredAt: string
  payload: Record<string, unknown>
  receivedAt: number
}

export interface LiveState {
  connection: ConnectionState
  /** Latest position per driver, keyed by driver id. */
  positions: Map<number, LivePosition>
  /** Deliveries predicted to miss their window, newest first, one entry per order. */
  alerts: EventFrame[]
  recalculations: EventFrame[]
  statusChanges: EventFrame[]
  framesReceived: number
  lastFrameAt: number | null
}

const KEEP = 40

export const emptyLiveState: LiveState = {
  connection: 'connecting',
  positions: new Map(),
  alerts: [],
  recalculations: [],
  statusChanges: [],
  framesReceived: 0,
  lastFrameAt: null,
}

function envelope(payload: unknown, now: number): EventFrame | null {
  const frame = payload as Partial<EventFrame> | null
  if (!frame || typeof frame !== 'object' || !frame.payload) return null
  return {
    eventId: frame.eventId ?? '',
    type: frame.type ?? 'UNKNOWN',
    aggregateType: frame.aggregateType ?? '',
    aggregateId: frame.aggregateId ?? '',
    occurredAt: frame.occurredAt ?? new Date(now).toISOString(),
    payload: frame.payload as Record<string, unknown>,
    receivedAt: now,
  }
}

/**
 * Folds one frame into the state.
 *
 * Kept as a pure function, separate from the connection, because this is the part with rules in it: a position
 * older than the one on screen is dropped (the server guards this too, but after a reconnection a client that
 * trusts arrival order will still flicker), and the alert, recalculation and status lists are capped so a
 * dashboard left open overnight does not grow without limit. The position map is not capped: it holds one
 * entry per driver the server has reported, so it is bounded by the fleet rather than by how long the page
 * has been open.
 */
export function applyFrame(state: LiveState, event: string, payload: unknown): LiveState {
  const now = Date.now()
  const next: LiveState = { ...state, framesReceived: state.framesReceived + 1, lastFrameAt: now }
  switch (event) {
    case 'hello':
    case 'heartbeat':
      return { ...next, connection: 'open' }
    case 'driver-moved': {
      const position = payload as LivePosition
      if (typeof position?.driverId !== 'number') return next
      const known = state.positions.get(position.driverId)
      if (known && Date.parse(known.at) > Date.parse(position.at)) return next
      const positions = new Map(state.positions)
      positions.set(position.driverId, position)
      return { ...next, positions }
    }
    case 'delivery-delayed': {
      const frame = envelope(payload, now)
      if (!frame) return next
      const others = state.alerts.filter((a) => a.aggregateId !== frame.aggregateId)
      return { ...next, alerts: [frame, ...others].slice(0, KEEP) }
    }
    case 'route-recalculated': {
      const frame = envelope(payload, now)
      if (!frame) return next
      return { ...next, recalculations: [frame, ...state.recalculations].slice(0, KEEP) }
    }
    case 'order-status': {
      const frame = envelope(payload, now)
      if (!frame) return next
      return { ...next, statusChanges: [frame, ...state.statusChanges].slice(0, KEEP) }
    }
    default:
      return next
  }
}

/**
 * Subscribes to the tracking stream for as long as the component is mounted.
 *
 * Reconnects with a growing delay, capped at 30 s, because the server drops clients it cannot write to: a
 * dropped client reconnecting immediately in a loop would be the thing keeping it from catching up.
 */
export function useLiveStream(enabled = true): LiveState {
  const [state, setState] = useState<LiveState>(emptyLiveState)
  const attempt = useRef(0)

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false

    const connect = async () => {
      try {
        setState((s) => ({ ...s, connection: attempt.current === 0 ? 'connecting' : 'retrying' }))
        await readEventStream('/api/tracking/stream', {
          signal: controller.signal,
          token: getAccessToken() ?? (await refreshAccessToken()),
          onOpen: () => {
            attempt.current = 0
            setState((s) => ({ ...s, connection: 'open' }))
          },
          onFrame: (frame) => {
            let payload: unknown = frame.data
            try {
              payload = JSON.parse(frame.data)
            } catch {
              // The greeting is plain text, not JSON; it is still worth counting as a frame.
            }
            setState((s) => applyFrame(s, frame.event, payload))
          },
        })
      } catch {
        // Falls through to the retry below. The user sees "reconnecting", not a stack trace.
      }
      if (stopped) return
      setState((s) => ({ ...s, connection: 'retrying' }))
      attempt.current += 1
      const delay = Math.min(1000 * 2 ** (attempt.current - 1), 30_000)
      timer = setTimeout(connect, delay)
    }

    void connect()
    return () => {
      stopped = true
      controller.abort()
      if (timer) clearTimeout(timer)
    }
  }, [enabled])

  return state
}
