import type { PageQuery, PageResult } from '../../types/admin'

export interface PagePayload<T> {
  records?: T[]
  items?: T[]
  total?: number
  page?: number
  size?: number
}

export function normalizePage<T>(
  payload: PagePayload<T> | T[],
  fallback: PageQuery,
  serverZeroBased = false,
): PageResult<T> {
  if (Array.isArray(payload)) {
    const start = (fallback.page - 1) * fallback.size
    return {
      records: payload.slice(start, start + fallback.size),
      total: payload.length,
      page: fallback.page,
      size: fallback.size,
    }
  }
  const records = payload.records ?? payload.items ?? []
  const payloadPage = payload.page ?? (serverZeroBased ? fallback.page - 1 : fallback.page)
  return {
    records,
    total: payload.total ?? records.length,
    page: serverZeroBased ? payloadPage + 1 : payloadPage,
    size: payload.size ?? fallback.size,
  }
}

export function listPayload<T>(payload: PagePayload<T> | T[]): T[] {
  return Array.isArray(payload) ? payload : (payload.records ?? payload.items ?? [])
}

export function writeHeaders(idempotencyKey?: string) {
  return { 'Idempotency-Key': idempotencyKey ?? `web-${crypto.randomUUID()}` }
}

