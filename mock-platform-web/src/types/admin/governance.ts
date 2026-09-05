import type { JsonValue } from './common'
import type { PageQuery } from './common'

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
