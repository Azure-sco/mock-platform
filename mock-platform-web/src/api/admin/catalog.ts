import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { ApiMutation, ApiUpdate, MockApi, PageQuery, PageResult, Provider, ProviderMutation, ProviderQuery, ProviderUpdate } from '../../types/admin'
import { normalizePage, writeHeaders, type PagePayload } from './shared'

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

export async function createProvider(payload: ProviderMutation, idempotencyKey?: string): Promise<Provider> {
  const response = await http.post<ApiResponse<Provider>>('/admin/v1/providers', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}

export async function updateProvider(id: number, payload: ProviderUpdate, idempotencyKey?: string): Promise<Provider> {
  const response = await http.put<ApiResponse<Provider>>(`/admin/v1/providers/${id}`, payload, {
    headers: writeHeaders(idempotencyKey),
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

export async function createApi(payload: ApiMutation, idempotencyKey?: string): Promise<MockApi> {
  const response = await http.post<ApiResponse<MockApi>>('/admin/v1/apis', payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}

export async function updateApi(id: number, payload: ApiUpdate, idempotencyKey?: string): Promise<MockApi> {
  const response = await http.put<ApiResponse<MockApi>>(`/admin/v1/apis/${id}`, payload, {
    headers: writeHeaders(idempotencyKey),
  })
  return response.data.data
}
