import { http } from '../http'
import type { ApiResponse } from '../../types/platform'
import type { AuditLog, AuditLogQuery, PageResult } from '../../types/admin'
import { normalizePage, type PagePayload } from './shared'

export async function getAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLog>> {
  const response = await http.get<ApiResponse<PagePayload<AuditLog>>>('/admin/v1/audits', {
    params: { ...query, page: query.page - 1 },
  })
  return normalizePage(response.data.data, query, true)
}

