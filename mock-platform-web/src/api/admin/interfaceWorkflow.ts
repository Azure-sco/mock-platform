import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { ActiveRelease, ContractMutation, ContractVersion, Release, Scenario, ScenarioMutation, ScenarioVersion, ScenarioVersionMutation } from '../../types/admin'
import { sameJson, type ReleaseItem } from '../../utils/interfaceWorkflow'
import { createScenario, createScenarioVersion, getScenario, getScenarios, submitScenarioApproval } from './scenarios'
import { createContract, getContracts, publishContract } from './contracts'

export interface ReleaseDetail { release: Release; items: ReleaseItem[] }
export interface ActivationDetail {
  activation: { id: string; status: string; failureReason?: string }
  targets: { runtimeNodeId: string; status: string }[]
}

export async function getReleaseDetail(id: string): Promise<ReleaseDetail> {
  return (await http.get<ApiResponse<ReleaseDetail>>(`/admin/v1/releases/${id}`)).data.data
}
export async function getActivationDetail(id: string): Promise<ActivationDetail> {
  return (await http.get<ApiResponse<ActivationDetail>>(`/admin/v1/release-activations/${id}`)).data.data
}

export async function activateResponseRelease(id: string, expectedActivationVersion: number, requestId: string): Promise<ActivationDetail> {
  return (await http.post<ApiResponse<ActivationDetail>>(`/admin/v1/releases/${id}/publish`,
    { expectedActivationVersion }, { headers: { 'X-Request-Id': requestId, 'Idempotency-Key': requestId } })).data.data
}

// Persist each existing domain step. On retry, recover it from authority rather than duplicating it.
export async function saveResponse(root: ScenarioMutation, payload: ScenarioVersionMutation, submit: boolean): Promise<ScenarioVersion> {
  let scenario: Scenario | undefined = (await getScenarios()).find(item => item.scenarioCode === root.scenarioCode)
  if (!scenario) {
    try { scenario = await createScenario(root) }
    catch (failure) {
      scenario = (await getScenarios()).find(item => item.scenarioCode === root.scenarioCode)
      if (!scenario) throw failure
    }
  }
  if (scenario.apiId !== root.apiId || scenario.providerId !== root.providerId) throw new Error('响应归属与当前接口不一致')
  const matching = async () => (await getScenario(scenario!.id)).versions?.find(version =>
    version.contractVersionId === payload.contractVersionId && version.priority === payload.priority
    && !version.flowDefinitionVersionId && !version.effectiveFrom && !version.effectiveTo
    && sameJson(version.scope, payload.scope) && sameJson(version.matchRules, payload.matchRules)
    && sameJson(version.response, payload.response) && sameJson(version.callbacks, payload.callbacks))
  let version = await matching()
  if (!version) {
    try { version = await createScenarioVersion(scenario.id, payload) }
    catch (failure) { version = await matching(); if (!version) throw failure }
  }
  if (version.status === 'DRAFT') {
    version = (await http.post<ApiResponse<ScenarioVersion>>(`/admin/v1/scenario-versions/${version.id}/validate`)).data.data
  }
  if (version.validationStatus !== 'VALID') throw new Error('响应校验未通过：' + JSON.stringify(version.validationResult))
  if (submit && version.status === 'VALIDATED') await submitScenarioApproval(version.id)
  return version
}

export async function ensureContract(apiId: number, payload: ContractMutation): Promise<ContractVersion> {
  const find = async () => (await getContracts(apiId)).find(item => sameJson(item.requestSchema, payload.requestSchema)
    && sameJson(item.responseSchema, payload.responseSchema) && item.sourceType === 'MANUAL'
    && sameJson(item.examples, payload.examples ?? []) && sameJson(item.errorCodes, payload.errorCodes ?? [])
    && sameJson(item.businessKeyExtractor, payload.businessKeyExtractor ?? {})
    && sameJson(item.signatureMetadata, payload.signatureMetadata ?? {}))
  let contract = await find()
  if (!contract) {
    try { contract = await createContract(apiId, payload) }
    catch (failure) { contract = await find(); if (!contract) throw failure }
  }
  if (contract.status === 'DRAFT') {
    contract = (await http.post<ApiResponse<ContractVersion>>(`/admin/v1/contracts/${contract.id}/validate`)).data.data
  }
  if (contract.status === 'VALIDATED') {
    await publishContract(contract.id)
    contract = (await getContracts(apiId)).find(item => item.id === contract!.id)!
  }
  if (contract.status !== 'PUBLISHED') throw new Error('契约校验或发布未通过，请检查 Schema')
  return contract
}

export function assertSameBase(expected: ActiveRelease | null, current: ActiveRelease | null) {
  if (expected?.releaseId !== current?.releaseId || expected?.activationVersion !== current?.activationVersion) {
    throw new Error('期间已有其他发布，请刷新并重新核对变更；本次未覆盖其他人的配置')
  }
}
