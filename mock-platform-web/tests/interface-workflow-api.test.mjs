import assert from 'node:assert/strict'
import test from 'node:test'
import { build } from 'vite'
import { mkdir, writeFile } from 'node:fs/promises'

const built = await build({ configFile: false, logLevel: 'silent', build: { ssr: true, write: false, rollupOptions: {
  input: 'tests/fixtures/workflow-entry.ts', output: { format: 'es' },
} } })
const output = (Array.isArray(built) ? built[0] : built).output.find(item => item.type === 'chunk' && item.isEntry)
await mkdir('node_modules/.cache/mock-platform-tests', { recursive: true })
await writeFile('node_modules/.cache/mock-platform-tests/workflow-api.mjs', output.code)
const { http, saveResponse, ensureContract, assertSameBase, activateResponseRelease, useSessionStore, pinia } =
  await import('../node_modules/.cache/mock-platform-tests/workflow-api.mjs')

function respond(config, data) { return { data: { success: true, data }, status: 200, statusText: 'OK', headers: {}, config } }
const root = { scenarioCode: 'response-test', scenarioName: '成功', providerId: 1, apiId: 2 }
const payload = { contractVersionId: 3, priority: 100, scope: { environments: ['TEST'], apps: ['ecs'], tenants: [], testAccounts: [] },
  matchRules: [], response: { httpStatus: 200, headers: {}, bodyTemplate: '{}' }, callbacks: [] }

test('response save recovers a committed version after a lost response, validates then submits only once', async () => {
  let scenario, version, creates = 0, submits = 0
  http.defaults.adapter = async config => {
    const path = config.url
    if (config.method === 'get' && path === '/admin/v1/scenarios') return respond(config, scenario ? [scenario] : [])
    if (config.method === 'post' && path === '/admin/v1/scenarios') { scenario = { ...root, id: 4 }; return respond(config, scenario) }
    if (config.method === 'get' && path === '/admin/v1/scenarios/4') return respond(config, { scenario, versions: version ? [version] : [] })
    if (path === '/admin/v1/scenarios/4/versions') {
      creates++; version = { ...JSON.parse(config.data), id: 5, scenarioId: 4, status: 'DRAFT', validationStatus: null }
      throw new Error('response lost after commit')
    }
    if (path === '/admin/v1/scenario-versions/5/validate') {
      version.status = 'VALIDATED'; version.validationStatus = 'VALID'; return respond(config, version)
    }
    if (path === '/admin/v1/scenario-versions/5/submit-approval') {
      submits++; version.status = 'PENDING_APPROVAL'; return respond(config, {})
    }
    throw new Error('Unexpected ' + config.method + ' ' + path)
  }
  await saveResponse(root, payload, true)
  await saveResponse(root, payload, true)
  assert.equal(creates, 1); assert.equal(submits, 1)
  assert.equal(version.status, 'PENDING_APPROVAL')
})

test('invalid response never proceeds to approval', async () => {
  const version = { ...payload, id: 5, scenarioId: 4, status: 'DRAFT' }
  http.defaults.adapter = async config => {
    if (config.url === '/admin/v1/scenarios') return respond(config, [{ ...root, id: 4 }])
    if (config.url === '/admin/v1/scenarios/4') return respond(config, { scenario: { ...root, id: 4 }, versions: [version] })
    if (config.url.endsWith('/validate')) return respond(config, { ...version, validationStatus: 'INVALID', validationResult: { errors: ['conflict'] } })
    assert.fail('Invalid draft must not submit: ' + config.url)
  }
  await assert.rejects(saveResponse(root, payload, true), /校验未通过/)
})

test('contract retry resumes saved validated draft and does not create duplicate contracts', async () => {
  const contract = { id: 3, apiId: 2, status: 'VALIDATED', requestSchema: {}, responseSchema: {}, sourceType: 'MANUAL',
    examples: [], errorCodes: [], businessKeyExtractor: {}, signatureMetadata: {} }
  http.defaults.adapter = async config => {
    if (config.method === 'get') return respond(config, [contract])
    assert.equal(config.url, '/admin/v1/contracts/3/publish')
    contract.status = 'PUBLISHED'; return respond(config, contract)
  }
  assert.equal((await ensureContract(2, contract)).status, 'PUBLISHED')
})

test('activation retries keep server request identity and never advance expected version', async () => {
  const calls = []
  http.defaults.adapter = async config => {
    calls.push({ requestId: config.headers.get('X-Request-Id'), body: JSON.parse(config.data) })
    if (calls.length === 1) throw new Error('timeout after activation commit')
    return respond(config, { activation: { id: 'activation-1', status: 'PROJECTED' }, targets: [] })
  }
  await assert.rejects(activateResponseRelease('release-1', 3, 'stable-request'), /timeout/)
  assert.equal((await activateResponseRelease('release-1', 3, 'stable-request')).activation.id, 'activation-1')
  assert.deepEqual(calls, [{ requestId: 'stable-request', body: { expectedActivationVersion: 3 } },
    { requestId: 'stable-request', body: { expectedActivationVersion: 3 } }])
})

test('workflow does not impersonate another operator and propagates server denial', async () => {
  useSessionStore(pinia).operatorId = 'local-reviewer'
  http.defaults.adapter = async config => {
    assert.equal(config.headers.get('X-Operator-Id'), 'local-reviewer')
    throw new Error('FORBIDDEN')
  }
  await assert.rejects(activateResponseRelease('release-1', 3, 'request'), /FORBIDDEN/)
  useSessionStore(pinia).operatorId = 'local-admin'
})

test('concurrent release and rollback versions require a fresh review', () => {
  const base = { releaseId: 'a', activationVersion: 2 }
  assert.doesNotThrow(() => assertSameBase(null, null))
  assert.doesNotThrow(() => assertSameBase(base, { ...base }))
  assert.throws(() => assertSameBase(base, { ...base, activationVersion: 3 }), /重新核对/)
  assert.throws(() => assertSameBase(null, base), /重新核对/)
})
