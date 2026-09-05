export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page: number
  size: number
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

export interface AuditLog {
  id: number
  requestId: string
  operator: string
  action: string
  objectType: string
  objectId: string | null
  objectChecksum: string | null
  beforeJsonMasked: string | null
  afterJsonMasked: string | null
  result: string
  reason: string | null
  createdAt: string
}

export interface AuditLogQuery extends PageQuery {
  requestId?: string
  operator?: string
  action?: string
  objectType?: string
  objectId?: string
  createdFrom?: string
  createdTo?: string
}

export type ResourceStatus = 'ENABLED' | 'DISABLED'

export interface Provider {
  id: number
  providerCode: string
  providerName: string
  owner: string
  status: ResourceStatus
  createdAt: string
  updatedAt: string
}

export interface ProviderMutation {
  providerCode: string
  providerName: string
  owner: string
  status: ResourceStatus
}

export type ProviderUpdate = Omit<ProviderMutation, 'providerCode'>

export interface ProviderQuery extends PageQuery {
  keyword?: string
  status?: ResourceStatus
}

export interface MockApi {
  id: number
  providerId: number
  providerCode?: string
  apiCode: string
  apiName: string
  httpMethod: string
  path: string
  contentType: string
  owner: string
  status: ResourceStatus
  createdAt: string
  updatedAt: string
}

export interface ApiMutation {
  providerId: number
  apiCode: string
  apiName: string
  httpMethod: string
  path: string
  contentType: string
  owner: string
  status: ResourceStatus
}

export type ApiUpdate = Omit<ApiMutation, 'providerId' | 'apiCode'>

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

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

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface ApprovalRequest {
  id: number
  objectType: string
  objectId: number
  objectChecksum: string
  policyCode: string
  requiredCount: number
  approvedCount?: number
  status: ApprovalStatus
  requestedBy: string
  requestedAt: string
  completedAt: string | null
}

export interface ApprovalDecisionMutation {
  comment?: string
}

export type ReleaseStatus = 'PREPARING' | 'READY' | 'PUBLISHED' | 'PARTIAL' | 'FAILED'
export type ActivationState = 'ACTIVATING' | 'APPLIED' | 'PARTIAL'

export interface Release {
  id: string
  releaseCode: string
  environment: string
  appCode: string
  status: ReleaseStatus
  checksum: string
  schemaVersion: string
  signatureKeyId: string
  signatureAlgorithm: string
  releaseNote: string | null
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
  scenarioVersionIds?: number[]
}

export interface ReleaseMutation {
  releaseCode: string
  environment: string
  appCode: string
  scenarioVersionIds: number[]
  releaseNote?: string
}

export interface ReleaseValidation {
  valid: boolean
  environment: string
  app: string
  scenarioVersionIds: number[]
  contractCount: number
  warnings: string[]
}

export interface ReleaseActivationResult {
  activationId: string
  releaseId: string
  activationVersion: number
  state: ActivationState
}

export interface ActiveRelease {
  environment: string
  appCode: string
  releaseId: string
  activationVersion: number
  state: ActivationState
  updatedAt: string
}

export type SecurityPolicyType =
  | 'APP_ACL'
  | 'PROVIDER_ENVIRONMENT'
  | 'SDK_HEADER_FILTER'
  | 'CALLBACK_ALLOWLIST'
  | 'CALLBACK_SIGNATURE'
  | 'SDK_FALLBACK_REAL'

export type SecurityPolicyStatus = 'DRAFT' | 'VALIDATED' | 'APPROVED' | 'PUBLISHED' | 'DEPRECATED'

export interface SecurityPolicyVersion {
  id: number
  policyId: string
  policyType: SecurityPolicyType
  scopeKey: string
  versionNo: number
  config: JsonValue
  checksum: string
  status: SecurityPolicyStatus
  signatureKeyId: string | null
  approvalRequestId: number | null
  sourceAuditRef: string | null
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
}

export interface SecurityPolicy {
  policyId: string
  policyType: SecurityPolicyType
  scopeKey: string
  latestVersionNo: number
  latestStatus: SecurityPolicyStatus
  latestVersionId?: number
}

export interface SecurityPolicyMutation {
  policyType: SecurityPolicyType
  scopeKey: string
  config: JsonValue
  sourceAuditRef?: string
}

export interface SecurityPolicyBinding {
  id: string
  policyType: SecurityPolicyType
  scopeKey: string
  desiredPolicyVersionId: number
  effectivePolicyVersionId: number | null
  effectMode: 'LIVE_ADMISSION' | 'RELEASE' | 'SDK_CONFIG'
  status: 'PUBLISHING' | 'BOUND' | 'INACTIVE'
  bindingVersion: number
  effectiveAt: string | null
}

