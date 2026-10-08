import type { JsonValue, ScenarioVersion } from '../types/admin.js'

export interface ReleaseItem { itemType: string; objectId: number; objectVersionId: number }

/** Replace only the selected scenarios; every other active item stays in the release. */
export function mergeResponseVersions(items: ReleaseItem[], changes: Pick<ScenarioVersion, 'id' | 'scenarioId'>[]): number[] {
  if (!changes.length) throw new Error('请选择要发布的响应')
  const replacements = new Map<number, number>()
  for (const change of changes) {
    if (replacements.has(change.scenarioId)) throw new Error('同一响应不能同时发布两个版本')
    replacements.set(change.scenarioId, change.id)
  }
  const result = items.filter(item => item.itemType === 'SCENARIO')
    .filter(item => !replacements.has(item.objectId)).map(item => item.objectVersionId)
  return [...new Set([...result, ...replacements.values()])]
}

export function jsonObject(text: string, label: string): Record<string, JsonValue> {
  const value = JSON.parse(text) as JsonValue
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}必须是 JSON 对象`)
  return value
}

/** Examples suggest types, not mandatory fields or fixed values. Users confirm the schema. */
export function schemaFromExample(value: JsonValue): JsonValue {
  if (value === null) return { type: 'null' }
  if (Array.isArray(value)) return { type: 'array', ...(value.length ? { items: schemaFromExample(value[0]!) } : {}) }
  if (typeof value === 'object') return {
    type: 'object', properties: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, schemaFromExample(item)])),
  }
  return { type: typeof value === 'number' && Number.isInteger(value) ? 'integer' : typeof value }
}

export function sameJson(a: unknown, b: unknown): boolean {
  function canonical(value: unknown): string {
    if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
    if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => JSON.stringify(key) + ':' + canonical(item)).join(',') + '}'
    return JSON.stringify(value) ?? 'null'
  }
  return canonical(a) === canonical(b)
}

export function inScope(version: ScenarioVersion, environment: string, app: string): boolean {
  const scope = version.scope as { environments?: string[]; apps?: string[] }
  return Boolean(scope.environments?.includes(environment) && scope.apps?.includes(app))
}

export function editableResponse(version: ScenarioVersion, environment: string, app: string): boolean {
  const scope = version.scope as { environments?: string[]; apps?: string[]; tenants?: string[]; testAccounts?: string[] }
  const response = version.response as Record<string, JsonValue>
  const rules = version.matchRules as Record<string, JsonValue>[]
  return inScope(version, environment, app) && scope.environments?.length === 1 && scope.apps?.length === 1
    && !scope.tenants?.length && !scope.testAccounts?.length && !version.flowDefinitionVersionId
    && Array.isArray(version.callbacks) && !version.callbacks.length && !version.effectiveFrom && !version.effectiveTo
    && Array.isArray(rules) && rules.every(rule => ['JSON_PATH', 'HEADER', 'QUERY'].includes(String(rule.type)) && rule.operator === 'EQ')
    && Object.keys(response).every(key => ['httpStatus', 'headers', 'bodyTemplate'].includes(key))
    && typeof response.bodyTemplate === 'string' && !response.bodyTemplate.includes('${')
}
