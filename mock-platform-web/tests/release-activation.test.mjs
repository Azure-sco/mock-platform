import assert from 'node:assert/strict'
import test from 'node:test'
import {
  activationVersionFor,
  releaseScopeKey,
} from '../node_modules/.cache/mock-platform-tests/releaseActivation.js'

test('release activation uses the authority for the target environment and app', () => {
  const release = { environment: 'UAT', appCode: 'orders' }
  const active = { environment: 'UAT', appCode: 'orders', activationVersion: 7 }

  assert.equal(releaseScopeKey(release), 'UAT\u0000orders')
  assert.equal(activationVersionFor(release, active), 7)
  assert.throws(
    () => activationVersionFor(release, { ...active, environment: 'TEST' }),
    /不一致/,
  )
})

test('an authoritative empty scope starts at activation version zero', () => {
  assert.equal(activationVersionFor({ environment: 'TEST', appCode: 'new-app' }, null), 0)
})
