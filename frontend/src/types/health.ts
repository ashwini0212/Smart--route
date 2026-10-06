/** Status values reported by Spring Boot Actuator's health endpoint. */
export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'

export interface HealthResponse {
  status: HealthStatus
}
