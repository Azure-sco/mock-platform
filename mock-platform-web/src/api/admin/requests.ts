import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { PageResult, RequestLog, RequestLogQuery } from '../../types/admin'
import { normalizePage, type PagePayload } from './shared'

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

