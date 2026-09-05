import type { JsonValue, PageQuery } from './common'
import type { ResourceStatus } from './catalog'

export type ContractStatus = 'DRAFT' | 'VALIDATED' | 'PUBLISHED' | 'DEPRECATED'

export interface ContractVersion {
  id: number
  apiId: number
  versionNo: number
  status: ContractStatus
  requestSchema: JsonValue
  responseSchema: JsonValue
  examples: JsonValue
  errorCodes: JsonValue
  businessKeyExtractor: JsonValue
  signatureMetadata: JsonValue
  sourceType: string
  sourceFileHash: string | null
  checksum: string
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
}

export interface ContractMutation {
  requestSchema: JsonValue
  responseSchema: JsonValue
  examples?: JsonValue
  errorCodes?: JsonValue
  businessKeyExtractor?: JsonValue
  signatureMetadata?: JsonValue
  sourceType: string
  sourceFileHash?: string
}

export interface ContractDiffEntry {
  path: string
  changeType: string
  before: JsonValue
  after: JsonValue
}

export interface ContractDiff {
  compareTo: number
  contractId: number
  changes: ContractDiffEntry[]
}

export interface RequestLog {
  id: string
  mockRequestId: string
  traceId: string | null
  environment: string
  appCode: string
  tenantCode: string | null
  testAccountMasked: string | null
  providerCode: string
  apiCode: string
  scenarioId: string | null
  scenarioVersionId: string | null
  releaseId: string | null
  flowKey: string | null
  businessNoHmac: string | null
  hmacKeyVersion: string | null
  httpMethod: string
  path: string
  requestSummary: string | null
  responseSummary: string | null
  httpStatus: number | null
  matchResult: string | null
  durationMs: number
  errorCode: string | null
  expireAt: string | null
  createdAt: string
}

export interface RequestLogQuery extends PageQuery {
  traceId?: string
  appCode?: string
  providerCode?: string
  apiCode?: string
  scenarioId?: string
  mockRequestId?: string
  businessNoHmac?: string
  hmacKeyVersion?: string
  createdFrom?: string
  createdTo?: string
}

export type ScenarioVersionStatus =
  | 'DRAFT'
  | 'VALIDATED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'DISABLED'

export interface ScenarioVersion {
  id: number
  scenarioId: number
  versionNo: number
  status: ScenarioVersionStatus
  contractVersionId: number
  flowDefinitionVersionId: number | null
  priority: number
  effectiveFrom: string | null
  effectiveTo: string | null
  scope: JsonValue
  matchRules: JsonValue
  response: JsonValue
  callbacks: JsonValue
  checksum: string
  validationStatus: string | null
  validationResult: JsonValue | null
  approvalRequestId: number | null
  createdBy: string
  createdAt: string
  approvedAt: string | null
  publishedAt: string | null
}

export interface Scenario {
  id: number
  scenarioCode: string
  scenarioName: string
  providerId: number
  apiId: number
  currentDraftVersion: number | null
  status: ResourceStatus
  createdBy: string
  createdAt: string
  updatedAt: string
  versions?: ScenarioVersion[]
}

export interface ScenarioMutation {
  scenarioCode: string
  scenarioName: string
  providerId: number
  apiId: number
}

export interface ScenarioVersionMutation {
  contractVersionId: number
  flowDefinitionVersionId?: number
  priority: number
  effectiveFrom?: string
  effectiveTo?: string
  scope: JsonValue
  matchRules: JsonValue
  response: JsonValue
  callbacks: JsonValue
}
