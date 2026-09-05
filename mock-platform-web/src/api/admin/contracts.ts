import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { ContractDiff, ContractMutation, ContractVersion } from '../../types/admin'
import { writeHeaders, type PagePayload } from './shared'

export async function getContracts(apiId: number): Promise<ContractVersion[]> {
  const response = await http.get<ApiResponse<ContractVersion[] | PagePayload<ContractVersion>>>(
    `/admin/v1/apis/${apiId}/contracts`,
  )
  const payload = response.data.data
  return Array.isArray(payload) ? payload : (payload.records ?? payload.items ?? [])
}

export async function createContract(
  apiId: number,
  payload: ContractMutation,
  idempotencyKey?: string,
): Promise<ContractVersion> {
  const response = await http.post<ApiResponse<ContractVersion>>(
    `/admin/v1/apis/${apiId}/contracts`,
    payload,
    { headers: writeHeaders(idempotencyKey) },
  )
  return response.data.data
}

export async function importContract(
  apiId: number,
  file: File,
  options: { path?: string; method?: string; target?: 'REQUEST' | 'RESPONSE' },
  idempotencyKey?: string,
): Promise<ContractVersion> {
  const body = new FormData()
  body.append('file', file)
  const response = await http.post<ApiResponse<ContractVersion>>(
    `/admin/v1/apis/${apiId}/contracts/import`,
    body,
    { headers: writeHeaders(idempotencyKey), params: options },
  )
  return response.data.data
}

export async function validateContract(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/contracts/${id}/validate`, undefined, { headers: writeHeaders(idempotencyKey) })
}

export async function publishContract(id: number, idempotencyKey?: string): Promise<void> {
  await http.post(`/admin/v1/contracts/${id}/publish`, undefined, { headers: writeHeaders(idempotencyKey) })
}

export async function diffContract(id: number, compareTo: number): Promise<ContractDiff> {
  const response = await http.get<ApiResponse<ContractDiff>>(`/admin/v1/contracts/${id}/diff`, {
    params: { compareTo },
  })
  return response.data.data
}
