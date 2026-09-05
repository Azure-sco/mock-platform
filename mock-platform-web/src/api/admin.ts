import { http } from './http'
import type { ApiResponse } from '../types/platform'
import type {
  ActiveRelease,
  AuditLog,
  AuditLogQuery,
  ApprovalDecisionMutation,
  ApprovalRequest,
  ApiMutation,
  ApiUpdate,
  ContractDiff,
  ContractMutation,
  ContractVersion,
  DashboardSummary,
  CallbackAttempt,
  CallbackTask,
  FlowDefinition,
  FlowDefinitionMutation,
  FlowDefinitionVersion,
  FlowDefinitionVersionMutation,
  FlowEvent,
  FlowInstance,
  MockApi,
  PageQuery,
  PageResult,
  Provider,
  ProviderMutation,
  ProviderQuery,
  ProviderUpdate,
  Release,
  ReleaseActivationResult,
  ReleaseMutation,
  ReleaseValidation,
  RequestLog,
  RequestLogQuery,
  Scenario,
  ScenarioMutation,
  ScenarioVersion,
  ScenarioVersionMutation,
  SdkConfigEnvelope,
  SdkConfigMutation,
  SdkConfigPublishMutation,
  SecurityPolicy,
  SecurityPolicyBinding,
  SecurityPolicyMutation,
  SecurityPolicyType,
  SecurityPolicyVersion,
} from '../types/admin'

export async function getDashboardSummary(): Promise<DashboardSummary> {
  const response = await http.get<ApiResponse<DashboardSummary>>('/dashboard/summary')
  return response.data.data
}

export async function getAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLog>> {
  const response = await http.get<ApiResponse<PagePayload<AuditLog>>>('/admin/v1/audits', {
    params: { ...query, page: query.page - 1 },
  })
  return normalizePage(response.data.data, query, true)
}

interface PagePayload<T> {
  records?: T[]
  items?: T[]
  total?: number
  page?: number
  size?: number
}

function normalizePage<T>(
  payload: PagePayload<T> | T[],
  fallback: PageQuery,
  serverZeroBased = false,
): PageResult<T> {
  if (Array.isArray(payload)) {
    const start = (fallback.page - 1) * fallback.size
    return {
      records: payload.slice(start, start + fallback.size),
      total: payload.length,
      page: fallback.page,
      size: fallback.size,
    }
  }
  const records = payload.records ?? payload.items ?? []
  const payloadPage = payload.page ?? (serverZeroBased ? fallback.page - 1 : fallback.page)
  return {
    records,
    total: payload.total ?? records.length,
    page: serverZeroBased ? payloadPage + 1 : payloadPage,
    size: payload.size ?? fallback.size,
  }
}

function writeHeaders() {
  return { 'Idempotency-Key': `web-${crypto.randomUUID()}` }
}

export async function getProviders(query: ProviderQuery): Promise<PageResult<Provider>> {
  const response = await http.get<ApiResponse<PagePayload<Provider> | Provider[]>>('/admin/v1/providers', {
    params: query,
  })
  const payload = response.data.data
  if (!Array.isArray(payload)) return normalizePage(payload, query)
  const keyword = query.keyword?.toLocaleLowerCase()
  const filtered = payload.filter((provider) => {
    const matchesKeyword = !keyword || [provider.providerCode, provider.providerName, provider.owner]
      .some((value) => value.toLocaleLowerCase().includes(keyword))
    return matchesKeyword && (!query.status || provider.status === query.status)
  })
  return normalizePage(filtered, query)
}

