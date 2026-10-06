import { describe, expect, it } from 'vitest'
import { coordinates, humanize, metres, relative, seconds } from './format'

describe('formatting', () => {
  it('switches from metres to kilometres where a reader would', () => {
    expect(metres(430)).toBe('430 m')
    expect(metres(999)).toBe('999 m')
    expect(metres(1000)).toBe('1.0 km')
    expect(metres(8243)).toBe('8.2 km')
  })

  it('writes durations the way a dispatcher says them', () => {
    expect(seconds(45)).toBe('45 s')
    expect(seconds(600)).toBe('10 min')
    expect(seconds(5400)).toBe('1 h 30 min')
  })

  it('describes how long ago something happened', () => {
    const now = Date.parse('2026-10-06T12:00:00Z')
    expect(relative('2026-10-06T11:59:58Z', now)).toBe('just now')
    expect(relative('2026-10-06T11:59:30Z', now)).toBe('30 s ago')
    expect(relative('2026-10-06T11:30:00Z', now)).toBe('30 min ago')
    expect(relative('2026-10-06T09:00:00Z', now)).toBe('3 h ago')
    expect(relative(null, now)).toBe('never')
  })

  it('keeps five decimal places of a coordinate, which is about a metre', () => {
    expect(coordinates(12.971598, 77.594562)).toBe('12.97160, 77.59456')
  })

  it('turns the API enums into words', () => {
    expect(humanize('IN_TRANSIT')).toBe('In transit')
    expect(humanize('OFF_SHIFT')).toBe('Off shift')
    expect(humanize('VAN')).toBe('Van')
  })
})
