import type { PageQuery } from './common'

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

