import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { CallbackAttempt, CallbackTask } from '../../types/admin'
import { listPayload, type PagePayload } from './shared'

export async function getCallbackTasks(params: {
  providerCode?: string
  apiCode?: string
  status?: string
}): Promise<CallbackTask[]> {
  const response = await http.get<ApiResponse<PagePayload<CallbackTask> | CallbackTask[]>>(
    '/admin/v1/callback-tasks',
    { params },
  )
  return listPayload(response.data.data)
}

export async function getCallbackAttempts(taskId: string): Promise<CallbackAttempt[]> {
  const response = await http.get<ApiResponse<PagePayload<CallbackAttempt> | CallbackAttempt[]>>(
    `/admin/v1/callback-tasks/${encodeURIComponent(taskId)}/attempts`,
  )
  return listPayload(response.data.data)
}

export async function retryCallbackTask(taskId: string, requestId: string, delayMs: number): Promise<void> {
  await http.post(
    `/admin/v1/callback-tasks/${encodeURIComponent(taskId)}/retry`,
    { requestId, delayMs, confirmed: true },
    { headers: { 'Idempotency-Key': requestId, 'X-Second-Confirmation': 'true' } },
  )
}

export async function cancelCallbackTask(taskId: string, requestId: string): Promise<void> {
  await http.post(
    `/admin/v1/callback-tasks/${encodeURIComponent(taskId)}/cancel`,
    { requestId, confirmed: true },
    { headers: { 'Idempotency-Key': requestId, 'X-Second-Confirmation': 'true' } },
  )
}

