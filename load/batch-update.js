import http from 'k6/http'
import { check } from 'k6'
import { Counter, Trend } from 'k6/metrics'

/**
 * The budget in CLAUDE.md: PATCH cells:batchUpdate p95 under 200 ms, local,
 * 50 virtual users.
 *
 * Each virtual user owns its own row for the whole run. That is deliberate.
 * Fifty users hammering ONE cell would measure the conflict path, because
 * every write but one would be a 409, and the number would say more about
 * optimistic locking than about throughput. Fifty users on fifty rows measures
 * what the endpoint actually does under load, and the conflict rate is
 * reported separately so it is visible rather than hidden in an average.
 *
 * Run with: make load
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080'

const conflicts = new Counter('conflicts')
const applied = new Counter('cells_applied')
const batchDuration = new Trend('batch_update_duration', true)

export const options = {
  scenarios: {
    batch_writes: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    // The budget, expressed as a gate rather than as a note.
    'http_req_duration{name:batchUpdate}': ['p(95)<200'],
    'http_req_failed{name:batchUpdate}': ['rate<0.01'],
  },
}

export function setup() {
  const email = `load-${Date.now()}@example.com`
  const password = 'correct-horse-battery'
  const json = { headers: { 'Content-Type': 'application/json' } }

  http.post(`${BASE}/api/v1/auth/register`,
    JSON.stringify({ email, password, displayName: 'Load' }), json)
  const token = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ email, password }), json).json('token')

  const auth = { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } }

  const sheetId = http.post(`${BASE}/api/v1/sheets`,
    JSON.stringify({ name: `Load ${Date.now()}` }), auth).json('id')
  const columnId = http.post(`${BASE}/api/v1/sheets/${sheetId}/columns`,
    JSON.stringify({ name: 'Task', type: 'TEXT' }), auth).json('id')

  // One row per virtual user, created up front so the measured phase is
  // nothing but cell writes.
  const vus = Number(__ENV.VUS || 50)
  const rows = []
  for (let i = 0; i < vus; i++) {
    rows.push(http.post(`${BASE}/api/v1/sheets/${sheetId}/rows`, null, auth).json('id'))
  }

  return { token, sheetId, columnId, rows }
}

export default function (data) {
  const auth = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
    tags: { name: 'batchUpdate' },
  }

  // Each VU keeps its own row and its own version counter, so it always knows
  // what version to expect and a conflict genuinely means someone raced it.
  const rowId = data.rows[(__VU - 1) % data.rows.length]
  if (!__ITER) {
    __ENV.__version = '1'
  }
  const expectedVersion = Number(__ENV.__version || '1') + __ITER

  const body = JSON.stringify({
    updates: [{
      rowId,
      columnId: data.columnId,
      value: `vu ${__VU} iteration ${__ITER}`,
      expectedVersion,
    }],
  })

  const response = http.patch(`${BASE}/api/v1/sheets/${data.sheetId}/cells:batchUpdate`, body, auth)
  batchDuration.add(response.timings.duration)

  if (response.status === 409) conflicts.add(1)
  if (response.status === 200) applied.add(1)

  check(response, {
    'not a server error': (r) => r.status < 500,
    'answered': (r) => r.status === 200 || r.status === 409,
  })
}