export type SdkConfigStatus =
  | 'DRAFT'
  | 'VALIDATED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PUBLISHING'
  | 'PUBLISHED'
  | 'DEPRECATED'

export interface SdkSecurityPolicyRef {
  policyVersionId: number
  policyType: SecurityPolicyType
  scopeKey: string
  checksum: string
}

export interface SdkConfigEnvelope {
  id: number
  appCode: string
  environment: string
  configVersion: number
  routing: JsonValue
  securityPolicyRefs: SdkSecurityPolicyRef[]
  securityPolicyPayloads: JsonValue
  effectiveAt: string
  expireAt: string | null
  checksum: string
  signatureAlgorithm: string | null
  signatureKeyId: string | null
  validationStatus: string | null
  status: SdkConfigStatus
  approvalRequestId: number | null
  sourceAuditRef: string | null
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
}

export interface SdkConfigMutation {
  appCode: string
  environment: string
  routing: JsonValue
  securityPolicyVersionIds: number[]
  effectiveAt: string
  expireAt?: string
  sourceAuditRef?: string
}

export interface SdkConfigPublishMutation {
  expectedConfigVersion: number
  targetType: 'APOLLO' | 'NACOS'
  targetNamespace: string
}

export type FlowDefinitionVersionStatus =
  | 'DRAFT'
  | 'VALIDATED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'DEPRECATED'

export interface FlowDefinition {
  id: number
  providerId: number
  providerCode?: string
  flowCode: string
  flowName: string
  currentDraftVersion: number
  status: ResourceStatus
  createdBy: string
  createdAt: string
  updatedBy: string
  updatedAt: string
}

export interface FlowDefinitionVersion {
  id: number
  flowDefinitionId: number
  versionNo: number
  status: FlowDefinitionVersionStatus
  initialState: string
  ttlSeconds: number
  participantApis: JsonValue
  variables: JsonValue
  transitions: JsonValue
  compiled: JsonValue | null
  checksum: string
  validationStatus: string | null
  createdBy: string
  createdAt: string
}

export interface FlowDefinitionMutation {
  providerId: number
  flowCode: string
  flowName: string
}

export interface FlowDefinitionVersionMutation {
  initialState: string
  ttlSeconds: number
  participantApis: JsonValue
  variables: JsonValue
  transitions: JsonValue
}

export type FlowInstanceStatus = 'ACTIVE' | 'EXPIRED' | 'DELETED'

export interface FlowInstance {
  id: number
  flowKey: string
  environment: string
  appCode: string
  providerCode: string
  flowCode: string
  tenantCode: string
  testAccount: string
  businessNoMasked: string
  releaseId: string
  flowDefinitionVersionId: number
  flowDefinitionChecksum: string
  generation: number
  status: FlowInstanceStatus
  currentState: string
  queryCount: number
  variables: JsonValue
  version: number
  pendingTransitionId: string | null
  nextTransitionAt: string | null
  expireAt: string
  createdAt: string
  updatedAt: string
}

export interface FlowEvent {
  eventId: string
  flowInstanceId: number
  flowGeneration: number
  sourceType: 'SDK' | 'TIMER' | 'MANUAL'
  sourceApiCode: string | null
  transitionId: string | null
  fromState: string | null
  toState: string | null
  eventType: string
  queryCount: number
  operator: string | null
  eventAt: string
}

export type CallbackTaskStatus =
  | 'NEW'
  | 'RETRYING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'FAILED_PREPARATION'
  | 'FAILED_UNCONFIRMED'
  | 'CANCELLED'

export interface CallbackTask {
  taskId: string
  deliveryId: string
  flowEventId: string
  flowInstanceId: number
  flowGeneration: number
  releaseId: string
  snapshotChecksum: string
  providerCode: string
  apiCode: string
  callbackDefinitionId: string
  deliveryIndex: number
  callbackHost: string
  callbackPathMasked: string
  httpMethod: string
  status: CallbackTaskStatus
  nextExecuteAt: string
  sendAttemptCount: number
  maxRetry: number
  preparationRetryCount: number
  maxPreparationRetry: number
  fencingToken: number
  lastHttpStatus: number | null
  lastErrorMasked: string | null
  createdAt: string
  updatedAt: string
}

export interface CallbackAttempt {
  id: number
  taskId: string
  deliveryId: string
  attemptNo: number
  sendAttemptNo: number | null
  fencingToken: number
  status: string
  deliveryCertainty: 'NEVER_SENT' | 'CONFIRMED_RESPONSE' | 'UNKNOWN' | null
  startedAt: string | null
  completedAt: string | null
  httpStatus: number | null
  resultMasked: string | null
}
