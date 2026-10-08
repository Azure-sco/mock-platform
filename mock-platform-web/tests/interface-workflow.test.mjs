import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeResponseVersions, schemaFromExample, editableResponse, inScope, sameJson } from '../node_modules/.cache/mock-platform-tests/utils/interfaceWorkflow.js'

test('publishing one response preserves all other scenarios, including those of the same API', () => {
  const items = [
    { itemType: 'SCENARIO', objectId: 1, objectVersionId: 10 },
    { itemType: 'SCENARIO', objectId: 2, objectVersionId: 20 },
    { itemType: 'CONTRACT', objectId: 3, objectVersionId: 30 },
  ]
  assert.deepEqual(mergeResponseVersions(items, [{ scenarioId: 1, id: 11 }, { scenarioId: 4, id: 40 }]), [20, 11, 40])
  assert.deepEqual(mergeResponseVersions([], [{ scenarioId: 1, id: 10 }]), [10])
  assert.throws(() => mergeResponseVersions(items, []), /请选择/)
  assert.throws(() => mergeResponseVersions(items, [{ scenarioId: 1, id: 11 }, { scenarioId: 1, id: 12 }]), /两个版本/)
})

test('example schema preserves nested types without turning sample values into mandatory constants', () => {
  assert.deepEqual(schemaFromExample({ code: '0', data: { amount: 1.2, ok: true, items: [1], absent: null } }), {
    type: 'object', properties: { code: { type: 'string' }, data: { type: 'object', properties: {
      amount: { type: 'number' }, ok: { type: 'boolean' }, items: { type: 'array', items: { type: 'integer' } }, absent: { type: 'null' },
    } } },
  })
  assert.deepEqual(schemaFromExample([]), { type: 'array' })
})

test('simple editor cannot flatten restricted, shared, stateful or dynamic scenarios', () => {
  const version = { scope: { environments: ['TEST'], apps: ['ecs'], tenants: [], testAccounts: [] },
    matchRules: [{ type: 'JSON_PATH', key: '$.result', operator: 'EQ', value: 'ok' }], callbacks: [],
    response: { httpStatus: 200, headers: {}, bodyTemplate: '{}' } }
  assert.equal(editableResponse(version, 'TEST', 'ecs'), true)
  assert.equal(inScope(version, 'UAT', 'ecs'), false)
  for (const patch of [
    { scope: { ...version.scope, apps: ['ecs', 'other'] } },
    { scope: { ...version.scope, tenants: ['tenant-a'] } },
    { flowDefinitionVersionId: 1 }, { callbacks: [{}] }, { effectiveTo: '2030-01-01T00:00:00Z' },
    { response: { ...version.response, delayMs: 100 } },
    { response: { ...version.response, bodyTemplate: '${mockRequestId}' } },
    { matchRules: [{ type: 'JSON_PATH', operator: 'EXISTS' }] },
  ]) assert.equal(editableResponse({ ...version, ...patch }, 'TEST', 'ecs'), false)
})

test('retry content comparison ignores property order but preserves array and scope semantics', () => {
  assert.equal(sameJson({ a: 1, b: [2, 3] }, { b: [2, 3], a: 1 }), true)
  assert.equal(sameJson({ a: [2, 3] }, { a: [3, 2] }), false)
  assert.equal(sameJson({ apps: ['ecs'] }, { apps: ['other'] }), false)
})
