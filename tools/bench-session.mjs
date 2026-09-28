import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

function loopback(name) {
  const raw = process.env[name]
  assert.ok(raw, `${name} is required`)
  const url = new URL(raw)
  assert.equal(url.protocol, 'http:', `${name} must use local HTTP`)
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname),
    `${name} must point to loopback`)
  assert.equal(url.pathname, '/', `${name} must be an origin without a path`)
  return url.origin
}

function positiveInteger(name, fallback, maximum) {
  const value = Number(process.env[name] ?? fallback)
  assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum,
    `${name} must be an integer from 1 to ${maximum}`)
  return value
}

const api = loopback('API_BASE_URL')
const mailpit = loopback('MAILPIT_BASE_URL')
const durationSeconds = positiveInteger('BENCH_DURATION_SECONDS', 15, 60)
const concurrency = positiveInteger('BENCH_CONCURRENCY', 8, 64)
const maximumRequests = positiveInteger('BENCH_MAX_REQUESTS', 50000, 100000)
const email = `uno-bench-${randomUUID()}@example.test`
const password = `Bench-${randomUUID()}-Pass1!`

function request(path, body, token) {
  return fetch(`${api}${path}`, {
    method: body ? 'POST' : 'GET',
    headers: {
      'X-UNO-Client': 'APP',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(5000),
  })
}

assert.equal((await request('/api/auth/register', { email, password, nickname: 'Bench Player' })).status, 202)
let verificationToken
for (let attempt = 0; attempt < 30; attempt++) {
  const listing = await (await fetch(`${mailpit}/api/v1/messages`, {
    signal: AbortSignal.timeout(5000),
  })).json()
  const message = listing.messages.find(item => item.To?.some(to => to.Address === email))
  if (message) {
    const detail = await (await fetch(`${mailpit}/api/v1/message/${encodeURIComponent(message.ID)}`, {
      signal: AbortSignal.timeout(5000),
    })).json()
    verificationToken = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
    if (verificationToken) break
  }
  await new Promise(resolve => setTimeout(resolve, 500))
}
assert.ok(verificationToken, 'verification email was not delivered')
assert.equal((await request('/api/auth/verify-email', { token: verificationToken })).status, 204)
const login = await request('/api/auth/login', { email, password })
assert.equal(login.status, 200)
const { accessToken, user } = await login.json()

const times = []
const statuses = new Map()
const started = performance.now()
const deadline = started + durationSeconds * 1000
let nextRequest = 0
await Promise.all(Array.from({ length: concurrency }, async () => {
  while (performance.now() < deadline && nextRequest++ < maximumRequests) {
    const before = performance.now()
    let key
    try {
      const response = await request('/api/system/session', undefined, accessToken)
      const body = await response.json()
      key = response.status === 200 && body.userId === user.id
        ? '200' : `${response.status} ${body.code ?? 'UNEXPECTED_BODY'}`
    } catch {
      key = 'NETWORK_ERROR'
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    times.push(performance.now() - before)
    statuses.set(key, (statuses.get(key) ?? 0) + 1)
  }
}))
times.sort((left, right) => left - right)
const elapsedSeconds = (performance.now() - started) / 1000
const percentile = fraction => Math.round(times[Math.max(0, Math.ceil(times.length * fraction) - 1)] ?? 0)
const result = {
  target: api,
  scenario: 'one real App account, GET /api/system/session, no think time',
  durationSeconds,
  concurrency,
  requests: times.length,
  elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
  requestsPerSecond: Math.round(times.length / elapsedSeconds),
  statuses: Object.fromEntries(statuses),
  latencyMilliseconds: {
    p50: percentile(0.5),
    p95: percentile(0.95),
    p99: percentile(0.99),
    max: percentile(1),
  },
}
console.log(JSON.stringify(result, null, 2))
assert.equal(statuses.size, 1, 'session benchmark observed non-200 responses')
assert.equal(statuses.get('200'), times.length)
