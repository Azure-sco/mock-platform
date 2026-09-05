import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { FlowDefinition, FlowDefinitionMutation, FlowDefinitionVersion, FlowDefinitionVersionMutation, FlowEvent, FlowInstance } from '../../types/admin'
import { listPayload, writeHeaders, type PagePayload } from './shared'

export async function getFlowDefinitions(providerId?: number): Promise<FlowDefinition[]> {
  const response = await http.get<ApiResponse<PagePayload<FlowDefinition> | FlowDefinition[]>>(
    '/admin/v1/flow-definitions',
    { params: { providerId } },
  )
  return listPayload(response.data.data)
}

export async function getFlowDefinition(
  id: number,
): Promise<{ flowDefinition: FlowDefinition; versions: FlowDefinitionVersion[] }> {
  const response = await http.get<ApiResponse<
    | { flowDefinition: FlowDefinition; versions: FlowDefinitionVersion[] }
    | { definition: FlowDefinition; versions: FlowDefinitionVersion[] }
  >>(`/admin/v1/flow-definitions/${id}`)
  const data = response.data.data
  return {
    flowDefinition: 'flowDefinition' in data ? data.flowDefinition : data.definition,
    versions: data.versions,
  }
}

export async function createFlowDefinition(payload: FlowDefinitionMutation, idempotencyKey?: string): Promise<FlowDefinition> {
  const response = await http.post<ApiResponse<FlowDefinition | { flowDefinition: FlowDefinition }>>(
    '/admin/v1/flow-definitions',
    payload,
    { headers: writeHeaders(idempotencyKey) },
  )
  const data = response.data.data
  return 'flowDefinition' in data ? data.flowDefinition : data
}

export async function createFlowDefinitionVersion(
  flowDefinitionId: number,
  payload: FlowDefinitionVersionMutation,
  idempotencyKey?: string,
): Promise<FlowDefinitionVersion> {
  const response = await http.post<ApiResponse<FlowDefinitionVersion | { version: FlowDefinitionVersion }>>(
    `/admin/v1/flow-definitions/${flowDefinitionId}/versions`,
    payload,
    { headers: writeHeaders(idempotencyKey) },
  )
  const data = response.data.data
  return 'version' in data ? data.version : data
}

export async function validateFlowDefinitionVersion(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/flow-definition-versions/${id}/validate`, undefined, { headers: writeHeaders(idempotencyKey) })
}

export async function submitFlowDefinitionApproval(
  id: number,
  policyCode = 'FLOW_DUAL_CONTROL',
  requiredCount = 2,
  idempotencyKey?: string,
): Promise<void> {
  await http.post(
    `/admin/v1/flow-definition-versions/${id}/submit-approval`,
    { policyCode, requiredCount },
    { headers: writeHeaders(idempotencyKey) },
  )
}

export async function getFlowInstances(params: {
  environment?: string
  appCode?: string
  providerCode?: string
  flowCode?: string
  status?: string
}): Promise<FlowInstance[]> {
  const response = await http.get<ApiResponse<PagePayload<FlowInstance> | FlowInstance[]>>(
    '/admin/v1/flow-instances',
    { params },
  )
  return listPayload(response.data.data)
}

export async function getFlowInstance(flowKey: string): Promise<FlowInstance> {
  const response = await http.get<ApiResponse<FlowInstance>>(`/admin/v1/flow-instances/${encodeURIComponent(flowKey)}`)
  return response.data.data
}

export async function getFlowEvents(flowKey: string): Promise<FlowEvent[]> {
  const response = await http.get<ApiResponse<PagePayload<FlowEvent> | FlowEvent[]>>(
    `/admin/v1/flow-instances/${encodeURIComponent(flowKey)}/events`,
  )
  return listPayload(response.data.data)
}

export async function transitionFlow(flowKey: string, transitionId: string, requestId: string): Promise<void> {
  await http.post(
    `/admin/v1/flow-instances/${encodeURIComponent(flowKey)}/transition`,
    { transitionId, requestId, confirmed: true },
    { headers: { 'Idempotency-Key': requestId, 'X-Second-Confirmation': 'true' } },
  )
}

export async function resetFlow(flowKey: string, requestId: string, keepPinnedVersion: boolean): Promise<void> {
  await http.post(
    `/admin/v1/flow-instances/${encodeURIComponent(flowKey)}/reset`,
    { requestId, keepPinnedVersion, confirmed: true },
    { headers: { 'Idempotency-Key': requestId, 'X-Second-Confirmation': 'true' } },
  )
}

export async function deleteFlow(flowKey: string, requestId: string): Promise<void> {
  await http.delete(`/admin/v1/flow-instances/${encodeURIComponent(flowKey)}`, {
    data: { requestId, confirmed: true },
    headers: { 'Idempotency-Key': requestId, 'X-Second-Confirmation': 'true' },
  })
}
