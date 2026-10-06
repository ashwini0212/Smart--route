import { query, request } from './client'
import type {
  AssignmentResponse,
  AssignmentSettings,
  AutoDispatchResult,
  CandidateRanking,
  DriverResponse,
  DriverStatus,
  LivePosition,
  NetworkResponse,
  OptimizedRoute,
  OrderPriority,
  OrderResponse,
  OrderStatus,
  OrderStatusChangeResponse,
  OutboxStatus,
  PageResponse,
  Role,
  RouteMode,
  RouteResponse,
  SimulationStatus,
  SystemEventResponse,
  TokenResponse,
  UserResponse,
  VehicleResponse,
  VehicleType,
  WarehouseResponse,
} from './types'

/** One function per endpoint the dashboard uses, so a page never builds a URL itself. */

export const auth = {
  login: (email: string, password: string) =>
    request<TokenResponse>('/api/auth/login', { method: 'POST', body: { email, password } }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  me: () => request<UserResponse>('/api/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/auth/password', { method: 'PUT', body: { currentPassword, newPassword } }),
}

export interface OrderSearchParams {
  status?: OrderStatus | ''
  priority?: OrderPriority | ''
  warehouseId?: number | ''
  driverId?: number | ''
  page?: number
  size?: number
}

export interface CreateOrderRequest {
  warehouseId: number
  customerName: string
  dropAddress: string
  dropLatitude: number
  dropLongitude: number
  priority: OrderPriority
  weightKg: number
  volumeM3: number
  requiredVehicleType?: VehicleType | null
  windowStart?: string | null
  windowEnd?: string | null
}

export const orders = {
  search: (params: OrderSearchParams) =>
    request<PageResponse<OrderResponse>>(`/api/orders${query({ ...params })}`),
  byId: (id: number) => request<OrderResponse>(`/api/orders/${id}`),
  history: (id: number) => request<OrderStatusChangeResponse[]>(`/api/orders/${id}/history`),
  create: (body: CreateOrderRequest) => request<OrderResponse>('/api/orders', { method: 'POST', body }),
  cancel: (id: number, reason: string) =>
    request<OrderResponse>(`/api/orders/${id}/cancel`, { method: 'POST', body: { reason } }),
  unassign: (id: number, reason: string) =>
    request<OrderResponse>(`/api/orders/${id}/unassign`, { method: 'POST', body: { reason } }),
}

export const fleet = {
  drivers: (params: { status?: DriverStatus | ''; page?: number; size?: number }) =>
    request<PageResponse<DriverResponse>>(`/api/drivers${query({ ...params })}`),
  driver: (id: number) => request<DriverResponse>(`/api/drivers/${id}`),
  setDriverStatus: (id: number, status: DriverStatus) =>
    request<DriverResponse>(`/api/drivers/${id}/status${query({ status })}`, { method: 'PUT' }),
  vehicles: (params: { type?: VehicleType | ''; page?: number; size?: number }) =>
    request<PageResponse<VehicleResponse>>(`/api/vehicles${query({ ...params })}`),
}

export const warehouses = {
  all: () => request<WarehouseResponse[]>('/api/warehouses'),
}

export const assignment = {
  candidates: (orderId: number, k = 5) =>
    request<CandidateRanking>(`/api/assignments/candidates${query({ orderId, k })}`),
  assign: (orderId: number, driverId: number, reason?: string) =>
    request<AssignmentResponse>('/api/assignments', { method: 'POST', body: { orderId, driverId, reason } }),
  auto: (limit?: number) => request<AutoDispatchResult>(`/api/assignments/auto${query({ limit })}`, { method: 'POST' }),
  settings: () => request<AssignmentSettings>('/api/admin/assignment-config'),
  updateSettings: (body: Omit<AssignmentSettings, 'updatedAt'>) =>
    request<AssignmentSettings>('/api/admin/assignment-config', { method: 'PUT', body }),
}

export interface GeoPointRequest {
  latitude: number
  longitude: number
}

export interface RouteRequest {
  from: GeoPointRequest
  to: GeoPointRequest
}

export interface OptimizeRequest {
  start: GeoPointRequest
  stops: {
    label?: string
    location: GeoPointRequest
    weightKg?: number
    volumeM3?: number
    dueBy?: string | null
    serviceMinutes?: number
  }[]
  mode?: RouteMode
  /** Absent means AUTO: exact up to the configured stop count, the heuristic above it. */
  strategy?: 'EXACT' | 'HEURISTIC'
  returnToStart?: boolean
  departAt?: string
  capacityKg?: number
  capacityM3?: number
}

export const routing = {
  fastest: (body: RouteRequest) => request<RouteResponse>('/api/routes/fastest', { method: 'POST', body }),
  shortest: (body: RouteRequest) => request<RouteResponse>('/api/routes/shortest', { method: 'POST', body }),
  optimize: (body: OptimizeRequest) => request<OptimizedRoute>('/api/routes/optimize', { method: 'POST', body }),
  network: () => request<NetworkResponse>('/api/routing/network'),
}

export const deliveries = {
  mine: () => request<OrderResponse[]>('/api/deliveries/mine'),
  myRoute: () => request<OptimizedRoute>('/api/deliveries/mine/route'),
  routeFor: (driverId: number) => request<OptimizedRoute>(`/api/deliveries/route${query({ driverId })}`),
  setStatus: (orderId: number, status: OrderStatus, reason?: string) =>
    request<OrderResponse>(`/api/deliveries/${orderId}/status`, { method: 'PUT', body: { status, reason } }),
}

export const tracking = {
  positions: () => request<LivePosition[]>('/api/tracking/drivers'),
  position: (driverId: number) => request<LivePosition>(`/api/tracking/drivers/${driverId}`),
  connectedClients: () => request<{ clients: number }>('/api/tracking/stream/clients'),
  sweep: () => request<Record<string, number>>('/api/tracking/sweep', { method: 'POST' }),
  rebuild: () => request<{ positions: number }>('/api/tracking/positions/rebuild', { method: 'POST' }),
}

export const events = {
  search: (params: { eventType?: string; aggregateType?: string; aggregateId?: string; page?: number; size?: number }) =>
    request<PageResponse<SystemEventResponse>>(`/api/events${query({ ...params })}`),
  outbox: () => request<OutboxStatus>('/api/events/outbox'),
}

export const simulation = {
  status: () => request<SimulationStatus>('/api/simulation/status'),
}

export const admin = {
  users: (params: { page?: number; size?: number }) =>
    request<PageResponse<UserResponse>>(`/api/admin/users${query({ ...params })}`),
  setRole: (id: number, role: Role) =>
    request<UserResponse>(`/api/admin/users/${id}/role${query({ role })}`, { method: 'PUT' }),
  setEnabled: (id: number, enabled: boolean) =>
    request<UserResponse>(`/api/admin/users/${id}/enabled${query({ enabled })}`, { method: 'PUT' }),
}
