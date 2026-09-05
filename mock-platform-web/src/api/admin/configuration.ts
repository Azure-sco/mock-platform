import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { SdkConfigEnvelope, SdkConfigMutation, SdkConfigPublishMutation, SecurityPolicy, SecurityPolicyBinding, SecurityPolicyMutation, SecurityPolicyType, SecurityPolicyVersion } from '../../types/admin'
import { listPayload, writeHeaders, type PagePayload } from './shared'

export async function getSecurityPolicies(
  policyType?: SecurityPolicyType,
  scopeKey?: string,
): Promise<SecurityPolicy[]> {
  const response = await http.get<ApiResponse<PagePayload<SecurityPolicy> | SecurityPolicy[]>>(
    '/admin/v1/security-policies',
    { params: { policyType, scopeKey } },
  )
  return listPayload(response.data.data)
}

export async function getSecurityPolicyVersions(policyId: string): Promise<SecurityPolicyVersion[]> {
  const response = await http.get<ApiResponse<PagePayload<SecurityPolicyVersion> | SecurityPolicyVersion[]>>(
    `/admin/v1/security-policies/${policyId}/versions`,
  )
  return listPayload(response.data.data)
}

export async function createSecurityPolicy(payload: SecurityPolicyMutation, idempotencyKey?: string): Promise<SecurityPolicyVersion> {
  const response = await http.post<ApiResponse<SecurityPolicyVersion>>('/admin/v1/security-policies', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}

export async function createSecurityPolicyVersion(
  policyId: string,
  payload: Omit<SecurityPolicyMutation, 'policyType' | 'scopeKey'>,
  idempotencyKey?: string,
): Promise<SecurityPolicyVersion> {
  const response = await http.post<ApiResponse<SecurityPolicyVersion>>(
    `/admin/v1/security-policies/${policyId}/versions`,
    payload,
    { headers: writeHeaders(idempotencyKey) },
  )
  return response.data.data
}

export async function validateSecurityPolicyVersion(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/security-policy-versions/${id}/validate`, undefined, {
    headers: writeHeaders(idempotencyKey),
  })
}

export async function submitSecurityPolicyApproval(id: number, policyCode: string, requiredCount: number, idempotencyKey?: string) {
  await http.post(
    `/admin/v1/security-policy-versions/${id}/submit-approval`,
    { policyCode, requiredCount },
    { headers: writeHeaders(idempotencyKey) },
  )
}

export async function publishSecurityPolicyVersion(id: number, expectedBindingVersion: number, idempotencyKey?: string): Promise<void> {
  await http.post(
    `/admin/v1/security-policy-versions/${id}/publish`,
    { expectedBindingVersion },
    { headers: writeHeaders(idempotencyKey) },
  )
}

export async function getSecurityPolicyBinding(
  policyType: SecurityPolicyType,
  scopeKey: string,
): Promise<SecurityPolicyBinding | null> {
  const response = await http.get<ApiResponse<SecurityPolicyBinding | null>>(
    '/admin/v1/security-policy-bindings',
    { params: { policyType, scopeKey } },
  )
  return response.data.data
}

export async function getSdkConfigEnvelopes(app: string, environment: string): Promise<SdkConfigEnvelope[]> {
  const response = await http.get<ApiResponse<PagePayload<SdkConfigEnvelope> | SdkConfigEnvelope[]>>(
    '/admin/v1/sdk-config-envelopes',
    { params: { app, environment } },
  )
  return listPayload(response.data.data)
}

export async function createSdkConfigEnvelope(
  payload: SdkConfigMutation,
  idempotencyKey?: string,
): Promise<SdkConfigEnvelope> {
  const response = await http.post<ApiResponse<SdkConfigEnvelope>>('/admin/v1/sdk-config-envelopes', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}

export async function validateSdkConfigEnvelope(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/validate`, undefined, { headers: writeHeaders(idempotencyKey) })
}

export async function submitSdkConfigApproval(
  id: number,
  policyCode: string,
  requiredCount: number,
  idempotencyKey?: string,
): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/submit-approval`, { policyCode, requiredCount }, {
    headers: writeHeaders(idempotencyKey),
  })
}

export async function publishSdkConfigEnvelope(
  id: number,
  payload: SdkConfigPublishMutation,
  idempotencyKey?: string,
): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/publish`, payload, { headers: writeHeaders(idempotencyKey) })
}

export async function rollbackSdkConfigEnvelope(id: number, idempotencyKey?: string): Promise<SdkConfigEnvelope> {
  const response = await http.post<ApiResponse<SdkConfigEnvelope>>(
    `/admin/v1/sdk-config-envelopes/${id}/rollback`,
    undefined,
    { headers: writeHeaders(idempotencyKey) },
  )
  return response.data.data
}
