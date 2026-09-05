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

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

