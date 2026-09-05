import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { ApprovalDecisionMutation, ApprovalRequest, Scenario, ScenarioMutation, ScenarioVersion, ScenarioVersionMutation } from '../../types/admin'
import { listPayload, writeHeaders, type PagePayload } from './shared'

export async function getScenarios(): Promise<Scenario[]> {
  const response = await http.get<ApiResponse<PagePayload<Scenario> | Scenario[]>>('/admin/v1/scenarios')
  return listPayload(response.data.data)
}

export async function getScenario(id: number): Promise<Scenario> {
  const response = await http.get<ApiResponse<{ scenario: Scenario; versions: ScenarioVersion[] }>>(
    `/admin/v1/scenarios/${id}`,
  )
  return { ...response.data.data.scenario, versions: response.data.data.versions }
}

export async function createScenario(payload: ScenarioMutation, idempotencyKey?: string): Promise<Scenario> {
  const response = await http.post<ApiResponse<Scenario>>('/admin/v1/scenarios', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}

export async function createScenarioVersion(
  scenarioId: number,
  payload: ScenarioVersionMutation,
  idempotencyKey?: string,
): Promise<ScenarioVersion> {
  const response = await http.post<ApiResponse<ScenarioVersion>>(
    `/admin/v1/scenarios/${scenarioId}/versions`,
    payload,
    { headers: writeHeaders(idempotencyKey) },
  )
  return response.data.data
}

export async function validateScenarioVersion(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/scenario-versions/${id}/validate`, undefined, { headers: writeHeaders(idempotencyKey) })
}

export async function submitScenarioApproval(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/scenario-versions/${id}/submit-approval`, undefined, {
    headers: writeHeaders(idempotencyKey),
  })
}

export async function getApprovals(): Promise<ApprovalRequest[]> {
  const response = await http.get<ApiResponse<PagePayload<ApprovalRequest> | ApprovalRequest[]>>(
    '/admin/v1/approvals',
  )
  return listPayload(response.data.data)
}

export async function decideApproval(
  id: number,
  decision: 'approve' | 'reject',
  payload: ApprovalDecisionMutation,
  idempotencyKey?: string,
): Promise<void> {
  await http.post(`/admin/v1/approvals/${id}/${decision}`, payload, { headers: writeHeaders(idempotencyKey) })
}
