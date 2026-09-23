import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const api = process.env.API_BASE_URL ?? 'http://127.0.0.1:28080'
const mailpit = process.env.MAILPIT_BASE_URL ?? 'http://127.0.0.1:28025'
const clientHeaders = { 'X-UNO-Client': 'APP' }

async function request(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...clientHeaders,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const content = response.headers.get('content-type')?.includes('application/json')
    ? await response.json() : undefined
  return { status: response.status, body: content }
}

async function verificationToken(email) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await fetch(`${mailpit}/api/v1/messages`)
    assert.equal(response.status, 200, 'Mailpit must be available for this local smoke test')
    const listing = await response.json()
    const message = listing.messages.find(item => item.To?.some(to => to.Address === email))
    if (message) {
      const detailResponse = await fetch(`${mailpit}/api/v1/message/${encodeURIComponent(message.ID)}`)
      assert.equal(detailResponse.status, 200)
      const detail = await detailResponse.json()
      const token = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
      assert.ok(token, 'Verification email must contain a token')
      return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('Verification email was not delivered to Mailpit within 15 seconds')
}

async function account(label) {
  const email = `uno-smoke-${randomUUID()}@example.test`
  const password = `Smoke-${randomUUID()}-Pass1!`
  const registered = await request('/api/auth/register', {
    method: 'POST', body: { email, password, nickname: label },
  })
  assert.equal(registered.status, 202, `${label} registration`)
  const verified = await request('/api/auth/verify-email', {
    method: 'POST', body: { token: await verificationToken(email) },
  })
  assert.equal(verified.status, 204, `${label} verification`)
  const loggedIn = await request('/api/auth/login', {
    method: 'POST', body: { email, password },
  })
  assert.equal(loggedIn.status, 200, `${label} login`)
  assert.ok(loggedIn.body.accessToken)
  return loggedIn.body.accessToken
}

const host = await account('Smoke Host')
const guest = await account('Smoke Guest')
const created = await request('/api/rooms', {
  method: 'POST', token: host, body: { mode: 'CLASSIC', maxPlayers: 2 },
})
assert.equal(created.status, 201, 'room creation')
const room = created.body
assert.ok(room.id && room.code)
const joined = await request('/api/rooms/join', {
  method: 'POST', token: guest, body: { code: room.code },
})
assert.equal(joined.status, 200, 'room join')
assert.equal(joined.body.id, room.id)

const firstId = randomUUID()
const first = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: host, body: { clientMessageId: firstId, content: 'Container smoke: hello' },
})
assert.equal(first.status, 200, 'host message')
const repeated = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: host, body: { clientMessageId: firstId, content: 'Container smoke: hello' },
})
assert.equal(repeated.status, 200, 'idempotent message retry')
assert.equal(repeated.body.id, first.body.id)
const second = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: guest, body: { clientMessageId: randomUUID(), content: 'Container smoke: received' },
})
assert.equal(second.status, 200, 'guest message')
assert.ok(second.body.sequence > first.body.sequence)
const history = await request(`/api/rooms/${room.id}/messages`, { token: guest })
assert.equal(history.status, 200, 'guest history')
assert.deepEqual(history.body.items.map(item => item.id), [first.body.id, second.body.id])
const left = await request(`/api/rooms/${room.id}/leave`, { method: 'POST', token: guest })
assert.equal(left.status, 204, 'guest leave')
const afterLeave = await request(`/api/rooms/${room.id}/messages`, { token: guest })
assert.equal(afterLeave.status, 404, 'former member must lose chat access')

console.log('PASS: containerized registration, Mailpit verification, App login, room join, bidirectional text, idempotency, and leave access.')
