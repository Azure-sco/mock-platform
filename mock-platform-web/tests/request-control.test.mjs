import assert from 'node:assert/strict'
import test from 'node:test'
import {
  LatestRequestGate,
  SubmissionCoordinator,
} from '../node_modules/.cache/mock-platform-tests/requestControl.js'

test('only the newest cascading request may update state', () => {
  const gate = new LatestRequestGate()
  const first = gate.next()
  const second = gate.next()

  assert.equal(gate.isLatest(first), false)
  assert.equal(gate.isLatest(second), true)
})

test('a failed business submission reuses its key and concurrent entry is rejected', () => {
  let sequence = 0
  const coordinator = new SubmissionCoordinator(() => `key-${++sequence}`)
  const first = coordinator.begin('publish:1')

  assert.equal(first?.key, 'key-1')
  assert.equal(coordinator.begin('publish:1'), null)
  first?.finish(false)

  const retry = coordinator.begin('publish:1')
  assert.equal(retry?.key, 'key-1')
  retry?.finish(true)

  assert.equal(coordinator.begin('publish:1')?.key, 'key-2')
})

test('changing submission content creates a new key after a failed attempt', () => {
  let sequence = 0
  const coordinator = new SubmissionCoordinator(() => `key-${++sequence}`)
  const first = coordinator.begin('release:create', '{"version":1}')
  first?.finish(false)

  const changed = coordinator.begin('release:create', '{"version":2}')
  assert.equal(changed?.key, 'key-2')
})
