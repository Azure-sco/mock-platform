export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
  requestId: string
}

export interface PlatformHealth {
  service: string
  status: 'UP' | 'DOWN'
  phase: 'M0' | 'M1'
}

export interface DashboardSummary {
  operator: string
  providers: number
  apis: number
  scenarios: number
  releases: number
  requests: number
  hitRate: number
  noMatchRequests: number
  p95DurationMs: number
  callbackSuccessRate: number
  callbackRetries: number
}

export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN'
export type HealthCheckStatus = 'IDLE' | 'CHECKING' | 'SUCCESS' | 'ERROR'
