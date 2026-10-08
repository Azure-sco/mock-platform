import assert from 'node:assert/strict'
import test from 'node:test'
import { formatShanghaiTime, shanghaiDayStart, shanghaiDayAfter } from '../node_modules/.cache/mock-platform-tests/utils/time.js'

test('Shanghai display converts UTC across midnight and handles missing times', () => {
  assert.equal(formatShanghaiTime('2026-09-29T17:30:00Z'), '2026-09-30 01:30:00')
  assert.equal(formatShanghaiTime('2026-09-30T01:30:00+08:00'), '2026-09-30 01:30:00')
  assert.equal(formatShanghaiTime(null), '—')
  assert.equal(formatShanghaiTime('invalid'), '—')
})

test('Shanghai filters use local midnight and exclusive next midnight', () => {
  assert.equal(shanghaiDayStart('2026-09-30'), '2026-09-29T16:00:00.000Z')
  assert.equal(shanghaiDayAfter('2026-09-30'), '2026-09-30T16:00:00.000Z')
  assert.equal(shanghaiDayAfter('2026-12-31'), '2026-12-31T16:00:00.000Z')
})
