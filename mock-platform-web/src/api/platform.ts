import axios from 'axios'
import { http } from './http'
import type { ApiResponse, DashboardSummary, HealthState, PlatformHealth } from '../types/platform'

export async function getControlHealth(): Promise<PlatformHealth> {
  const response = await http.get<ApiResponse<PlatformHealth>>('/platform/health')
  return response.data.data
}

export async function getDashboardSummary(): Promise<DashboardSummary> {
  const response = await http.get<ApiResponse<DashboardSummary>>('/dashboard/summary')
  return response.data.data
}

export async function getRuntimeHealth(): Promise<HealthState> {
  const response = await axios.get<{ status?: string }>('/runtime-actuator/health', { timeout: 5000 })
  return response.data.status === 'UP' ? 'UP' : 'DOWN'
}
