/**
 * The shapes the backend actually returns, written out by hand from the Java records.
 *
 * Hand-written rather than generated from the OpenAPI document: the generator would be another build step
 * for a surface this small, and writing them out forces a read of the contract. The cost is that a backend
 * change can drift from this file silently, which is why the API client validates nothing — a missing field
 * shows up as undefined in the page, not as a crash deep in a parser.
 */

export type Role = 'ADMIN' | 'DISPATCHER' | 'DRIVER' | 'VIEWER'

export type OrderStatus =
  | 'CREATED'
  | 'ASSIGNED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED'

export type OrderPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type VehicleType = 'BIKE' | 'VAN' | 'TRUCK'
export type VehicleStatus = 'ACTIVE' | 'MAINTENANCE' | 'RETIRED'
export type DriverStatus = 'OFFLINE' | 'AVAILABLE' | 'ON_DELIVERY' | 'ON_BREAK'
export type RouteMode = 'SHORTEST' | 'FASTEST'
export type LocationSource = 'API' | 'SIMULATION'

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/**
 * A page of a list the server does not count. The event log is append-only and large, so it returns
 * `hasNext` instead of a total: see SliceResponse.java.
 */
export interface SliceResponse<T> {
  content: T[]
  page: number
  size: number
  hasNext: boolean
}

export interface UserResponse {
  id: number
  email: string
  fullName: string
  role: Role
  driverId: number | null
  enabled: boolean
  lastLoginAt: string | null
  createdAt: string
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: UserResponse
}

export interface WarehouseResponse {
  id: number
  code: string
  name: string
  address: string
  latitude: number
  longitude: number
  active: boolean
}

export interface VehicleResponse {
  id: number
  plateNumber: string
  type: VehicleType
  maxWeightKg: string
  maxVolumeM3: string
  status: VehicleStatus
}

export interface DriverResponse {
  id: number
  code: string
  fullName: string
  phone: string | null
  homeWarehouseId: number | null
  vehicleId: number | null
  status: DriverStatus
  currentLoadKg: string
  currentLoadM3: string
  activeDeliveryCount: number
  lastLatitude: number | null
  lastLongitude: number | null
  lastLocationAt: string | null
}

