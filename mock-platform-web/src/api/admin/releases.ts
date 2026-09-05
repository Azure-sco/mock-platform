import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { ActiveRelease, Release, ReleaseActivationResult, ReleaseMutation, ReleaseValidation } from '../../types/admin'
import { listPayload, writeHeaders, type PagePayload } from './shared'

export async function getReleases(): Promise<Release[]> {
  const response = await http.get<ApiResponse<PagePayload<Release> | Release[]>>('/admin/v1/releases')
  return listPayload(response.data.data)
}

export async function validateRelease(payload: ReleaseMutation, idempotencyKey?: string): Promise<ReleaseValidation> {
  const response = await http.post<ApiResponse<ReleaseValidation>>(
    '/admin/v1/releases/validate',
    {
      environment: payload.environment,
      appCode: payload.appCode,
      scenarioVersionIds: payload.scenarioVersionIds,
    },
    { headers: writeHeaders(idempotencyKey) },
  )
  return response.data.data
}

export async function createRelease(payload: ReleaseMutation, idempotencyKey?: string): Promise<Release> {
  const response = await http.post<ApiResponse<{ release: Release }>>('/admin/v1/releases', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data.release
}

export async function publishRelease(
  id: string,
  expectedActivationVersion: number,
  idempotencyKey?: string,
): Promise<ReleaseActivationResult> {
  const response = await http.post<ApiResponse<{
    activation: { id: string; toReleaseId: string; toActivationVersion: number; status: string }
  }>>(
    `/admin/v1/releases/${id}/publish`,
    { expectedActivationVersion },
    { headers: writeHeaders(idempotencyKey) },
  )
  const activation = response.data.data.activation
  return {
    activationId: activation.id,
    releaseId: activation.toReleaseId,
    activationVersion: activation.toActivationVersion,
    state: activation.status === 'APPLIED' || activation.status === 'PARTIAL'
      ? activation.status
      : 'ACTIVATING',
  }
}

export async function rollbackRelease(
  id: string,
  expectedActivationVersion: number,
  idempotencyKey?: string,
): Promise<ReleaseActivationResult> {
  const response = await http.post<ApiResponse<{
    activation: { id: string; toReleaseId: string; toActivationVersion: number; status: string }
  }>>(
    `/admin/v1/releases/${id}/rollback`,
    { expectedActivationVersion },
    { headers: writeHeaders(idempotencyKey) },
  )
  const activation = response.data.data.activation
  return {
    activationId: activation.id,
    releaseId: activation.toReleaseId,
    activationVersion: activation.toActivationVersion,
    state: activation.status === 'APPLIED' || activation.status === 'PARTIAL'
      ? activation.status
      : 'ACTIVATING',
  }
}

export async function getActiveRelease(environment: string, app: string): Promise<ActiveRelease | null> {
  const response = await http.get<ApiResponse<ActiveRelease | null>>('/admin/v1/active-releases', {
    params: { environment, app },
  })
  return response.data.data
}