export async function createProvider(payload: ProviderMutation): Promise<Provider> {
  const response = await http.post<ApiResponse<Provider>>('/admin/v1/providers', payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function updateProvider(id: number, payload: ProviderUpdate): Promise<Provider> {
  const response = await http.put<ApiResponse<Provider>>(`/admin/v1/providers/${id}`, payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function getProviderApis(
  providerId: number,
  query: PageQuery,
): Promise<PageResult<MockApi>> {
  const response = await http.get<ApiResponse<PagePayload<MockApi> | MockApi[]>>(
    `/admin/v1/providers/${providerId}/apis`,
    { params: query },
  )
  return normalizePage(response.data.data, query)
}

export async function createApi(payload: ApiMutation): Promise<MockApi> {
  const response = await http.post<ApiResponse<MockApi>>('/admin/v1/apis', payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function updateApi(id: number, payload: ApiUpdate): Promise<MockApi> {
  const response = await http.put<ApiResponse<MockApi>>(`/admin/v1/apis/${id}`, payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function getContracts(apiId: number): Promise<ContractVersion[]> {
  const response = await http.get<ApiResponse<ContractVersion[] | PagePayload<ContractVersion>>>(
    `/admin/v1/apis/${apiId}/contracts`,
  )
  const payload = response.data.data
  return Array.isArray(payload) ? payload : (payload.records ?? payload.items ?? [])
}

export async function createContract(apiId: number, payload: ContractMutation): Promise<ContractVersion> {
  const response = await http.post<ApiResponse<ContractVersion>>(
    `/admin/v1/apis/${apiId}/contracts`,
    payload,
    { headers: writeHeaders() },
  )
  return response.data.data
}

export async function importContract(
  apiId: number,
  file: File,
  options: { path?: string; method?: string; target?: 'REQUEST' | 'RESPONSE' },
): Promise<ContractVersion> {
  const body = new FormData()
  body.append('file', file)
  const response = await http.post<ApiResponse<ContractVersion>>(
    `/admin/v1/apis/${apiId}/contracts/import`,
    body,
    { headers: writeHeaders(), params: options },
  )
  return response.data.data
}

export async function validateContract(id: number): Promise<void> {
  await http.post(`/admin/v1/contracts/${id}/validate`, undefined, { headers: writeHeaders() })
}

export async function publishContract(id: number): Promise<void> {
  await http.post(`/admin/v1/contracts/${id}/publish`, undefined, { headers: writeHeaders() })
}

export async function diffContract(id: number, compareTo: number): Promise<ContractDiff> {
  const response = await http.get<ApiResponse<ContractDiff>>(`/admin/v1/contracts/${id}/diff`, {
    params: { compareTo },
  })
  return response.data.data
}

export async function getRequestLogs(query: RequestLogQuery): Promise<PageResult<RequestLog>> {
  const params = { ...query, page: Math.max(query.page - 1, 0) }
  const response = await http.get<ApiResponse<PagePayload<RequestLog> | RequestLog[]>>(
    '/admin/v1/requests',
    { params },
  )
  return normalizePage(response.data.data, query, true)
}

export async function getRequestLog(id: string, createdDate: string): Promise<RequestLog> {
  const response = await http.get<ApiResponse<RequestLog>>(`/admin/v1/requests/${id}`, {
    params: { createdDate },
  })
  return response.data.data
}

function listPayload<T>(payload: PagePayload<T> | T[]): T[] {
  return Array.isArray(payload) ? payload : (payload.records ?? payload.items ?? [])
}

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

export async function createScenario(payload: ScenarioMutation): Promise<Scenario> {
  const response = await http.post<ApiResponse<Scenario>>('/admin/v1/scenarios', payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function createScenarioVersion(
  scenarioId: number,
  payload: ScenarioVersionMutation,
): Promise<ScenarioVersion> {
  const response = await http.post<ApiResponse<ScenarioVersion>>(
    `/admin/v1/scenarios/${scenarioId}/versions`,
    payload,
    { headers: writeHeaders() },
  )
  return response.data.data
}

export async function validateScenarioVersion(id: number): Promise<void> {
  await http.post(`/admin/v1/scenario-versions/${id}/validate`, undefined, { headers: writeHeaders() })
}

export async function submitScenarioApproval(id: number): Promise<void> {
  await http.post(`/admin/v1/scenario-versions/${id}/submit-approval`, undefined, {
    headers: writeHeaders(),
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
): Promise<void> {
  await http.post(`/admin/v1/approvals/${id}/${decision}`, payload, { headers: writeHeaders() })
}

export async function getReleases(): Promise<Release[]> {
  const response = await http.get<ApiResponse<PagePayload<Release> | Release[]>>('/admin/v1/releases')
  return listPayload(response.data.data)
}

export async function validateRelease(payload: ReleaseMutation): Promise<ReleaseValidation> {
  const response = await http.post<ApiResponse<ReleaseValidation>>(
    '/admin/v1/releases/validate',
    {
      environment: payload.environment,
      appCode: payload.appCode,
      scenarioVersionIds: payload.scenarioVersionIds,
    },
    { headers: writeHeaders() },
  )
  return response.data.data
}

export async function createRelease(payload: ReleaseMutation): Promise<Release> {
  const response = await http.post<ApiResponse<{ release: Release }>>('/admin/v1/releases', payload, {
    headers: writeHeaders(),
  })
  return response.data.data.release
}

export async function publishRelease(
  id: string,
  expectedActivationVersion: number,
): Promise<ReleaseActivationResult> {
  const response = await http.post<ApiResponse<{
    activation: { id: string; toReleaseId: string; toActivationVersion: number; status: string }
  }>>(
    `/admin/v1/releases/${id}/publish`,
    { expectedActivationVersion },
    { headers: writeHeaders() },
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
): Promise<ReleaseActivationResult> {
  const response = await http.post<ApiResponse<{
    activation: { id: string; toReleaseId: string; toActivationVersion: number; status: string }
  }>>(
    `/admin/v1/releases/${id}/rollback`,
    { expectedActivationVersion },
    { headers: writeHeaders() },
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

export async function createSecurityPolicy(payload: SecurityPolicyMutation): Promise<SecurityPolicyVersion> {
  const response = await http.post<ApiResponse<SecurityPolicyVersion>>('/admin/v1/security-policies', payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function createSecurityPolicyVersion(
  policyId: string,
  payload: Omit<SecurityPolicyMutation, 'policyType' | 'scopeKey'>,
): Promise<SecurityPolicyVersion> {
  const response = await http.post<ApiResponse<SecurityPolicyVersion>>(
    `/admin/v1/security-policies/${policyId}/versions`,
    payload,
    { headers: writeHeaders() },
  )
  return response.data.data
}

export async function validateSecurityPolicyVersion(id: number): Promise<void> {
  await http.post(`/admin/v1/security-policy-versions/${id}/validate`, undefined, {
    headers: writeHeaders(),
  })
}

export async function submitSecurityPolicyApproval(id: number, policyCode: string, requiredCount: number) {
  await http.post(
    `/admin/v1/security-policy-versions/${id}/submit-approval`,
    { policyCode, requiredCount },
    { headers: writeHeaders() },
  )
}

export async function publishSecurityPolicyVersion(id: number, expectedBindingVersion: number): Promise<void> {
  await http.post(
    `/admin/v1/security-policy-versions/${id}/publish`,
    { expectedBindingVersion },
    { headers: writeHeaders() },
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

export async function createSdkConfigEnvelope(payload: SdkConfigMutation): Promise<SdkConfigEnvelope> {
  const response = await http.post<ApiResponse<SdkConfigEnvelope>>('/admin/v1/sdk-config-envelopes', payload, {
    headers: writeHeaders(),
  })
  return response.data.data
}

export async function validateSdkConfigEnvelope(id: number): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/validate`, undefined, { headers: writeHeaders() })
}

export async function submitSdkConfigApproval(
  id: number,
  policyCode: string,
  requiredCount: number,
): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/submit-approval`, { policyCode, requiredCount }, {
    headers: writeHeaders(),
  })
}

export async function publishSdkConfigEnvelope(
  id: number,
  payload: SdkConfigPublishMutation,
): Promise<void> {
  await http.post(`/admin/v1/sdk-config-envelopes/${id}/publish`, payload, { headers: writeHeaders() })
}

export async function rollbackSdkConfigEnvelope(id: number): Promise<SdkConfigEnvelope> {
  const response = await http.post<ApiResponse<SdkConfigEnvelope>>(
    `/admin/v1/sdk-config-envelopes/${id}/rollback`,
    undefined,
    { headers: writeHeaders() },
  )
  return response.data.data
}

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

export async function createFlowDefinition(payload: FlowDefinitionMutation): Promise<FlowDefinition> {
  const response = await http.post<ApiResponse<FlowDefinition | { flowDefinition: FlowDefinition }>>(
    '/admin/v1/flow-definitions',
    payload,
    { headers: writeHeaders() },
  )
  const data = response.data.data
  return 'flowDefinition' in data ? data.flowDefinition : data
}

export async function createFlowDefinitionVersion(
  flowDefinitionId: number,
  payload: FlowDefinitionVersionMutation,
): Promise<FlowDefinitionVersion> {
  const response = await http.post<ApiResponse<FlowDefinitionVersion | { version: FlowDefinitionVersion }>>(
    `/admin/v1/flow-definitions/${flowDefinitionId}/versions`,
    payload,
    { headers: writeHeaders() },
  )
  const data = response.data.data
  return 'version' in data ? data.version : data
}

export async function validateFlowDefinitionVersion(id: number): Promise<void> {
  await http.post(`/admin/v1/flow-definition-versions/${id}/validate`, undefined, { headers: writeHeaders() })
}

export async function submitFlowDefinitionApproval(
  id: number,
  policyCode = 'FLOW_DUAL_CONTROL',
  requiredCount = 2,
): Promise<void> {
  await http.post(
    `/admin/v1/flow-definition-versions/${id}/submit-approval`,
    { policyCode, requiredCount },
    { headers: writeHeaders() },
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
