import assert from 'node:assert/strict'

const base = process.env.API_BASE_URL ?? 'http://127.0.0.1:28080'
const bootstrapResponse = await fetch(`${base}/api/system/bootstrap`)
assert.equal(bootstrapResponse.status, 200)
const bootstrap = await bootstrapResponse.json()
assert.equal(bootstrap.service, 'game-service')
assert.equal(bootstrap.stage, 'scaffold')
assert.equal(bootstrap.protocolVersion, 1)
const gameAuthAvailable = process.env.EXPECT_GAME_AUTH_AVAILABLE === 'true'
assert.equal(bootstrap.features.authentication, gameAuthAvailable)
assert.equal(bootstrap.features.rooms, gameAuthAvailable)
assert.equal(bootstrap.features.gameplay, gameAuthAvailable)
assert.equal(bootstrap.features.roomText, gameAuthAvailable)
assert.equal(bootstrap.features.teamText, gameAuthAvailable)
assert.equal(bootstrap.features.teamVoice, false)

const identityResponse = await fetch(`${base}/api/auth/status`)
assert.equal(identityResponse.status, 200)
const identity = await identityResponse.json()
const authAvailable = process.env.EXPECT_AUTH_AVAILABLE === 'true'
assert.equal(identity.loginAvailable, authAvailable)
assert.equal(identity.registrationAvailable, authAvailable)

for (const path of ['/api/users/me', '/api/system/session', '/api/rooms/unknown',
  '/api/matches/history', '/api/voice/token']) {
  const response = await fetch(`${base}${path}`)
  assert.ok([401, 403].includes(response.status), `${path} must reject unauthenticated access`)
}
const forged = await fetch(`${base}/api/users/me`, {
  headers: { 'X-User-Id': '00000000-0000-0000-0000-000000000001', 'X-Authenticated-User': 'admin' },
})
assert.ok([401, 403].includes(forged.status), 'Caller-controlled identity headers must not create a session')
const internal = await fetch(`${base}/internal/auth/introspect`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
})
assert.equal(internal.status, 404, 'Gateway must not expose internal identity endpoints')
console.log('PASS: gateway routes, truthful feature flags, and default-deny protected paths.')
