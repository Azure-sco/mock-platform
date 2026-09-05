import type { JsonValue } from './common'
import type { ResourceStatus } from './catalog'

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