export interface OrderResponse {
  id: number
  code: string
  warehouseId: number
  customerName: string
  dropAddress: string
  dropLatitude: number
  dropLongitude: number
  priority: OrderPriority
  status: OrderStatus
  weightKg: string
  volumeM3: string
  requiredVehicleType: VehicleType | null
  windowStart: string | null
  windowEnd: string | null
  driverId: number | null
  assignedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface OrderStatusChangeResponse {
  fromStatus: OrderStatus | null
  toStatus: OrderStatus
  reason: string | null
  changedAt: string
}

export interface Candidate {
  rank: number
  driverId: number
  driverCode: string
  vehicleType: VehicleType
  etaSeconds: number
  straightLineMeters: number
  activeDeliveries: number
  remainingKg: string
  remainingM3: string
  score: number
  etaScore: number
  workloadScore: number
  capacityScore: number
}

export interface CandidateRanking {
  orderId: number
  orderCode: string
  pickupLatitude: number
  pickupLongitude: number
  driversInRadius: number
  eligible: number
  excluded: Record<string, number>
  candidates: Candidate[]
  algorithm: string
}

export interface AssignmentResponse {
  id: number
  orderId: number
  driverId: number
  method: 'MANUAL' | 'AUTO'
  assignedBy: number | null
  score: number | null
  etaSeconds: number | null
  etaScore: number | null
  workloadScore: number | null
  capacityScore: number | null
  candidateRank: number | null
  createdAt: string
}

export interface AutoDispatchResult {
  startedAt: string
  waitingAtStart: number
  assignedCount: number
  notAssignedCount: number
  stillWaiting: number
  durationMillis: number
  assigned: {
    orderId: number
    orderCode: string
    priority: OrderPriority
    driverId: number
    driverCode: string
    etaSeconds: number
    score: number
    candidateRank: number
  }[]
  notAssigned: { orderId: number; orderCode: string; priority: OrderPriority; reason: string }[]
  algorithm: string
}

export interface AssignmentSettings {
  etaWeight: number
  workloadWeight: number
  capacityWeight: number
  etaCapSeconds: number
  searchRadiusMeters: number
  maxCandidates: number
  maxActiveDeliveries: number
  updatedAt: string
}

export interface SnappedPoint {
  nodeId: number
  latitude: number
  longitude: number
  snapDistanceMeters: number
}

export interface RouteResponse {
  id: number | null
  mode: RouteMode
  distanceMeters: number
  durationSeconds: number
  path: [number, number][]
  from: SnappedPoint
  to: SnappedPoint
  algorithm: string
  optimal: boolean
  nodesSettled: number
  graphVersion: number
  cached: boolean
  createdAt: string
}

export interface OptimizedRoute {
  mode: RouteMode
  returnToStart: boolean
  departAt: string
  totalDistanceMeters: number
  totalDurationSeconds: number
  serviceSeconds: number
  finishAt: string
  algorithm: string
  optimal: boolean
  algorithmSteps: number
  sequencingMillis: number
  stopCount: number
  visits: {
    sequence: number
    stopIndex: number
    label: string
    latitude: number
    longitude: number
    arriveAt: string
    departAt: string
    snapDistanceMeters: number
  }[]
  legs: {
    fromSequence: number
    toSequence: number
    from: string
    to: string
    distanceMeters: number
    durationSeconds: number
    path: [number, number][]
  }[]
  lateStops: { stopIndex: number; label: string; dueBy: string; arriveAt: string; lateBySeconds: number }[]
  comparedTo: number | null
  graphVersion: number
}

export interface SystemEventResponse {
  id: number
  eventId: string
  eventType: string
  eventVersion: number
  topic: string
  aggregateType: string
  aggregateId: string
  summary: string
  payload: string
  correlationId: string | null
  occurredAt: string
  recordedAt: string
}

export interface OutboxStatus {
  pending: number
  publishedEstimate: number
  at: string
}

export interface LivePosition {
  driverId: number
  latitude: number
  longitude: number
  at: string
  source: LocationSource
}

export interface SimulationStatus {
  driverMovement: boolean
  traffic: boolean
  note: string
}

export interface NetworkResponse {
  version: number
  source: string
  synthetic: boolean
  nodes: number
  edges: number
  segmentsWithTraffic: number
  bounds: { minLatitude: number; minLongitude: number; maxLatitude: number; maxLongitude: number }
  builtAt: string
}

/** The one error body every endpoint returns (Phase 4). */
export interface ApiErrorBody {
  timestamp?: string
  status: number
  code: string
  message: string
  path?: string
  traceId?: string
  fieldErrors?: { field: string; message: string }[]
}

/** Analytics (Phase 12). Each response carries the definitions its numbers were computed with. */

export interface Distribution {
  samples: number
  p50: number | null
  p90: number | null
  mean: number | null
}

export interface AnalyticsOverview {
  from: string
  to: string
  days: number
  ordersByStatus: Record<string, number>
  created: number
  delivered: number
  failed: number
  cancelled: number
  waitingNow: number
  activeNow: number
  deliveredWithWindow: number
  onTime: number
  late: number
  onTimeRate: number | null
  assignedToDeliveredMinutes: Distribution
  definitions: string[]
}

export interface ThroughputDay {
  day: string
  created: number
  delivered: number
  failed: number
  cancelled: number
}

export interface DriverPerformance {
  driverId: number
  driverCode: string
  status: DriverStatus
  delivered: number
  late: number
  failed: number
  activeNow: number
  medianMinutes: number | null
}

export interface FleetUsage {
  driverCount: number
  driversWithDeliveries: number
  shareOfFleetUsed: number | null
  deliveriesPerActiveDriver: number | null
  perDriver: DriverPerformance[]
  definitions: string[]
}

export interface EtaAccuracy {
  samples: number
  predictedMedianMinutes: number | null
  actualMedianMinutes: number | null
  medianDifferenceMinutes: number | null
  p90DifferenceMinutes: number | null
  withinFiveMinutes: number
  definitions: string[]
}

/** Whether the optional assistant (FR-24) is configured, and which read-only tools it has. */
export interface AssistantStatus {
  enabled: boolean
  reason: string
  model: string
  tools: string[]
}

export interface AssistantToolCall {
  tool: string
  arguments: string
  failed: boolean
  millis: number
}

export interface AssistantUsage {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  requests: number
}

/**
 * One answer. `sectionsParsed` is false when the model did not follow the three-section shape; then only
 * `text` is meaningful, and the page shows it as written rather than showing less than the model said.
 */
export interface AssistantAnswer {
  facts: string[]
  recommendations: string[]
  uncertainty: string[]
  text: string
  sectionsParsed: boolean
  toolCalls: AssistantToolCall[]
  toolRounds: number
  toolLimitReached: boolean
  model: string
  usage: AssistantUsage
}
