import type { DriverStatus, OrderPriority, OrderStatus, VehicleStatus } from '../api/types'

/** Formatting the whole dashboard shares, so two pages never show the same value in two different ways. */

export function metres(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)} km` : `${Math.round(value)} m`
}

export function seconds(value: number): string {
  if (value < 60) return `${Math.round(value)} s`
  const minutes = Math.round(value / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  return `${hours} h ${minutes % 60} min`
}

export function clockTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })
}

export function relative(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'never'
  const delta = Math.round((now - Date.parse(iso)) / 1000)
  if (delta < 5) return 'just now'
  if (delta < 60) return `${delta} s ago`
  if (delta < 3600) return `${Math.round(delta / 60)} min ago`
  if (delta < 86_400) return `${Math.round(delta / 3600)} h ago`
  return `${Math.round(delta / 86_400)} d ago`
}

export function coordinates(latitude: number, longitude: number): string {
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`
}

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export function orderStatusTone(status: OrderStatus): Tone {
  switch (status) {
    case 'DELIVERED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'neutral'
    case 'CREATED':
      return 'warning'
    default:
      return 'info'
  }
}

export function priorityTone(priority: OrderPriority): Tone {
  switch (priority) {
    case 'URGENT':
      return 'danger'
    case 'HIGH':
      return 'warning'
    case 'LOW':
      return 'neutral'
    default:
      return 'info'
  }
}

export function driverStatusTone(status: DriverStatus): Tone {
  switch (status) {
    case 'AVAILABLE':
      return 'success'
    case 'BUSY':
      return 'info'
    case 'OFFLINE':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function vehicleStatusTone(status: VehicleStatus): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'MAINTENANCE':
      return 'warning'
    default:
      return 'neutral'
  }
}

/** Turns SCREAMING_SNAKE_CASE into something readable, which is most of what the API returns. */
export function humanize(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ')
}
